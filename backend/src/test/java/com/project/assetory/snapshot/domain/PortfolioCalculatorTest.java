package com.project.assetory.snapshot.domain;

import com.project.assetory.snapshot.domain.PortfolioCalculator.HoldingInput;
import com.project.assetory.snapshot.domain.PortfolioCalculator.Profit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

// 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04, 05 §1.3) — 스프링 없는 순수 계산
class PortfolioCalculatorTest {

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    // evalAmount = 기록 평가금액, quantity = 기록 수량, avgBuyPrice = 현재 holdings 평균 매입가(매칭 없으면 null)
    private static Profit profit(String evalAmount, String quantity, String avgBuyPrice) {
        return PortfolioCalculator.profit(new HoldingInput(d(evalAmount), d(quantity), d(avgBuyPrice)));
    }

    @Nested
    @DisplayName("원금 · 손익 · 손익률 — 원금 = 기록 수량 × 평균 매입가(HALF_UP 2자리), 손익률 퍼센트 HALF_UP 4자리")
    class Profits {

        @Test
        @DisplayName("1. 원금 · 손익 · 손익률")
        void basic() {
            Profit p = profit("712000", "10", "65000");

            assertThat(p.principal()).isEqualByComparingTo("650000");
            assertThat(p.profitLoss()).isEqualByComparingTo("62000");
            assertThat(p.profitLossRate()).isEqualByComparingTo("9.5385"); // 62,000 / 650,000 = 9.538461…%
        }

        @Test
        @DisplayName("1-1. 원금 반올림 — 수량 × 평균 매입가를 HALF_UP 2자리 (0.3333 × 1000.55 = 333.483315 → 333.48)")
        void principalRounding() {
            Profit p = profit("333.50", "0.3333", "1000.55");

            assertThat(p.principal()).isEqualByComparingTo("333.48");
            assertThat(p.profitLoss()).isEqualByComparingTo("0.02");
        }

        @Test
        @DisplayName("1-2. 손익률 4자리 HALF_UP 경계 — 0.00005% 올림, 0.0000499990% 내림")
        void rateBoundary() {
            assertThat(profit("2000001", "20000", "100").profitLossRate()).isEqualByComparingTo("0.0001");   // 1 / 2,000,000
            assertThat(profit("2000041", "1", "2000040").profitLossRate()).isEqualByComparingTo("0.0000");   // 1 / 2,000,040
        }

        @Test
        @DisplayName("6. 손실(음수) — 손익 음수, 손익률 HALF_UP은 0에서 먼 쪽 (−0.00005% → −0.0001)")
        void loss() {
            Profit small = profit("1999999", "20000", "100");
            Profit big = profit("600000", "10", "65000");

            assertThat(small.profitLoss()).isEqualByComparingTo("-1");
            assertThat(small.profitLossRate()).isEqualByComparingTo("-0.0001");
            assertThat(big.profitLoss()).isEqualByComparingTo("-50000");
            assertThat(big.profitLossRate()).isEqualByComparingTo("-7.6923"); // −50,000 / 650,000 = −7.692307…%
        }

        @Test
        @DisplayName("2. 평균 매입가 NULL · 매칭 holdings 없음(입력 null) · 기록 수량 NULL(전환기) → 원금 · 손익 · 손익률 NULL (0 채움 금지)")
        void missingInputs_null() {
            for (Profit p : List.of(
                    profit("712000", "10", null),       // 평균 매입가 없음 또는 그 뒤 전량 매도로 holdings에 없음
                    profit("712000", null, "65000"))) { // B2 이전 기록 — 수량 없음
                assertThat(p.principal()).isNull();
                assertThat(p.profitLoss()).isNull();
                assertThat(p.profitLossRate()).isNull();
            }
            assertThat(PortfolioCalculator.profit(new HoldingInput(d("712000"), d("10"), null))).isEqualTo(Profit.NONE);
        }

        @Test
        @DisplayName("3. 원금 0(평균 매입가 0) → 손익 = 평가금액, 손익률만 NULL")
        void zeroPrincipal() {
            Profit p = profit("712000", "10", "0");

            assertThat(p.principal()).isEqualByComparingTo("0");
            assertThat(p.profitLoss()).isEqualByComparingTo("712000");
            assertThat(p.profitLossRate()).isNull();
        }
    }

    @Nested
    @DisplayName("비중 — 금액 ÷ 그날 총자산 × 100, HALF_UP 2자리 (자산군 · 종목 같은 분모)")
    class Ratios {

        @Test
        @DisplayName("4. 비중 2자리 HALF_UP — 0.005% 올림, 1/3 → 33.33, 2/3 → 66.67")
        void rounding() {
            assertThat(PortfolioCalculator.ratio(d("1"), d("20000"))).isEqualByComparingTo("0.01");  // 0.005%
            assertThat(PortfolioCalculator.ratio(d("100"), d("300"))).isEqualByComparingTo("33.33");
            assertThat(PortfolioCalculator.ratio(d("200"), d("300"))).isEqualByComparingTo("66.67");
            assertThat(PortfolioCalculator.ratio(d("712000"), d("3100000"))).isEqualByComparingTo("22.97"); // 22.967…
        }

        @Test
        @DisplayName("4-1. 총자산 0 · 총자산 NULL · 금액 NULL → NULL")
        void nullCases() {
            assertThat(PortfolioCalculator.ratio(d("100"), d("0"))).isNull();
            assertThat(PortfolioCalculator.ratio(d("100"), null)).isNull();
            assertThat(PortfolioCalculator.ratio(null, d("300"))).isNull();
        }

        @Test
        @DisplayName("5. 자산군 비중 합은 보정하지 않는다 — 100 · 100 · 100 / 300 → 33.33 × 3 = 99.99")
        void noSumCorrection() {
            BigDecimal total = d("300");
            BigDecimal sum = List.of(d("100"), d("100"), d("100")).stream()
                    .map(amount -> PortfolioCalculator.ratio(amount, total))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(sum).isEqualByComparingTo("99.99");
        }
    }
}
