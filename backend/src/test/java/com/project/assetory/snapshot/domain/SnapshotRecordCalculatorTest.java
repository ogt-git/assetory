package com.project.assetory.snapshot.domain;

import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.DayRecord;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.Reference;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.RiseFall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 자산 기록 월별 목록 계산 (FR-AS-06, 01 §14 #19) — 스프링 없는 순수 계산
class SnapshotRecordCalculatorTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static DayRecord complete(LocalDate date, String total, String dayChange) {
        return new DayRecord(date, SnapshotStatus.COMPLETE, d(total), d(dayChange));
    }

    // PARTIAL · FAILED — 부분 금액(totalAsset)이 있어도 참조 값으로 쓰지 않는다
    private static DayRecord missing(LocalDate date, SnapshotStatus status, String partialTotal) {
        return new DayRecord(date, status, d(partialTotal), null);
    }

    @Nested
    @DisplayName("누락 행 참조 값 — PARTIAL · FAILED 날은 직전 COMPLETE 총자산 · 날짜 (01 §14 #19)")
    class References {

        @Test
        @DisplayName("1. COMPLETE · PARTIAL · FAILED 섞임 → 누락 행마다 그 앞 마지막 COMPLETE, COMPLETE 행은 자기 값")
        void mixed() {
            List<DayRecord> rows = List.of(
                    complete(SEP_1, "3000000", "10000"),
                    missing(SEP_1.plusDays(1), SnapshotStatus.PARTIAL, "500000"),
                    complete(SEP_1.plusDays(2), "3050000", "50000"),
                    missing(SEP_1.plusDays(3), SnapshotStatus.FAILED, null));

            List<Reference> refs = SnapshotRecordCalculator.references(null, rows);

            assertThat(refs).containsExactly(
                    new Reference(SEP_1, d("3000000")),
                    new Reference(SEP_1, d("3000000")),                 // PARTIAL 부분 금액 500,000이 아니라 직전 COMPLETE
                    new Reference(SEP_1.plusDays(2), d("3050000")),
                    new Reference(SEP_1.plusDays(2), d("3050000")));
        }

        @Test
        @DisplayName("2. 연속 누락 → 모두 같은 직전 COMPLETE")
        void consecutiveMissing() {
            List<DayRecord> rows = List.of(
                    complete(SEP_1, "3000000", "10000"),
                    missing(SEP_1.plusDays(1), SnapshotStatus.PARTIAL, "400000"),
                    missing(SEP_1.plusDays(2), SnapshotStatus.FAILED, null),
                    missing(SEP_1.plusDays(3), SnapshotStatus.PARTIAL, "600000"));

            List<Reference> refs = SnapshotRecordCalculator.references(null, rows);

            assertThat(refs.subList(1, 4)).containsOnly(new Reference(SEP_1, d("3000000")));
        }

        @Test
        @DisplayName("3. 달 첫날이 누락 → 입력으로 받은 전달 마지막 COMPLETE 사용")
        void firstDayMissing_usesPreviousMonth() {
            Reference august = new Reference(LocalDate.of(2026, 8, 31), d("2900000"));
            List<DayRecord> rows = List.of(
                    missing(SEP_1, SnapshotStatus.PARTIAL, "100000"),
                    complete(SEP_1.plusDays(1), "3000000", "100000"));

            List<Reference> refs = SnapshotRecordCalculator.references(august, rows);

            assertThat(refs).containsExactly(august, new Reference(SEP_1.plusDays(1), d("3000000")));
        }

        @Test
        @DisplayName("4. 직전 COMPLETE가 전혀 없음 → 날짜 · 총자산 null (0으로 채우지 않음)")
        void noPreviousComplete() {
            List<DayRecord> rows = List.of(
                    missing(SEP_1, SnapshotStatus.FAILED, null),
                    missing(SEP_1.plusDays(1), SnapshotStatus.PARTIAL, "100000"));

            List<Reference> refs = SnapshotRecordCalculator.references(null, rows);

            assertThat(refs).containsOnly(Reference.NONE);
            assertThat(Reference.NONE.date()).isNull();
            assertThat(Reference.NONE.totalAsset()).isNull();
        }

        @Test
        @DisplayName("빈 목록 → 빈 결과")
        void empty() {
            assertThat(SnapshotRecordCalculator.references(null, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("상승 · 하락일 — 그 달 COMPLETE 행의 day_change_amount 부호")
    class RisingFalling {

        @Test
        @DisplayName("5. NULL · 0 제외, PARTIAL · FAILED 행 제외")
        void counts() {
            List<DayRecord> rows = List.of(
                    complete(SEP_1, "3000000", null),                       // 첫 기록 — 전일 대비 없음
                    complete(SEP_1.plusDays(1), "3010000", "10000"),
                    complete(SEP_1.plusDays(2), "3010000", "0"),
                    complete(SEP_1.plusDays(3), "3000000", "-10000"),
                    complete(SEP_1.plusDays(4), "3020000", "20000"),
                    new DayRecord(SEP_1.plusDays(5), SnapshotStatus.PARTIAL, d("100"), d("5000")),  // 누락 행은 세지 않음
                    new DayRecord(SEP_1.plusDays(6), SnapshotStatus.FAILED, null, d("-5000")));

            RiseFall result = SnapshotRecordCalculator.riseFall(rows);

            assertThat(result.risingDays()).isEqualTo(2);
            assertThat(result.fallingDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 목록 → 0 · 0")
        void empty() {
            assertThat(SnapshotRecordCalculator.riseFall(List.of())).isEqualTo(new RiseFall(0, 0));
        }
    }
}
