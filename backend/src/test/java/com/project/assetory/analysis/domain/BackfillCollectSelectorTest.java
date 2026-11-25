package com.project.assetory.analysis.domain;

import com.project.assetory.analysis.domain.BackfillCollectSelector.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 소급 직후 근거 수집 대상 (Q-4) — 날짜 < 오늘 · PENDING만, |변동률| 큰 순 → 날짜 최신 → id, 상한까지
class BackfillCollectSelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static Candidate pending(long id, LocalDate date, String changeRate) {
        return new Candidate(id, date, new BigDecimal(changeRate), AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("오늘 날짜 분석은 제외 — 16:00 배치가 실시간 분석과 함께 처리한다")
    void excludesToday() {
        List<Long> ids = BackfillCollectSelector.select(List.of(
                pending(1, TODAY, "9.0"), pending(2, TODAY.minusDays(1), "6.0")), TODAY, 50);

        assertThat(ids).containsExactly(2L);
    }

    @Test
    @DisplayName("오늘 이후 날짜(시계 어긋남 등)도 제외")
    void excludesFuture() {
        assertThat(BackfillCollectSelector.select(List.of(pending(1, TODAY.plusDays(1), "9.0")), TODAY, 50)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}이면 제외")
    @DisplayName("PENDING이 아닌 분석은 제외 — 이미 수집 · 판정됨")
    @EnumSource(value = AnalysisStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void excludesNonPending(AnalysisStatus status) {
        Candidate done = new Candidate(1L, TODAY.minusDays(3), new BigDecimal("9.0"), status);

        assertThat(BackfillCollectSelector.select(List.of(done), TODAY, 50)).isEmpty();
    }

    @Test
    @DisplayName("상승 · 하락이 섞여도 |변동률| 큰 순")
    void ordersByAbsoluteChange() {
        List<Long> ids = BackfillCollectSelector.select(List.of(
                pending(1, TODAY.minusDays(2), "5.5"),
                pending(2, TODAY.minusDays(3), "-9.1"),
                pending(3, TODAY.minusDays(4), "7.0"),
                pending(4, TODAY.minusDays(5), "-6.2")), TODAY, 50);

        assertThat(ids).containsExactly(2L, 3L, 4L, 1L);
    }

    @Test
    @DisplayName("|변동률|이 같으면 날짜 최신 → id 오름차순 (결정적)")
    void tieBreak() {
        List<Long> ids = BackfillCollectSelector.select(List.of(
                pending(7, TODAY.minusDays(5), "6.0"),
                pending(3, TODAY.minusDays(2), "-6.0"),
                pending(5, TODAY.minusDays(2), "6.0000")), TODAY, 50);

        assertThat(ids).containsExactly(3L, 5L, 7L);
    }

    @Test
    @DisplayName("상한 경계 — 정확히 N건이면 전부, N+1건이면 |변동률|이 가장 작은 1건이 빠진다")
    void limit() {
        List<Candidate> three = new ArrayList<>(List.of(
                pending(1, TODAY.minusDays(1), "6.0"), pending(2, TODAY.minusDays(2), "7.0"), pending(3, TODAY.minusDays(3), "8.0")));

        assertThat(BackfillCollectSelector.select(three, TODAY, 3)).containsExactly(3L, 2L, 1L);
        three.add(pending(4, TODAY.minusDays(4), "5.0"));
        assertThat(BackfillCollectSelector.select(three, TODAY, 3)).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("같은 분석이 두 번 들어와도 1번만 (여러 종목 · 계좌가 같은 분석을 가리킬 수 있다)")
    void deduplicates() {
        Candidate c = pending(1, TODAY.minusDays(1), "6.0");

        assertThat(BackfillCollectSelector.select(List.of(c, c), TODAY, 50)).containsExactly(1L);
    }

    @Test
    @DisplayName("빈 목록 · null은 빈 결과, 상한 1 미만 · 오늘 null은 호출부 버그 — 예외")
    void guards() {
        assertThat(BackfillCollectSelector.select(List.of(), TODAY, 50)).isEmpty();
        assertThat(BackfillCollectSelector.select(null, TODAY, 50)).isEmpty();
        assertThatThrownBy(() -> BackfillCollectSelector.select(List.of(), TODAY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillCollectSelector.select(List.of(), null, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
