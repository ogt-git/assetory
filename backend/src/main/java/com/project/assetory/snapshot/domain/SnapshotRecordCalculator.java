package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 자산 기록 월별 목록 계산 (FR-AS-06, 03 S-02) — 스프링 · 엔티티 의존 없는 순수 계산. 쿼리 없이 메모리에서 순회한다.
 * - PARTIAL · FAILED 날은 그 날짜 이전 마지막 COMPLETE의 총자산 · 날짜를 보여준다. 그날의 부분 금액은 쓰지 않는다 (01 §14 #19)
 * - 상승 · 하락일은 COMPLETE 행의 day_change_amount 부호로 센다 (NULL · 0 · 누락 행 제외)
 */
public final class SnapshotRecordCalculator {

    private SnapshotRecordCalculator() {}

    /** asset_snapshots 1행의 목록 계산용 값 */
    public record DayRecord(LocalDate date, SnapshotStatus status, BigDecimal totalAsset, BigDecimal dayChangeAmount) {}

    /** 화면에 보여줄 기준 기록 — COMPLETE 행은 자기 자신, 누락 행은 직전 COMPLETE. 직전 COMPLETE가 없으면 NONE */
    public record Reference(LocalDate date, BigDecimal totalAsset) {

        public static final Reference NONE = new Reference(null, null);
    }

    public record RiseFall(int risingDays, int fallingDays) {}

    /**
     * 행마다 기준 기록을 붙인다.
     *
     * @param previousComplete    그 달 시작 전 마지막 COMPLETE (없으면 null)
     * @param rowsByDateAscending 그 달 행 전체(상태 무관), 날짜 오름차순
     * @return 입력과 같은 순서의 기준 기록
     */
    public static List<Reference> references(Reference previousComplete, List<DayRecord> rowsByDateAscending) {
        Reference last = previousComplete == null ? Reference.NONE : previousComplete;
        List<Reference> result = new ArrayList<>(rowsByDateAscending.size());
        for (DayRecord row : rowsByDateAscending) {
            if (row.status() == SnapshotStatus.COMPLETE) {
                last = new Reference(row.date(), row.totalAsset());
            }
            result.add(last);
        }
        return result;
    }

    public static RiseFall riseFall(List<DayRecord> rows) {
        int rising = 0;
        int falling = 0;
        for (DayRecord row : rows) {
            if (row.status() != SnapshotStatus.COMPLETE || row.dayChangeAmount() == null) {
                continue;
            }
            int sign = row.dayChangeAmount().signum();
            if (sign > 0) {
                rising++;
            } else if (sign < 0) {
                falling++;
            }
        }
        return new RiseFall(rising, falling);
    }
}
