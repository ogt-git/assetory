package com.project.assetory.analysis;

import com.project.assetory.analysis.EvidenceCollectServiceIntegrationTest.FakeCollector;
import com.project.assetory.analysis.dart.DartDisclosureCollector;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.evidence.EvidenceTarget;
import com.project.assetory.analysis.exception.EvidenceCollectException;
import com.project.assetory.analysis.naver.NaverNewsCollector;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.analysis.service.EvidenceRetryService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * PENDING 분석 재시도 1회 실행 (Q-6, ANL-7) — 기존 수집 경로(락 · 판정 · Q-3 · Q-4 · D2)를 그대로 탄다.
 * "지금"을 2046-03-10 12:00(KST)로 고정하고 이 테스트 분석의 created_at을 그 근처로 둔다 — 다른 테스트의 PENDING 행(현재 시각)은
 * 창(24시간) 밖이라 섞이지 않는다. 끝나면 이 테스트 행을 지운다. 가짜 수집기 3개는 EvidenceCollectServiceIntegrationTest 것을 쓴다
 */
@Import(EvidenceCollectServiceIntegrationTest.FakeCollectors.class)
class EvidenceRetryServiceIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2046, 3, 10, 12, 0);
    private static final LocalDate EVENT_DATE = LocalDate.of(2046, 3, 9);

    @TestBean(name = "clock")
    Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
    }

    @Autowired EvidenceRetryService retryService;
    @MockitoSpyBean EvidenceCollectService evidenceCollectService;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired EventSourceRepository eventSourceRepository;
    @Autowired UserMarketEventRepository userMarketEventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meterRegistry;
    // 운영 수집기는 뺀다(목의 supports()는 false). 시장 구분 조회는 null 응답 → 시장 구분 없음
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
    void cleanUp() {
        jdbc.update("DELETE FROM event_sources WHERE analysis_id IN (SELECT id FROM stock_event_analyses WHERE created_at >= ?)",
                LocalDateTime.of(2046, 1, 1, 0, 0));
        jdbc.update("DELETE FROM user_market_events WHERE analysis_id IN (SELECT id FROM stock_event_analyses WHERE created_at >= ?)",
                LocalDateTime.of(2046, 1, 1, 0, 0));
        jdbc.update("DELETE FROM stock_event_analyses WHERE created_at >= ?", LocalDateTime.of(2046, 1, 1, 0, 0));
        Set<String> keys = redis.keys("lock:analysis:Q*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @DisplayName("1. 수집기가 모두 실패해 PENDING인 분석 → 다음 실행에서 성공하면 기대 상태(공시 있음 → COLLECTING)")
    void allFailed_thenRetrySucceeds() {
        StockEventAnalysis analysis = pending("6.5", NOW.minusHours(2));
        Function<EvidenceTarget, List<CollectedEvidence>> fail = t -> {
            throw new IllegalStateException("테스트 — 예상 밖 오류");
        };
        disclosure.behavior = fail;
        news.behavior = fail;
        macro.behavior = fail;
        EvidenceRetryService.Result first = retryService.run();
        assertThat(first.stillPending()).isEqualTo(1);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);

        resetFakes();
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));
        EvidenceRetryService.Result second = retryService.run();

        assertThat(second.targets()).isEqualTo(1);
        assertThat(second.completed()).isEqualTo(1);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("2. D2: 공시 실패 + 뉴스 성공 → PENDING(뉴스 저장) → 재시도에서 공시 성공 → COLLECTING, 행 중복 없음 · 건수 = 실제 행 수")
    void disclosureRetry_thenCollecting() {
        StockEventAnalysis analysis = pending("-7.0", NOW.minusHours(1));
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-" + t.ticker()));
        retryService.run();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);

        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));
        retryService.run();

        StockEventAnalysis saved = reload(analysis);
        assertThat(saved.getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isEqualTo(2);
        assertThat(saved.getEvidenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("3. D2: 재시도에서 공시 0건 성공이면 뉴스만이라 SUMMARY_SKIPPED")
    void disclosureRetry_thenSummarySkipped() {
        StockEventAnalysis analysis = pending("5.5", NOW.minusHours(1));
        disclosure.behavior = t -> {
            throw new EvidenceCollectException(SourceType.DISCLOSURE, "테스트 — 외부 오류");
        };
        news.behavior = t -> List.of(evidence(SourceType.NEWS, "n-" + t.ticker()));
        retryService.run();

        disclosure.behavior = t -> List.of();
        retryService.run();

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 락 사용 중이면 건너뛴다 — 상태 변화 없음, 수집기 호출 0회")
    void lockHeld_skipped() {
        StockEventAnalysis analysis = pending("6.0", NOW.minusHours(1));
        redis.opsForValue().set("lock:analysis:" + analysis.getTicker() + ":" + analysis.getEventDate(), "other-owner");

        EvidenceRetryService.Result result = retryService.run();

        assertThat(result.skippedLocked()).isEqualTo(1);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(disclosure.calls.get() + news.calls.get() + macro.calls.get()).isZero();
    }

    @Test
    @DisplayName("5. 1건이 예외를 던져도 나머지는 진행한다")
    void oneException_othersProceed() {
        StockEventAnalysis failing = pending("9.0", NOW.minusHours(1));
        StockEventAnalysis ok = pending("6.0", NOW.minusHours(1));
        doThrow(new IllegalStateException("테스트 — 수집 중 예상 밖 오류")).when(evidenceCollectService).collect(failing.getId());

        EvidenceRetryService.Result result = retryService.run();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(reload(ok).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(failing).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("6. 창(24시간)이 지난 PENDING은 대상이 아니다 — 외부 호출 0회, 그대로 PENDING")
    void outsideWindow_notTargeted() {
        StockEventAnalysis old = pending("9.0", NOW.minusHours(24).minusSeconds(1));

        EvidenceRetryService.Result result = retryService.run();

        assertThat(result.targets()).isZero();
        assertThat(disclosure.calls.get() + news.calls.get() + macro.calls.get()).isZero();
        assertThat(reload(old).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("7. 소급 이벤트만 붙은 과거 날짜 분석도 재시도에서 같은 규칙 — 공시가 있어도 SUMMARY_SKIPPED (Q-4)")
    void backfillOnly_sameRule() {
        StockEventAnalysis analysis = pending("6.5", NOW.minusHours(1));
        userMarketEventRepository.saveAndFlush(UserMarketEvent.create(newUser(), analysis, Direction.UP, new BigDecimal("5"),
                null, null, null, true));
        disclosure.behavior = t -> List.of(evidence(SourceType.DISCLOSURE, "d-" + t.ticker()));

        retryService.run();

        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
    }

    @Test
    @DisplayName("8. 실행 시간은 batch.step.duration{step=analyze-retry}로 남긴다 (05 §12 이름 재사용)")
    void metric() {
        pending("6.0", NOW.minusHours(1));

        retryService.run();

        assertThat(meterRegistry.find("batch.step.duration").tag("step", "analyze-retry").timer()).isNotNull();
    }

    // ───────── 보조 ─────────

    /** 테스트 전용 PENDING 분석 — 종목코드는 매번 새로, created_at은 JDBC로 고정 시각 근처에 둔다 */
    private StockEventAnalysis pending(String changeRate, LocalDateTime createdAt) {
        String ticker = "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        StockEventAnalysis saved = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(ticker, "테스트 종목", EVENT_DATE, new BigDecimal(changeRate)));
        jdbc.update("UPDATE stock_event_analyses SET created_at = ? WHERE id = ?", createdAt, saved.getId());
        return saved;
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
                type == SourceType.DISCLOSURE ? null : NOW.minusDays(1),
                "https://example.com/" + Math.abs(externalId.hashCode()), null, null, externalId);
    }
}
