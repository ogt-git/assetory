package com.project.assetory.snapshot.domain;

import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.MarketSum;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.SnapshotValues;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.StoredDayChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// 전일 · 전월 · 전년 · 누적 증감 계산 (FR-AN-01 · 02 · 03 · 06, 01 §3.1 · §3.2, 05 §4.7) — 스프링 없는 순수 계산
class AssetChangeCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 28);

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static SnapshotValues values(LocalDate date, String total, String securities, String deposit, String cash) {
        return new SnapshotValues(date, d(total), d(securities), d(deposit), d(cash));
    }

    // total = 자산군 합인 COMPLETE 기록
    private static SnapshotValues values(LocalDate date, String securities, String deposit, String cash) {
        BigDecimal total = d(securities).add(d(deposit)).add(d(cash));
        return new SnapshotValues(date, total, d(securities), d(deposit), d(cash));
    }

    @Nested
    @DisplayName("목표 날짜 — 항상 asOfDate에서 계산 (말일 보정)")
    class TargetDates {

        @Test
        @DisplayName("1. 전월: 3/31 → 2/28(평년) · 2/29(윤년), 1/15 → 전년 12/15")
        void monthTarget() {
            assertThat(AssetChangeCalculator.monthTarget(LocalDate.of(2026, 3, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(AssetChangeCalculator.monthTarget(LocalDate.of(2028, 3, 31))).isEqualTo(LocalDate.of(2028, 2, 29));
            assertThat(AssetChangeCalculator.monthTarget(LocalDate.of(2027, 1, 15))).isEqualTo(LocalDate.of(2026, 12, 15));
        }

        @Test
        @DisplayName("2. 전년: 2/29 → 전년 2/28")
        void yearTarget() {
            assertThat(AssetChangeCalculator.yearTarget(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2027, 2, 28));
            assertThat(AssetChangeCalculator.yearTarget(AS_OF)).isEqualTo(LocalDate.of(2025, 10, 28));
        }
    }

    @Nested
    @DisplayName("기간 비교 (전월 · 전년) — COMPLETE끼리, 기준 = 목표 날짜 이하 마지막 COMPLETE")
    class PeriodComparisons {

        private final SnapshotValues latest = values(AS_OF, "1200000", "2000000", "300000"); // 3,500,000

        @Test
        @DisplayName("3. 기준 행이 목표 날짜보다 앞선 COMPLETE → 그대로 비교하고 baseDate 반환 (간격 제한 없음)")
        void baseBeforeTarget_compared() {
            // 목표 9/28, 그 사이 실패가 이어져 마지막 COMPLETE는 9/10
            SnapshotValues base = values(LocalDate.of(2026, 9, 10), "1000000", "2000000", "250000"); // 3,250,000

            Comparison c = AssetChangeCalculator.compare(latest, base, new MarketSum(d("150000"), false));

            assertThat(c.available()).isTrue();
            assertThat(c.baseDate()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(c.baseTotal()).isEqualByComparingTo("3250000");
            assertThat(c.changeAmount()).isEqualByComparingTo("250000");
            assertThat(c.changeRate()).isEqualByComparingTo("7.6923"); // 250,000 / 3,250,000 = 7.692307…%
        }

        @Test
        @DisplayName("4. 기준 행 없음(기간 미달) → available=false, 값은 모두 NULL")
        void noBase_unavailable() {
            Comparison c = AssetChangeCalculator.compare(latest, null, new MarketSum(d("0"), false));

            assertThat(c).isEqualTo(Comparison.UNAVAILABLE);
            assertThat(c.available()).isFalse();
            assertThat(c.baseDate()).isNull();
            assertThat(c.changeAmount()).isNull();
        }

        @Test
        @DisplayName("6-1. 증감률: 기준 total 0 → NULL (증감액은 계산)")
        void rate_baseZero_null() {
            SnapshotValues zero = values(LocalDate.of(2026, 9, 28), "0", "0", "0");

            Comparison c = AssetChangeCalculator.compare(latest, zero, new MarketSum(d("0"), false));

            assertThat(c.changeAmount()).isEqualByComparingTo("3500000");
            assertThat(c.changeRate()).isNull();
        }

        @Test
        @DisplayName("6-2. 증감률: 퍼센트 HALF_UP 4자리 경계 — 0.00005% 올림, 0.00004999% 내림")
        void rate_halfUpBoundary() {
            // 1 / 2,000,000 = 0.00005% → 0.0001
            Comparison up = AssetChangeCalculator.compare(
                    values(AS_OF, "2000001", "0", "0"), values(LocalDate.of(2026, 9, 28), "2000000", "0", "0"),
                    new MarketSum(d("0"), false));
            // 1 / 2,000,040 = 0.0000499990…% → 0.0000
            Comparison down = AssetChangeCalculator.compare(
                    values(AS_OF, "2000041", "0", "0"), values(LocalDate.of(2026, 9, 28), "2000040", "0", "0"),
                    new MarketSum(d("0"), false));
            // −1 / 2,000,000 = −0.00005% → −0.0001 (HALF_UP은 0에서 먼 쪽)
            Comparison negative = AssetChangeCalculator.compare(
                    values(AS_OF, "1999999", "0", "0"), values(LocalDate.of(2026, 9, 28), "2000000", "0", "0"),
                    new MarketSum(d("0"), false));

            assertThat(up.changeRate()).isEqualByComparingTo("0.0001");
            assertThat(down.changeRate()).isEqualByComparingTo("0.0000");
            assertThat(negative.changeRate()).isEqualByComparingTo("-0.0001");
        }

        @Test
        @DisplayName("7-1. 기간 market 전부 값 있음 → market = 합, other = 증감 − 합")
        void market_allPresent() {
            SnapshotValues base = values(LocalDate.of(2026, 9, 28), "1000000", "2000000", "250000"); // 3,250,000

            Comparison c = AssetChangeCalculator.compare(latest, base, new MarketSum(d("150000"), false));

            assertThat(c.marketChangeAmount()).isEqualByComparingTo("150000");
            assertThat(c.otherChangeAmount()).isEqualByComparingTo("100000"); // 250,000 − 150,000
        }

        @Test
        @DisplayName("7-2. 기간 market에 NULL 포함 → market · other 둘 다 NULL (0으로 채우지 않음), 증감액은 계산")
        void market_containsNull() {
            SnapshotValues base = values(LocalDate.of(2026, 9, 28), "1000000", "2000000", "250000");

            Comparison c = AssetChangeCalculator.compare(latest, base, new MarketSum(d("150000"), true));

            assertThat(c.changeAmount()).isEqualByComparingTo("250000");
            assertThat(c.marketChangeAmount()).isNull();
            assertThat(c.otherChangeAmount()).isNull();
        }

        @Test
        @DisplayName("8. 자산군 증감 = 최근 − 기준, 한쪽이 NULL인 자산군만 NULL")
        void assetClassChanges() {
            SnapshotValues base = values(LocalDate.of(2026, 9, 28), "3250000", "1000000", null, "250000");

            Comparison c = AssetChangeCalculator.compare(latest, base, new MarketSum(d("0"), false));

            assertThat(c.assetClassChanges().securities()).isEqualByComparingTo("200000");
            assertThat(c.assetClassChanges().deposit()).isNull();
            assertThat(c.assetClassChanges().cash()).isEqualByComparingTo("50000");
        }
    }

    @Nested
    @DisplayName("기간 market 합 (메모리) — B4 DB 집계와 같은 MarketSum (05 §4.7)")
    class MarketSumOf {

        @Test
        @DisplayName("전부 값 있음 → 합, NULL 포함 아님")
        void allPresent() {
            MarketSum sum = AssetChangeCalculator.marketSumOf(java.util.List.of(d("10"), d("20.50"), d("-5")));

            assertThat(sum.sum()).isEqualByComparingTo("25.50");
            assertThat(sum.containsNull()).isFalse();
        }

        @Test
        @DisplayName("NULL 하나 → NULL 포함 (compare에서 market · other 둘 다 NULL)")
        void containsNull() {
            MarketSum sum = AssetChangeCalculator.marketSumOf(java.util.Arrays.asList(d("10"), null, d("20")));

            assertThat(sum.containsNull()).isTrue();
            Comparison c = AssetChangeCalculator.compare(
                    values(AS_OF, "1100000", "0", "0"), values(LocalDate.of(2026, 9, 28), "1000000", "0", "0"), sum);
            assertThat(c.marketChangeAmount()).isNull();
            assertThat(c.otherChangeAmount()).isNull();
        }

        @Test
        @DisplayName("빈 목록 → 0 (NULL 아님), NULL 포함 아님")
        void empty() {
            MarketSum sum = AssetChangeCalculator.marketSumOf(java.util.List.of());

            assertThat(sum.sum()).isNotNull().isEqualByComparingTo("0");
            assertThat(sum.containsNull()).isFalse();
        }
    }

    @Nested
    @DisplayName("누적 변화 (연동 이후) — 첫 COMPLETE ~ 최근 COMPLETE")
    class SinceFirstRecord {

        @Test
        @DisplayName("첫 COMPLETE ~ 최근 COMPLETE 비교, baseDate = 첫 기록일")
        void compared() {
            SnapshotValues first = values(LocalDate.of(2026, 10, 14), "1000000", "2000000", "300000");
            SnapshotValues latest = values(AS_OF, "1100000", "2000000", "300000");

            Comparison c = AssetChangeCalculator.sinceFirstRecord(latest, first, new MarketSum(d("100000"), false));

            assertThat(c.available()).isTrue();
            assertThat(c.baseDate()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(c.changeAmount()).isEqualByComparingTo("100000");
            assertThat(c.marketChangeAmount()).isEqualByComparingTo("100000");
            assertThat(c.otherChangeAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("첫 COMPLETE = 최근 COMPLETE → 변화 0이 아니라 available=false")
        void firstIsLatest_unavailable() {
            SnapshotValues only = values(AS_OF, "1100000", "2000000", "300000");

            assertThat(AssetChangeCalculator.sinceFirstRecord(only, only, new MarketSum(null, false)))
                    .isEqualTo(Comparison.UNAVAILABLE);
            assertThat(AssetChangeCalculator.sinceFirstRecord(only, null, new MarketSum(null, false)))
                    .isEqualTo(Comparison.UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("전일 대비 — 최근 COMPLETE 행에 저장된 값 그대로 (다시 계산하지 않음)")
    class DayComparison {

        private final SnapshotValues latest = values(AS_OF, "1200000", "2000000", "300000");      // 3,500,000
        private final SnapshotValues base = values(LocalDate.of(2026, 10, 27), "1000000", "2000000", "250000"); // 3,250,000

        @Test
        @DisplayName("5. 첫 COMPLETE(base_snapshot_date NULL) → available=false")
        void firstComplete_unavailable() {
            StoredDayChange stored = new StoredDayChange(null, null, null, null, null);

            assertThat(AssetChangeCalculator.day(latest, stored, null)).isEqualTo(Comparison.UNAVAILABLE);
        }

        @Test
        @DisplayName("9. 전일 결과 = 저장된 base_snapshot_date · day_change_amount · day_change_rate · market · other 그대로")
        void storedValuesAsIs() {
            // 저장값이 두 행 차이(250,000 · 7.6923%)와 달라도 다시 계산하지 않는다
            StoredDayChange stored = new StoredDayChange(LocalDate.of(2026, 10, 27),
                    d("249999.99"), d("7.0000"), d("-5400.00"), d("255399.99"));

            Comparison c = AssetChangeCalculator.day(latest, stored, base);

            assertThat(c.available()).isTrue();
            assertThat(c.baseDate()).isEqualTo(LocalDate.of(2026, 10, 27));
            assertThat(c.baseTotal()).isEqualByComparingTo("3250000");
            assertThat(c.changeAmount()).isEqualByComparingTo("249999.99");
            assertThat(c.changeRate()).isEqualByComparingTo("7.0000");
            assertThat(c.marketChangeAmount()).isEqualByComparingTo("-5400.00");
            assertThat(c.otherChangeAmount()).isEqualByComparingTo("255399.99");
            assertThat(c.assetClassChanges().securities()).isEqualByComparingTo("200000");
            assertThat(c.assetClassChanges().cash()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("저장된 market · other가 NULL(전환기) → 그대로 NULL")
        void storedMarketNull() {
            StoredDayChange stored = new StoredDayChange(LocalDate.of(2026, 10, 27), d("250000"), d("7.6923"), null, null);

            Comparison c = AssetChangeCalculator.day(latest, stored, base);

            assertThat(c.changeAmount()).isEqualByComparingTo("250000");
            assertThat(c.marketChangeAmount()).isNull();
            assertThat(c.otherChangeAmount()).isNull();
        }

        @Test
        @DisplayName("기준일 행 값이 없으면(조회 불가) baseTotal · 자산군 증감만 NULL, 저장된 증감은 그대로")
        void baseValuesMissing() {
            StoredDayChange stored = new StoredDayChange(LocalDate.of(2026, 10, 27), d("250000"), d("7.6923"), d("0"), d("250000"));

            Comparison c = AssetChangeCalculator.day(latest, stored, null);

            assertThat(c.available()).isTrue();
            assertThat(c.changeAmount()).isEqualByComparingTo("250000");
            assertThat(c.baseTotal()).isNull();
            assertThat(c.assetClassChanges().securities()).isNull();
        }
    }
}
