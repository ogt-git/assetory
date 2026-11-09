package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.evidence.EvidenceCollector;
import com.project.assetory.analysis.evidence.EvidenceTarget;
import com.project.assetory.analysis.exception.EvidenceCollectException;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult;
import com.project.assetory.analysis.service.EvidenceCollectResult.Outcome;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * 근거 수집 흐름 (B1 Phase 3) — 실제 수집기 없이 테스트 소스의 가짜 수집기 3개(공시 · 뉴스 · 거시지표)로 확인한다.
 * 상태 전이(확정 사항 2): 락 → PENDING만 수집 → 수집기는 트랜잭션 밖 · 실패 격리 → 저장 + 건수 + 상태를 한 트랜잭션에서.
 * 0건 NO_EVIDENCE · 1건 이상 COLLECTING(요약 대기) · 실행한 수집기가 모두 실패하면 아무것도 바꾸지 않고 PENDING 유지.
 */
@Import(EvidenceCollectServiceIntegrationTest.FakeCollectors.class)
class EvidenceCollectServiceIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호

    @Autowired EvidenceCollectService service;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @MockitoSpyBean EventSourceRepository eventSourceRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;

    @Autowired @Qualifier("disclosureFake") FakeCollector disclosure;
    @Autowired @Qualifier("newsFake") FakeCollector news;
    @Autowired @Qualifier("macroFake") FakeCollector macro;

    @BeforeEach
    void resetFakes() {
        List.of(disclosure, news, macro).forEach(FakeCollector::reset);
    }

    @AfterEach
    void cleanLocks() {
        // 테스트가 직접 잡은 락이 남지 않게 (종목코드는 테스트마다 새로라 다른 테스트와 겹치지 않는다)
        var keys = redis.keys("lock:analysis:T*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @DisplayName("1. ETF면 공시 · 뉴스 수집기를 부르지 않는다 — 거시지표 수집기만 실행 (FR-CA-01 · 02)")
    void etf_skipsDisclosureAndNews() {
        StockEventAnalysis analysis = newAnalysis();
        holding(analysis.getTicker(), SecurityType.ETF);
        macro.behavior = t -> List.of(evidence(SourceType.MACRO, "macro-1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(disclosure.calls.get()).isZero();
        assertThat(news.calls.get()).isZero();
        assertThat(macro.calls.get()).isEqualTo(1);
        assertThat(macro.lastTarget.etf()).isTrue();
        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.runs()).extracting(EvidenceCollectResult.CollectorRun::sourceType).containsExactly(SourceType.MACRO);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("1-b. 보유 종목에 ETF 표시가 없으면(STOCK · NULL · 보유 없음) ETF로 보지 않고 모두 수집한다")
    void notEtf_collectsAll() {
        StockEventAnalysis analysis = newAnalysis();
        holding(analysis.getTicker(), null);

        service.collect(analysis.getId());

        assertThat(disclosure.calls.get()).isEqualTo(1);
        assertThat(news.calls.get()).isEqualTo(1);
        assertThat(macro.calls.get()).isEqualTo(1);
        assertThat(disclosure.lastTarget).isEqualTo(new EvidenceTarget(analysis.getTicker(), "테스트 종목", DATE, false));
    }

    @Test
    @DisplayName("2. 수집기 하나가 예외를 내도 다른 수집기 근거는 저장되고 COLLECTING")
    void oneCollectorFails_othersSaved() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "https://news.example.com/1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.runs()).filteredOn(r -> r.sourceType() == SourceType.DISCLOSURE)
                .singleElement().satisfies(r -> assertThat(r.success()).isFalse());
        assertThat(externalIds(analysis)).containsExactly("https://news.example.com/1");
        StockEventAnalysis reloaded = reload(analysis);
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(reloaded.getEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 실행한 수집기가 모두 실패하면 저장 0 · PENDING 유지 · evidence_count 그대로 — 다음 실행 때 재시도")
    void allCollectorsFail_staysPending() {
        StockEventAnalysis analysis = newAnalysis();
        Function<EvidenceTarget, List<CollectedEvidence>> fail = t -> {
            throw new IllegalStateException("테스트 — 예상 밖 오류");
        };
        disclosure.behavior = fail;
        news.behavior = fail;
        macro.behavior = fail;

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        StockEventAnalysis reloaded = reload(analysis);
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(reloaded.getEvidenceCount()).isZero();
        assertThat(lockValue(analysis)).isNull();

        // 다음 실행에서 성공하면 정상 진행된다
        macro.behavior = t -> List.of(evidence(SourceType.MACRO, "macro-retry"));
        assertThat(service.collect(analysis.getId()).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("4. 같은 externalId 근거가 두 수집기 · 한 수집기 안에서 두 번 와도 1건")
    void duplicateExternalId_savedOnce() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "same"), evidence(SourceType.DISCLOSURE, "same"));
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "same"), evidence(SourceType.NEWS, "other"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(externalIds(analysis)).containsExactlyInAnyOrder("same", "other");
        assertThat(result.savedCount()).isEqualTo(2);
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("5. 수집이 끝난 분석을 다시 부르면 수집기를 부르지 않고 아무것도 바꾸지 않는다 (멱등)")
    void rerun_idempotent() {
        StockEventAnalysis analysis = newAnalysis();
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));
        service.collect(analysis.getId());
        LocalDateTime updatedAt = reload(analysis).getUpdatedAt();

        EvidenceCollectResult second = service.collect(analysis.getId());

        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_NOT_PENDING);
        assertThat(second.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(news.calls.get()).isEqualTo(1);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isEqualTo(1);
        assertThat(reload(analysis).getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(lockValue(analysis)).isNull();
    }

    @Test
    @DisplayName("6. 락이 이미 잡혀 있으면 수집기를 부르지 않고 건너뛰며, 남의 락은 그대로 둔다")
    void lockHeld_skipped() {
        StockEventAnalysis analysis = newAnalysis();
        redis.opsForValue().set(lockKey(analysis), "other-owner");

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(disclosure.calls.get() + news.calls.get() + macro.calls.get()).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(lockValue(analysis)).isEqualTo("other-owner");
    }

    @Test
    @DisplayName("7. 근거 0건 → NO_EVIDENCE · 1건 이상 → COLLECTING, evidence_count = 실제 행 수")
    void status_byEvidenceCount() {
        StockEventAnalysis empty = newAnalysis();
        EvidenceCollectResult emptyResult = service.collect(empty.getId());
        assertThat(emptyResult.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(emptyResult.status()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(empty).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(empty).getEvidenceCount()).isZero();

        StockEventAnalysis found = newAnalysis();
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-1"), evidence(SourceType.DISCLOSURE, "d-2"));
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));
        EvidenceCollectResult foundResult = service.collect(found.getId());
        assertThat(foundResult.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(reload(found).getEvidenceCount()).isEqualTo(3)
                .isEqualTo((int) eventSourceRepository.countByAnalysisId(found.getId()));
    }

    @Test
    @DisplayName("8. 길이 초과 · url 빈 값 근거는 그 건만 제외하고 나머지는 저장한다")
    void invalidEvidence_excludedOnly() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> List.of(
                evidence(SourceType.DISCLOSURE, "ok"),
                new CollectedEvidence(SourceType.DISCLOSURE, "url 없음", "DART", null, " ", null, null, "no-url"),
                new CollectedEvidence(SourceType.DISCLOSURE, "긴 id", "DART", null, "https://e.com/x", null, null, "x".repeat(201)),
                new CollectedEvidence(SourceType.DISCLOSURE, "가".repeat(600), "DART", null, "https://e.com/long", null, null, "long-title"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(externalIds(analysis)).containsExactlyInAnyOrder("ok", "long-title");
        assertThat(result.excludedCount()).isEqualTo(2);
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("9. 저장 단계에서 예외가 나면 롤백 — 근거 0행 · PENDING 유지 · 락 해제")
    void saveFailure_rollsBack() {
        StockEventAnalysis analysis = newAnalysis();
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"), evidence(SourceType.NEWS, "n-2"));
        doThrow(new IllegalStateException("테스트 — 저장 실패")).when(eventSourceRepository).countByAnalysisId(analysis.getId());

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.SAVE_FAILED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.PENDING);
        org.mockito.Mockito.reset(eventSourceRepository);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(lockValue(analysis)).isNull();
    }

    @Test
    @DisplayName("10. 수집 중 락이 만료돼 다른 쪽이 새로 잡았으면, 끝날 때 그 락을 지우지 않는다 (소유자만 해제)")
    void releaseOnlyOwnLock() {
        StockEventAnalysis analysis = newAnalysis();
        news.behavior = t -> {
            // 락 TTL이 지나 다른 실행이 같은 키를 잡은 상황을 흉내 낸다
            redis.opsForValue().set(lockKey(analysis), "other-owner");
            return List.of(evidence(SourceType.NEWS, "n-1"));
        };

        service.collect(analysis.getId());

        assertThat(lockValue(analysis)).isEqualTo("other-owner");
    }

    @Test
    @DisplayName("없는 분석 id는 아무것도 하지 않는다")
    void missingAnalysis() {
        EvidenceCollectResult result = service.collect(Long.MAX_VALUE);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(disclosure.calls.get() + news.calls.get() + macro.calls.get()).isZero();
    }

    // ───────── 보조 ─────────

    private StockEventAnalysis newAnalysis() {
        return analysisRepository.saveAndFlush(
                StockEventAnalysis.create(uniqueTicker(), "테스트 종목", DATE, new BigDecimal("-6.1")));
    }

    private StockEventAnalysis reload(StockEventAnalysis analysis) {
        return analysisRepository.findById(analysis.getId()).orElseThrow();
    }

    private List<String> externalIds(StockEventAnalysis analysis) {
        return eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId()).stream()
                .map(EventSource::getExternalId).toList();
    }

    private String lockKey(StockEventAnalysis analysis) {
        return "lock:analysis:" + analysis.getTicker() + ":" + analysis.getEventDate();
    }

    private String lockValue(StockEventAnalysis analysis) {
        return redis.opsForValue().get(lockKey(analysis));
    }

    private void holding(String ticker, SecurityType securityType) {
        String email = uniqueEmail();
        User user = userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
        tx.executeWithoutResult(status -> {
            Institution institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-evidence-" + UUID.randomUUID(), BusinessType.ST));
            Asset asset = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER));
            Holding h = Holding.create(asset, ticker);
            h.update("테스트 종목", securityType, BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("999"),
                    new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("99.9999"));
            holdingRepository.save(h);
        });
    }

    private static CollectedEvidence evidence(SourceType type, String externalId) {
        return new CollectedEvidence(type, "테스트 근거 " + externalId, type == SourceType.DISCLOSURE ? "DART" : "테스트 출처",
                type == SourceType.DISCLOSURE ? null : LocalDateTime.of(2026, 11, 5, 10, 0),
                "https://example.com/" + Math.abs(externalId.hashCode()), null, null, externalId);
    }

    // 테스트 전용 가짜 종목코드 (다른 테스트와 겹치지 않게 매번 새로)
    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    /** 테스트 전용 가짜 수집기 — 동작(behavior)을 테스트마다 바꾸고 호출 횟수를 센다 */
    static class FakeCollector implements EvidenceCollector {
        private final SourceType type;
        private final boolean skipEtf;
        volatile Function<EvidenceTarget, List<CollectedEvidence>> behavior;
        volatile EvidenceTarget lastTarget;
        final AtomicInteger calls = new AtomicInteger();

        FakeCollector(SourceType type, boolean skipEtf) {
            this.type = type;
            this.skipEtf = skipEtf;
            reset();
        }

        void reset() {
            behavior = t -> new ArrayList<>();
            lastTarget = null;
            calls.set(0);
        }

        @Override
        public SourceType sourceType() {
            return type;
        }

        @Override
        public boolean supports(EvidenceTarget target) {
            return !(skipEtf && target.etf());
        }

        @Override
        public List<CollectedEvidence> collect(EvidenceTarget target) {
            calls.incrementAndGet();
            lastTarget = target;
            return behavior.apply(target);
        }
    }

    @TestConfiguration
    static class FakeCollectors {
        @Bean
        FakeCollector disclosureFake() {
            return new FakeCollector(SourceType.DISCLOSURE, true);
        }

        @Bean
        FakeCollector newsFake() {
            return new FakeCollector(SourceType.NEWS, true);
        }

        @Bean
        FakeCollector macroFake() {
            return new FakeCollector(SourceType.MACRO, false);
        }
    }
}
