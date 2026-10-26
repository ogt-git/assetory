package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 직전 COMPLETE 대비 총자산 변화 (asset_snapshots.base_snapshot_date · day_change_amount · day_change_rate).
 * COMPLETE가 아니거나 직전 COMPLETE가 없으면 모두 NULL.
 *
 * @param rate 퍼센트 (1.2345% → 1.2345), 소수 4자리 HALF_UP. 직전 total이 0이면 NULL
 */
public record DayChange(LocalDate baseSnapshotDate, BigDecimal amount, BigDecimal rate) {

    public static final DayChange NONE = new DayChange(null, null, null);
}
