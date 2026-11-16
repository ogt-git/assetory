package com.project.assetory.analysis;

import com.project.assetory.analysis.EvidenceCollectServiceIntegrationTest.FakeCollector;
import com.project.assetory.analysis.dart.DartDisclosureCollector;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.EvidenceStatus;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.naver.NaverNewsCollector;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.analysis.service.BackfillEvidenceService;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * 연동 직후 소급 분석의 근거 수집 (Q-4) — 날짜 < 오늘 · PENDING만, |변동률| 큰 순 · 상한(이 테스트는 3건),
 * 기존 수집 경로(락 · 판정 · Q-3) 그대로, 소급 이벤트만 붙은 분석은 공시가 있어도 SUMMARY_SKIPPED(LLM 미호출).
 * 가짜 수집기 3개(공시 · 뉴스 · 거시지표)는 EvidenceCollectServiceIntegrationTest 것을 쓴다. "오늘"은 운영 Clock(Asia/Seoul) 기준 상대 날짜
 */
@Import(EvidenceCollectServiceIntegrationTest.FakeCollectors.class)
@TestPropertySource(properties = "assetory.analysis.backfill-collect-max=3")
class BackfillEvidenceCollectIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired BackfillEvidenceService backfillEvidenceService;
    @MockitoSpyBean EvidenceCollectService evidenceCollectService;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserMarketEventRepository userMarketEventRepository;
    @Autowired EventSourceRepository eventSourceRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;
    // 운영 수집기는 뺀다(목의 supports()는 false) — 가짜 수집기만 돈다. 시장 구분 조회는 null 응답 → 시장 구분 없음
    @MockitoBean DartDisclosureCollector dartDisclosureCollector;
    @MockitoBean NaverNewsCollector naverNewsCollector;
    @MockitoBean KisApiClient kisApiClient;

    @Autowired @Qualifier("disclosureFake") FakeCollector disclosure;
    @Autowired @Qualifier("newsFake") FakeCollector news;
    @Autowired @Qualifier("macroFake") FakeCollector macro;

    @BeforeEach
    void resetFakes() {
        List.of(disclosure, news, macro).forEach(FakeCollector::reset);
    }

    @AfterEach
    void cleanLocks() {
        Set<String> keys = redis.keys("lock:analysis:B*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @DisplayName("1. 소급만 + 공시 있음(과거) → RELATED_FOUND + SUMMARY_SKIPPED, 요약 NULL (LLM 미호출)")
    void backfillOnly_disclosure_skipped() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(3), "6.5");
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));

        backfillEvidenceService.collect(List.of(analysis.getId()));

        StockEventAnalysis saved = reload(analysis);
        assertThat(saved.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
        assertThat(saved.getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(saved.getAiSummary()).isNull();
    }

    @Test
    @DisplayName("2. 소급만 + 뉴스만(과거) → SUMMARY_SKIPPED (Q-3 경로, 생략을 두 번 부르지 않는다)")
    void backfillOnly_newsOnly_skipped() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(4), "-6.0");
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-" + t.ticker()));

        backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
    }

    @Test
    @DisplayName("3. 소급만 + 근거 0건 → NO_EVIDENCE")
    void backfillOnly_noEvidence() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(5), "7.0");

        backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("4. 소급 + 실시간 혼합(과거, 공시 있음) → COLLECTING 유지 (실시간 사용자는 요약 대상)")
    void mixed_staysCollecting() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(2), "6.0");
        userMarketEventRepository.saveAndFlush(UserMarketEvent.create(newUser(), analysis, Direction.UP, new BigDecimal("5"),
                new BigDecimal("1000.00"), new BigDecimal("10.00"), new BigDecimal("1.0000"), false));
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));

        backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("5. 오늘 날짜 소급 분석은 연동 때 수집하지 않는다 — PENDING 유지, 수집기 호출 0회 (16:00 배치 몫)")
    void today_notCollected() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY, "9.0");

        backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(disclosure.calls.get() + news.calls.get() + macro.calls.get()).isZero();
    }

    @Test
    @DisplayName("6. 분석 1건 수집이 예외를 던져도 나머지는 수집된다")
    void oneFailure_othersCollected() {
        StockEventAnalysis failing = backfilledAnalysis(TODAY.minusDays(2), "9.0");
        StockEventAnalysis ok = backfilledAnalysis(TODAY.minusDays(3), "6.0");
        doThrow(new IllegalStateException("테스트 — 수집 중 예상 밖 오류")).when(evidenceCollectService).collect(failing.getId());

        BackfillEvidenceService.Result result = backfillEvidenceService.collect(List.of(failing.getId(), ok.getId()));

        assertThat(reload(failing).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(reload(ok).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. 상한 경계 — 대상 4건 · 상한 3건이면 |변동률| 큰 3건만 수집, 가장 작은 1건은 PENDING")
    void limit() {
        StockEventAnalysis a = backfilledAnalysis(TODAY.minusDays(1), "5.1");
        StockEventAnalysis b = backfilledAnalysis(TODAY.minusDays(2), "-9.0");
        StockEventAnalysis c = backfilledAnalysis(TODAY.minusDays(3), "7.0");
        StockEventAnalysis d = backfilledAnalysis(TODAY.minusDays(4), "-6.0");

        BackfillEvidenceService.Result result = backfillEvidenceService.collect(List.of(a.getId(), b.getId(), c.getId(), d.getId()));

        assertThat(result.candidates()).isEqualTo(4);
        assertThat(result.selected()).isEqualTo(3);
        assertThat(reload(a).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(List.of(reload(b), reload(c), reload(d))).allSatisfy(x ->
                assertThat(x.getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE));
    }

    @Test
    @DisplayName("8. 같은 목록을 다시 처리해도 상태 · 근거 행 수가 바뀌지 않는다 (멱등, 예외 없음)")
    void idempotent() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(3), "6.5");
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));
        backfillEvidenceService.collect(List.of(analysis.getId()));
        long rows = eventSourceRepository.countByAnalysisId(analysis.getId());

        BackfillEvidenceService.Result again = backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(again.selected()).isZero(); // PENDING이 아니라 대상에서 빠진다
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isEqualTo(rows);
        assertThat(disclosure.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("9. AI 한도 · 예산 Redis 키(ai:*)를 건드리지 않는다")
    void noAiQuotaKeys() {
        StockEventAnalysis analysis = backfilledAnalysis(TODAY.minusDays(3), "6.5");
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));
        Set<String> before = redis.keys("ai:*");

        backfillEvidenceService.collect(List.of(analysis.getId()));

        assertThat(redis.keys("ai:*")).isEqualTo(before);
    }

    // ───────── 보조 ─────────

    /** 소급 사용자 이벤트(금액 NULL)만 붙은 PENDING 분석 — 종목코드는 매번 새로(테스트 전용) */
    private StockEventAnalysis backfilledAnalysis(LocalDate date, String changeRate) {
        String ticker = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        StockEventAnalysis analysis = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(ticker, "테스트 종목", date, new BigDecimal(changeRate)));
        userMarketEventRepository.saveAndFlush(UserMarketEvent.create(newUser(), analysis, Direction.of(analysis.getChangeRate()),
                new BigDecimal("5"), null, null, null, true));
        return analysis;
    }

    private StockEventAnalysis reload(StockEventAnalysis analysis) {
        return analysisRepository.findById(analysis.getId()).orElseThrow();
    }

    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }

    private static CollectedEvidence evidence(SourceType type, String externalId) {
        return new CollectedEvidence(type, "테스트 근거 " + externalId, type == SourceType.DISCLOSURE ? "DART" : "테스트 출처",
                type == SourceType.DISCLOSURE ? null : LocalDateTime.now().minusDays(1),
                "https://example.com/" + Math.abs(externalId.hashCode()), null, null, externalId);
    }
}
