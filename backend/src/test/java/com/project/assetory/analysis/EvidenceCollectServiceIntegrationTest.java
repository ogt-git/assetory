package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.EvidenceStatus;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicatorRow;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.kis.KisStockInfo;
import com.project.assetory.market.service.MacroIndicatorWriter;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.project.assetory.analysis.dart.DartDisclosureCollector;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 근거 수집 흐름 (B1 Phase 3) — 실제 수집기 없이 테스트 소스의 가짜 수집기 3개(공시 · 뉴스 · 거시지표)로 확인한다.
 * 상태 전이(확정 사항 2): 락 → PENDING만 수집 → 수집기는 트랜잭션 밖 · 실패 격리 → 저장 + 건수 + 상태를 한 트랜잭션에서.
 * 0건 NO_EVIDENCE · 1건 이상 COLLECTING(요약 대기) · 실행한 수집기가 모두 실패하면 아무것도 바꾸지 않고 PENDING 유지.
 */
@Import(EvidenceCollectServiceIntegrationTest.FakeCollectors.class)
class EvidenceCollectServiceIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호
    // 판정 테스트 전용 지수 날짜 — macro_indicators는 (지수, 날짜) UNIQUE라 다른 테스트와 겹치지 않는 해로 둔다
    private static final LocalDate MARKET_DATE = LocalDate.of(2037, 3, 4);
    private static final LocalDate MISSING_MARKET_DATE = LocalDate.of(2037, 3, 5);
    private static final LocalDate KOSDAQ_DATE = LocalDate.of(2037, 3, 6);

    @Autowired EvidenceCollectService service;
    // 시장 구분(Q-2) 조회 — 실제 한투를 부르지 않는다. 설정하지 않은 종목은 null 응답 → 시장 구분 없음
    @MockitoBean KisApiClient kisApiClient;
    @Autowired MacroIndicatorWriter macroIndicatorWriter;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @MockitoSpyBean EventSourceRepository eventSourceRepository;
    // 운영 수집기(B2 DART · B3 네이버)는 이 테스트에서 뺀다 — 목의 supports()는 false라 실행되지 않고, 가짜 수집기 3개만 돈다
    @MockitoBean DartDisclosureCollector dartDisclosureCollector;
    @MockitoBean com.project.assetory.analysis.naver.NaverNewsCollector naverNewsCollector;
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
    @DisplayName("1. ETF면 공시 · 뉴스 수집기를 부르지 않는다 — 거시지표 수집기만 실행 (FR-CA-01 · 02), 공시가 없어 요약 생략 (Q-3)")
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
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
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
    @DisplayName("2. 공시 수집기가 (다시 하면 될 수 있는 오류로) 실패하면 다른 근거는 저장하고 PENDING 유지 — 재시도 대상 (Q-6 D2)")
    void oneCollectorFails_othersSaved() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "https://news.example.com/1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.PENDING_RETRY);
        assertThat(result.runs()).filteredOn(r -> r.sourceType() == SourceType.DISCLOSURE)
                .singleElement().satisfies(r -> {
                    assertThat(r.success()).isFalse();
                    assertThat(r.retryable()).isTrue();
                });
        assertThat(externalIds(analysis)).containsExactly("https://news.example.com/1");
        StockEventAnalysis reloaded = reload(analysis);
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        // 수집이 끝나지 않아 아직 세지 않는다 — 재시도가 끝날 때 실제 행 수로 다시 센다
        assertThat(reloaded.getEvidenceCount()).isZero();
    }

    @Test
    @DisplayName("2-b. D2 재시도: 다음 실행에서 공시가 성공하면 COLLECTING, 뉴스 행은 중복 없이 1건 · evidence_count = 실제 행 수")
    void disclosureRetry_thenSucceeds() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));
        service.collect(analysis.getId());

        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-1"));
        EvidenceCollectResult retried = service.collect(analysis.getId());

        assertThat(retried.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(retried.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(externalIds(analysis)).containsExactlyInAnyOrder("n-1", "d-1");
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(2)
                .isEqualTo((int) eventSourceRepository.countByAnalysisId(analysis.getId()));
    }

    @Test
    @DisplayName("2-c. D2 재시도: 다음 실행에서 공시 0건 성공이면 뉴스만이라 SUMMARY_SKIPPED (Q-3)")
    void disclosureRetry_thenZero() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));
        service.collect(analysis.getId());

        disclosure.behavior = t -> List.of();
        EvidenceCollectResult retried = service.collect(analysis.getId());

        assertThat(retried.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("2-d. 다시 해도 같은 공시 실패(키 오류 등)는 D2를 타지 않는다 — 지금처럼 완료 처리, 뉴스만이면 SUMMARY_SKIPPED")
    void nonRetryableDisclosureFailure_completes() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 키 오류", null, false);
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(1);
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

        // 다음 실행에서 성공하면 정상 진행된다 (거시지표만 저장 → 공시가 없어 요약 생략, Q-3).
        // 공시도 0건 성공으로 바꾼다 — 공시가 계속 재시도 가능한 오류면 Q-6 D2로 PENDING에 머문다(2 · 2-b 테스트가 따로 본다)
        disclosure.behavior = t -> List.of();
        macro.behavior = t -> List.of(evidence(SourceType.MACRO, "macro-retry"));
        assertThat(service.collect(analysis.getId()).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
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
        assertThat(second.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED); // 뉴스만 → 요약 생략(Q-3), 재실행해도 그대로
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
    @DisplayName("11. 판정 — 종목정보 STK → KOSPI 지수로 지수 변동률 · 초과 변동을 저장하고 근거 0건이면 기준대로 MARKET_MOVE (01 §14 #12, Q-2)")
    void judge_withMarketIndex() {
        // 지수 날짜는 다른 테스트와 겹치지 않게 따로 둔다
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        market(analysis, "STK");
        index(IndicatorCode.KOSPI, MARKET_DATE, "-4.0000");

        EvidenceCollectResult result = service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(result.status()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(saved.getMarketIndexCode()).isEqualTo("KOSPI");
        assertThat(saved.getMarketChangeRate()).isEqualByComparingTo("-4.0000");
        assertThat(saved.getExcessRate()).isEqualByComparingTo("-1.5000");
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("12. 판정 — 시장 구분이 없으면 지수를 추측하지 않는다: 지수 변동률 · 초과 변동 NULL, 근거 0건 NOT_FOUND · 1건 RELATED_FOUND")
    void judge_withoutMarketIndex() {
        StockEventAnalysis empty = newAnalysis();
        service.collect(empty.getId());

        StockEventAnalysis savedEmpty = reload(empty);
        assertThat(savedEmpty.getMarketIndexCode()).isNull();
        assertThat(savedEmpty.getMarketChangeRate()).isNull();
        assertThat(savedEmpty.getExcessRate()).isNull();
        assertThat(savedEmpty.getEvidenceStatus()).isEqualTo(EvidenceStatus.NOT_FOUND);

        StockEventAnalysis found = newAnalysis();
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-1"));
        service.collect(found.getId());

        StockEventAnalysis savedFound = reload(found);
        assertThat(savedFound.getExcessRate()).isNull();
        assertThat(savedFound.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
    }

    @Test
    @DisplayName("13. 판정 — 시장 구분은 있는데 그날 지수 행이 없으면 NULL · NOT_FOUND (0으로 채우지 않음)")
    void judge_marketRowMissing() {
        StockEventAnalysis analysis = newAnalysis(MISSING_MARKET_DATE, "-5.5");
        market(analysis, "KSQ");

        service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(saved.getMarketIndexCode()).isEqualTo("KOSDAQ");
        assertThat(saved.getMarketChangeRate()).isNull();
        assertThat(saved.getExcessRate()).isNull();
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("14. 판정 — 거시지표(MACRO)만 저장되면 근거 0건으로 판정하되 evidence_count는 저장 행 수 그대로, 공시가 없어 요약 생략 (Q-3)")
    void judge_macroOnly() {
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        market(analysis, "STK");
        index(IndicatorCode.KOSPI, MARKET_DATE, "-4.0000");
        macro.behavior = t -> List.of(evidence(SourceType.MACRO, "macro-1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(result.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(saved.getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(saved.getEvidenceCount()).isEqualTo(1);
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("19. 요약 생략(Q-3) — 공시 0건 · 뉴스 2건이면 RELATED_FOUND + SUMMARY_SKIPPED, 근거 수 · 초과 변동은 그대로 저장")
    void newsOnly_summarySkipped() {
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        market(analysis, "STK");
        index(IndicatorCode.KOSPI, MARKET_DATE, "-4.0000");
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"), evidence(SourceType.NEWS, "n-2"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(result.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(saved.getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
        assertThat(saved.getEvidenceCount()).isEqualTo(2);
        assertThat(saved.getExcessRate()).isEqualByComparingTo("-1.5000");
        assertThat(saved.getAiSummary()).isNull();
    }

    @Test
    @DisplayName("20. 요약 대기 — 공시 1건 + 뉴스 1건이면 RELATED_FOUND + COLLECTING 유지 (LLM 단계 대상)")
    void disclosureAndNews_staysCollecting() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-1"));
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(reload(analysis).getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
    }

    @Test
    @DisplayName("21. 근거 0건은 요약 생략 규칙을 타지 않는다 — NO_EVIDENCE 그대로")
    void noEvidence_unchanged() {
        StockEventAnalysis analysis = newAnalysis();

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.status()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(analysis).getEvidenceStatus()).isEqualTo(EvidenceStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("22. 요약 생략 — 값이 잘못된 공시(저장 전에 제외)만 있고 뉴스 1건이면 저장된 공시가 없어 SUMMARY_SKIPPED")
    void invalidDisclosureOnly_summarySkipped() {
        StockEventAnalysis analysis = newAnalysis();
        disclosure.behavior = t -> List.of(
                new CollectedEvidence(SourceType.DISCLOSURE, "url 없음", "DART", null, " ", null, null, "no-url"));
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-1"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.excludedCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(externalIds(analysis)).containsExactly("n-1");
    }

    @Test
    @DisplayName("15. 시장 구분 — 코스닥(KSQ) 종목은 KOSDAQ 지수와 비교한다 (같은 날 KOSPI 값을 쓰지 않는다)")
    void kosdaqComparedWithKosdaq() {
        StockEventAnalysis analysis = newAnalysis(KOSDAQ_DATE, "-5.5");
        market(analysis, "KSQ");
        index(IndicatorCode.KOSDAQ, KOSDAQ_DATE, "-4.0000");
        index(IndicatorCode.KOSPI, KOSDAQ_DATE, "-0.5000"); // KOSPI와 비교하면 excess −5.0 → NOT_FOUND가 된다

        service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(saved.getMarketIndexCode()).isEqualTo("KOSDAQ");
        assertThat(saved.getMarketChangeRate()).isEqualByComparingTo("-4.0000");
        assertThat(saved.getExcessRate()).isEqualByComparingTo("-1.5000");
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("16. 시장 구분 — 종목정보 조회가 실패해도 수집은 계속: 근거는 저장, 시장 · 초과 변동 NULL")
    void marketLookupFailure_collectionContinues() {
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        when(kisApiClient.getStockInfo(analysis.getTicker()))
                .thenThrow(new KisApiException(KisErrorType.API_ERROR, null));
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-1"));
        StockEventAnalysis empty = newAnalysis(MARKET_DATE, "-5.5");
        when(kisApiClient.getStockInfo(empty.getTicker()))
                .thenThrow(new KisApiException(KisErrorType.API_ERROR, null));

        EvidenceCollectResult result = service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(saved.getEvidenceCount()).isEqualTo(1);
        assertThat(saved.getMarketIndexCode()).isNull();
        assertThat(saved.getMarketChangeRate()).isNull();
        assertThat(saved.getExcessRate()).isNull();
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);

        disclosure.reset();
        service.collect(empty.getId());
        StockEventAnalysis savedEmpty = reload(empty);
        assertThat(savedEmpty.getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(savedEmpty.getEvidenceStatus()).isEqualTo(EvidenceStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("17. 시장 구분 — 이미 값이 있는 분석은 종목정보를 부르지 않고 덮어쓰지 않는다")
    void marketAlreadySet_noLookup() {
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        // 이전 실행 등으로 값이 이미 있는 상태를 만든다
        jdbcTemplate.update("UPDATE stock_event_analyses SET market_index_code = 'KOSPI' WHERE id = ?", analysis.getId());
        market(analysis, "KSQ");
        index(IndicatorCode.KOSPI, MARKET_DATE, "-4.0000");

        service.collect(analysis.getId());

        verify(kisApiClient, never()).getStockInfo(analysis.getTicker());
        assertThat(reload(analysis).getMarketIndexCode()).isEqualTo("KOSPI");
        assertThat(reload(analysis).getEvidenceStatus()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("18. 시장 구분 — ETF도 상장 시장(STK)의 지수로 비교한다: 공시 · 뉴스 없이 MARKET_MOVE")
    void etfComparedWithListedMarket() {
        StockEventAnalysis analysis = newAnalysis(MARKET_DATE, "-5.5");
        holding(analysis.getTicker(), SecurityType.ETF);
        market(analysis, "STK");
        index(IndicatorCode.KOSPI, MARKET_DATE, "-4.0000");

        service.collect(analysis.getId());

        StockEventAnalysis saved = reload(analysis);
        assertThat(disclosure.calls.get()).isZero();
        assertThat(news.calls.get()).isZero();
        assertThat(saved.getMarketIndexCode()).isEqualTo("KOSPI");
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.MARKET_MOVE);
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
        return newAnalysis(DATE, "-6.1");
    }

    private StockEventAnalysis newAnalysis(LocalDate date, String changeRate) {
        return analysisRepository.saveAndFlush(
                StockEventAnalysis.create(uniqueTicker(), "테스트 종목", date, new BigDecimal(changeRate)));
    }

    /** 한투 종목정보 목 — 이 종목의 mket_id_cd (테스트 전용 더미 종목정보) */
    private void market(StockEventAnalysis analysis, String marketIdCode) {
        when(kisApiClient.getStockInfo(analysis.getTicker())).thenReturn(new KisStockInfo(analysis.getTicker(), "테스트 종목",
                "ST", "0", null, "N", "N", null, marketIdCode));
    }

    private void index(IndicatorCode code, LocalDate date, String changeRate) {
        macroIndicatorWriter.upsertAll(List.of(new MacroIndicatorRow(code, date, new BigDecimal("1000.0000"), new BigDecimal(changeRate))));
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
