package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 전일 · 전월 · 전년 · 누적 자산 증감 계산 (FR-AN-01 · 02 · 03 · 06, 01 §3.1 · §3.2) — 스프링 · 엔티티 의존 없는 순수 계산.
 * 입력은 모두 COMPLETE 기록 값이다. 어떤 기록을 기준으로 고를지(목표 날짜 이하 마지막 COMPLETE)는 조회하는 쪽이 정하고, 여기서는 목표 날짜와 비교 값만 만든다.
 * - 현재 기준 = 가장 최근 COMPLETE(asOfDate). 목표 날짜는 항상 asOfDate에서 계산한다 (오늘 날짜를 쓰지 않음)
 * - 전일: 최근 COMPLETE 행에 저장된 값을 그대로 쓴다 (다시 계산하지 않음)
 * - 기준 기록이 없으면 UNAVAILABLE — 기간 상수(30일 · 1년)를 두지 않고, 기준 기록이 있는지로 기간 충족을 판단한다 (FR-AN-06)
 * - 기간 시장가격 영향 = (기준, 최근] COMPLETE 행의 market_change_amount 합. NULL이 하나라도 있으면 market · other 모두 NULL (05 §4.7)
 * - 값이 없으면 0으로 채우지 않는다 (AGENTS 5-C)
 */
public final class AssetChangeCalculator {

    private static final int RATE_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AssetChangeCalculator() {}

    /** COMPLETE 기록 1행의 비교용 값 (asset_snapshots) */
    public record SnapshotValues(LocalDate date, BigDecimal totalAsset,
                                 BigDecimal securitiesAmount, BigDecimal depositAmount, BigDecimal cashAmount) {}

    /** 최근 COMPLETE 행에 저장된 전일 대비 값 (base_snapshot_date · day_change_* · market/other_change_amount) */
    public record StoredDayChange(LocalDate baseSnapshotDate, BigDecimal amount, BigDecimal rate,
                                  BigDecimal marketChangeAmount, BigDecimal otherChangeAmount) {}

    /**
     * (기준, 최근] COMPLETE 행의 market_change_amount 집계
     *
     * @param containsNull 구간 안에 market_change_amount가 NULL인 행이 있으면 true
     */
    public record MarketSum(BigDecimal sum, boolean containsNull) {}

    /** 자산군별 증감 — 어느 한쪽이 NULL인 자산군은 NULL */
    public record AssetClassChanges(BigDecimal securities, BigDecimal deposit, BigDecimal cash) {}

    /** 비교 1건. available=false면 나머지는 모두 NULL */
    public record Comparison(boolean available, LocalDate baseDate, BigDecimal baseTotal,
                             BigDecimal changeAmount, BigDecimal changeRate,
                             BigDecimal marketChangeAmount, BigDecimal otherChangeAmount,
                             AssetClassChanges assetClassChanges) {

        public static final Comparison UNAVAILABLE = new Comparison(false, null, null, null, null, null, null, null);
    }

    // 전월 동일 시점 — 1개월 전 같은 날, 그 날이 없으면 그 달 말일 (3/31 → 2/28 · 2/29)
    public static LocalDate monthTarget(LocalDate asOfDate) {
        return asOfDate.minusMonths(1);
    }

    // 전년 동월 — 1년 전 같은 날, 2/29는 전년 2/28
    public static LocalDate yearTarget(LocalDate asOfDate) {
        return asOfDate.minusYears(1);
    }

    /**
     * 전일 대비 — 저장값 그대로. 기준일 행 값(base)은 baseTotal · 자산군 증감에만 쓰고, 없으면 그 둘만 NULL.
     *
     * @param base base_snapshot_date의 COMPLETE 기록 (조회되지 않으면 null)
     */
    public static Comparison day(SnapshotValues latest, StoredDayChange stored, SnapshotValues base) {
        if (stored.baseSnapshotDate() == null) {
            return Comparison.UNAVAILABLE; // 첫 COMPLETE — 비교할 직전 기록 없음
        }
        return new Comparison(true, stored.baseSnapshotDate(),
                base == null ? null : base.totalAsset(),
                stored.amount(), stored.rate(),
                stored.marketChangeAmount(), stored.otherChangeAmount(),
                assetClassChanges(latest, base));
    }

    /**
     * 전월 · 전년 대비 — 목표 날짜 이하 마지막 COMPLETE(base)와 비교. 기준이 목표 날짜보다 많이 앞서도 그대로 비교한다(baseDate로 표시).
     *
     * @param base 없으면 null (기간 미달)
     */
    public static Comparison compare(SnapshotValues latest, SnapshotValues base, MarketSum marketSum) {
        if (base == null) {
            return Comparison.UNAVAILABLE;
        }
        BigDecimal change = latest.totalAsset().subtract(base.totalAsset());
        BigDecimal market = marketSum.containsNull() || marketSum.sum() == null ? null : marketSum.sum();
        return new Comparison(true, base.date(), base.totalAsset(),
                change, rate(change, base.totalAsset()),
                market, market == null ? null : change.subtract(market),
                assetClassChanges(latest, base));
    }

    /**
     * 연동 이후 누적 변화 — 첫 COMPLETE ~ 최근 COMPLETE. 첫 기록이 곧 최근 기록이면 변화 0이 아니라 UNAVAILABLE.
     */
    public static Comparison sinceFirstRecord(SnapshotValues latest, SnapshotValues first, MarketSum marketSum) {
        if (first == null || !first.date().isBefore(latest.date())) {
            return Comparison.UNAVAILABLE;
        }
        return compare(latest, first, marketSum);
    }

    /**
     * 메모리에 있는 기록들의 market_change_amount 집계 — DB 집계(SUM · COUNT)와 같은 의미의 MarketSum.
     * sum은 값이 있는 행의 합이고, NULL이 하나라도 있으면 containsNull=true (compare에서 market · other 모두 NULL).
     * 빈 목록은 합 0 · NULL 없음.
     *
     * @param marketChangeAmounts (기준, 최근] 구간 COMPLETE 행의 market_change_amount — 기준 행은 넣지 않는다
     */
    public static MarketSum marketSumOf(List<BigDecimal> marketChangeAmounts) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean containsNull = false;
        for (BigDecimal amount : marketChangeAmounts) {
            if (amount == null) {
                containsNull = true;
            } else {
                sum = sum.add(amount);
            }
        }
        return new MarketSum(sum, containsNull);
    }

    // 퍼센트 (1.2345% → 1.2345), HALF_UP 4자리. 기준 0이면 NULL
    private static BigDecimal rate(BigDecimal change, BigDecimal baseTotal) {
        if (baseTotal == null || baseTotal.signum() == 0) {
            return null;
        }
        return change.multiply(HUNDRED).divide(baseTotal, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static AssetClassChanges assetClassChanges(SnapshotValues latest, SnapshotValues base) {
        if (base == null) {
            return new AssetClassChanges(null, null, null);
        }
        return new AssetClassChanges(
                difference(latest.securitiesAmount(), base.securitiesAmount()),
                difference(latest.depositAmount(), base.depositAmount()),
                difference(latest.cashAmount(), base.cashAmount()));
    }

    private static BigDecimal difference(BigDecimal latest, BigDecimal base) {
        return latest == null || base == null ? null : latest.subtract(base);
    }
}
