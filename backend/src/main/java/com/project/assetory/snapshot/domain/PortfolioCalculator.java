package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04) — 스프링 · 엔티티 의존 없는 순수 계산.
 * - 원금 = 기록 수량 × 현재 평균 매입가, HALF_UP 2자리 (05 §1.3 금액). 둘 중 하나라도 없으면 원금 · 손익 · 손익률 모두 null (0 채움 금지)
 * - 손익 = 평가금액 − 원금, 손익률 = 손익 ÷ 원금 × 100 (퍼센트, HALF_UP 4자리 — 05 §1.3 수익률). 원금 0이면 손익률만 null
 * - 비중 = 금액 ÷ 총자산 × 100 (퍼센트, HALF_UP 2자리 — user_market_events.position_ratio와 같은 단위). 반올림 합계를 100으로 맞추지 않는다
 */
public final class PortfolioCalculator {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final int RATIO_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PortfolioCalculator() {}

    /**
     * 종목 1행의 손익 계산 입력.
     *
     * @param evalAmount  기록 평가금액(asset_detail_snapshots.amount — 한투 종가 × 수량)
     * @param quantity    기록 수량(asset_detail_snapshots.quantity). B2 이전 기록이면 null
     * @param avgBuyPrice 현재 holdings 평균 매입가. 값이 없거나 그 (자산, 종목)이 지금 보유 목록에 없으면 null
     */
    public record HoldingInput(BigDecimal evalAmount, BigDecimal quantity, BigDecimal avgBuyPrice) {}

    /** 원금 · 손익 · 손익률. 원금을 알 수 없으면 NONE */
    public record Profit(BigDecimal principal, BigDecimal profitLoss, BigDecimal profitLossRate) {

        public static final Profit NONE = new Profit(null, null, null);
    }

    public static Profit profit(HoldingInput input) {
        if (input.quantity() == null || input.avgBuyPrice() == null || input.evalAmount() == null) {
            return Profit.NONE;
        }
        BigDecimal principal = input.quantity().multiply(input.avgBuyPrice()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal profitLoss = input.evalAmount().subtract(principal);
        BigDecimal rate = principal.signum() == 0
                ? null
                : profitLoss.multiply(HUNDRED).divide(principal, RATE_SCALE, RoundingMode.HALF_UP);
        return new Profit(principal, profitLoss, rate);
    }

    // 금액 ÷ 총자산 × 100. 금액 · 총자산이 없거나 총자산 0이면 null
    public static BigDecimal ratio(BigDecimal amount, BigDecimal totalAsset) {
        if (amount == null || totalAsset == null || totalAsset.signum() == 0) {
            return null;
        }
        return amount.multiply(HUNDRED).divide(totalAsset, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
