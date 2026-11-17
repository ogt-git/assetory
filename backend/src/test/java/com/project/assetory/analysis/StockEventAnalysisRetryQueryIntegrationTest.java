package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도 대상 조회 (Q-6, ANL-7) — PENDING · created_at ≥ 기준, |변동률| 큰 순 → created_at → id, 상한.
 * 다른 테스트의 PENDING 행과 섞이지 않게 created_at을 먼 미래(2045년)로 두고, 끝나면 이 테스트 행을 지운다
 */
class StockEventAnalysisRetryQueryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDateTime SINCE = LocalDateTime.of(2045, 1, 10, 12, 0);
    private static final String PREFIX = "R";

    @Autowired StockEventAnalysisRepository repository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM stock_event_analyses WHERE created_at >= ?", LocalDateTime.of(2045, 1, 1, 0, 0));
    }

    private List<Long> targets(int limit) {
        return repository.findRetryTargets(AnalysisStatus.PENDING, SINCE, Limit.of(limit)).stream()
                .map(StockEventAnalysis::getId).toList();
    }

    @Test
    @DisplayName("창 경계 — 기준 시각과 같으면 포함, 1초라도 이전이면 제외 (created_at ≥ 기준)")
    void windowBoundary() {
        StockEventAnalysis atBoundary = analysis("6.0", SINCE, AnalysisStatus.PENDING);
        StockEventAnalysis justBefore = analysis("9.0", SINCE.minusSeconds(1), AnalysisStatus.PENDING);
        StockEventAnalysis inside = analysis("5.5", SINCE.plusHours(3), AnalysisStatus.PENDING);

        assertThat(targets(50)).contains(atBoundary.getId(), inside.getId()).doesNotContain(justBefore.getId());
    }

    @ParameterizedTest(name = "[{index}] {0} 제외")
    @DisplayName("PENDING이 아닌 상태는 대상이 아니다")
    @EnumSource(value = AnalysisStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void excludesNonPending(AnalysisStatus status) {
        StockEventAnalysis done = analysis("9.0", SINCE.plusMinutes(1), status);

        assertThat(targets(50)).doesNotContain(done.getId());
    }

    @Test
    @DisplayName("상승 · 하락이 섞여도 |변동률| 큰 순 — 같으면 created_at 오래된 순 → id")
    void orderByAbsoluteChange() {
        StockEventAnalysis a = analysis("5.5", SINCE.plusMinutes(1), AnalysisStatus.PENDING);
        StockEventAnalysis b = analysis("-9.1", SINCE.plusMinutes(2), AnalysisStatus.PENDING);
        StockEventAnalysis c = analysis("7.0", SINCE.plusMinutes(3), AnalysisStatus.PENDING);
        StockEventAnalysis tieLater = analysis("-6.0", SINCE.plusMinutes(5), AnalysisStatus.PENDING);
        StockEventAnalysis tieEarlier = analysis("6.0", SINCE.plusMinutes(4), AnalysisStatus.PENDING);

        assertThat(targets(50)).containsExactly(b.getId(), c.getId(), tieEarlier.getId(), tieLater.getId(), a.getId());
    }

    @Test
    @DisplayName("상한 — N건이면 전부, N+1건이면 |변동률|이 가장 작은 1건이 빠진다")
    void limit() {
        StockEventAnalysis a = analysis("6.0", SINCE.plusMinutes(1), AnalysisStatus.PENDING);
        StockEventAnalysis b = analysis("7.0", SINCE.plusMinutes(2), AnalysisStatus.PENDING);
        StockEventAnalysis c = analysis("8.0", SINCE.plusMinutes(3), AnalysisStatus.PENDING);

        assertThat(targets(3)).containsExactly(c.getId(), b.getId(), a.getId());
        StockEventAnalysis smallest = analysis("5.0", SINCE.plusMinutes(4), AnalysisStatus.PENDING);
        assertThat(targets(3)).containsExactly(c.getId(), b.getId(), a.getId()).doesNotContain(smallest.getId());
    }

    /** 테스트 전용 분석 — 종목코드는 매번 새로, 상태 · created_at은 JDBC로 직접 맞춘다 */
    private StockEventAnalysis analysis(String changeRate, LocalDateTime createdAt, AnalysisStatus status) {
        String ticker = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        StockEventAnalysis saved = repository.saveAndFlush(
                StockEventAnalysis.create(ticker, "테스트 종목", LocalDate.of(2026, 11, 5), new BigDecimal(changeRate)));
        jdbc.update("UPDATE stock_event_analyses SET created_at = ?, analysis_status = ? WHERE id = ?",
                createdAt, status.name(), saved.getId());
        return saved;
    }
}
