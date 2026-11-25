package com.project.assetory.market.domain;

import com.project.assetory.market.exception.IndicatorSeriesException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 지표 응답 → 저장 행 (B4 확정 사항 3 · 4 · 5): 오름차순 정렬 → 같은 날짜 중복이면 실패 → 직전 행 대비 직접 계산 → 가장 오래된 날짜 제외
class IndicatorSeriesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 30);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 11, 3);
    private static final LocalDate D4 = LocalDate.of(2026, 11, 4);

    @Test
    @DisplayName("오름차순 · 최신순 입력이 같은 결과 — 가장 오래된 날짜만 빠지고 최신일은 남는다")
    void orderIndependent() {
        List<DatedValue> asc = List.of(v(D1, "2500.00"), v(D2, "2525.00"), v(D3, "2500.00"), v(D4, "2550.50"));
        List<DatedValue> desc = List.of(v(D4, "2550.50"), v(D3, "2500.00"), v(D2, "2525.00"), v(D1, "2500.00"));

        List<MacroIndicatorRow> fromAsc = IndicatorSeries.toRows(IndicatorCode.KOSPI, asc, true);
        List<MacroIndicatorRow> fromDesc = IndicatorSeries.toRows(IndicatorCode.KOSPI, desc, true);

        assertThat(fromAsc).isEqualTo(fromDesc);
        assertThat(fromAsc).extracting(MacroIndicatorRow::baseDate).containsExactly(D2, D3, D4);
    }

    @Test
    @DisplayName("change_rate = (값 − 직전 값) ÷ 직전 값 × 100, HALF_UP 4자리")
    void changeRate_computed() {
        List<MacroIndicatorRow> rows = IndicatorSeries.toRows(IndicatorCode.KOSPI,
                List.of(v(D1, "2500.00"), v(D2, "2525.00"), v(D3, "2500.00"), v(D4, "2550.50")), true);

        assertThat(rows.get(0).changeRate()).isEqualByComparingTo("1.0000");   // 25 ÷ 2500
        assertThat(rows.get(1).changeRate()).isEqualByComparingTo("-0.9901"); // −25 ÷ 2525 = −0.990099…
        assertThat(rows.get(2).changeRate()).isEqualByComparingTo("2.0200");   // 50.5 ÷ 2500
        assertThat(rows.get(1).changeRate().scale()).isEqualTo(4);
        assertThat(rows.get(2).value()).isEqualByComparingTo("2550.50");
    }

    @Test
    @DisplayName("HALF_UP 경계 — 0.00005는 올림")
    void halfUp() {
        // 10000 → 10000.005 = 0.00005% → 0.0001
        List<MacroIndicatorRow> rows = IndicatorSeries.toRows(IndicatorCode.USD_KRW,
                List.of(v(D1, "10000"), v(D2, "10000.005")), true);

        assertThat(rows.get(0).changeRate()).isEqualByComparingTo("0.0001");
    }

    @Test
    @DisplayName("변동률을 계산하지 않는 지표(BASE_RATE)는 NULL — 가장 오래된 날짜는 똑같이 빠진다")
    void noChangeRate() {
        List<MacroIndicatorRow> rows = IndicatorSeries.toRows(IndicatorCode.BASE_RATE,
                List.of(v(D1, "2.50"), v(D2, "2.50"), v(D3, "2.25")), false);

        assertThat(rows).extracting(MacroIndicatorRow::baseDate).containsExactly(D2, D3);
        assertThat(rows).extracting(MacroIndicatorRow::changeRate).containsOnlyNulls();
        assertThat(rows.get(1).value()).isEqualByComparingTo("2.25");
    }

    @Test
    @DisplayName("같은 날짜 중복 → 그 지표 실패 (어느 값을 믿을지 정하지 않는다)")
    void duplicateDate_fails() {
        assertThatThrownBy(() -> IndicatorSeries.toRows(IndicatorCode.KOSPI,
                List.of(v(D2, "2525.00"), v(D2, "2524.00"), v(D1, "2500.00")), true))
                .isInstanceOf(IndicatorSeriesException.class);
    }

    @Test
    @DisplayName("받은 날짜만 — 빠진 날짜(D3)를 0 · 직전 값 · 임의 날짜로 채우지 않는다, 계산은 받은 직전 행 기준")
    void gap_notFilled() {
        List<MacroIndicatorRow> rows = IndicatorSeries.toRows(IndicatorCode.KOSPI,
                List.of(v(D1, "2500.00"), v(D2, "2525.00"), v(D4, "2550.50")), true);

        assertThat(rows).extracting(MacroIndicatorRow::baseDate).containsExactly(D2, D4);
        assertThat(rows.get(1).changeRate()).isEqualByComparingTo("1.0099"); // 25.5 ÷ 2525 = 1.009900…
    }

    @Test
    @DisplayName("행이 1개 이하면 저장할 행이 없다(계산 불가) · 빈 입력은 빈 결과")
    void tooFew() {
        assertThat(IndicatorSeries.toRows(IndicatorCode.KOSPI, List.of(v(D1, "2500.00")), true)).isEmpty();
        assertThat(IndicatorSeries.toRows(IndicatorCode.KOSPI, List.of(), true)).isEmpty();
    }

    @Test
    @DisplayName("직전 값이 0이면 계산할 수 없어 그 지표 실패 · 값이 null이면 실패")
    void invalidValues_fail() {
        assertThatThrownBy(() -> IndicatorSeries.toRows(IndicatorCode.KOSPI, List.of(v(D1, "0"), v(D2, "10")), true))
                .isInstanceOf(IndicatorSeriesException.class);
        assertThatThrownBy(() -> IndicatorSeries.toRows(IndicatorCode.KOSPI,
                List.of(new DatedValue(D1, null), v(D2, "10")), true))
                .isInstanceOf(IndicatorSeriesException.class);
    }

    private static DatedValue v(LocalDate date, String value) {
        return new DatedValue(date, new BigDecimal(value));
    }
}
