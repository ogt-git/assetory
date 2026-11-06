package com.project.assetory.detection.domain;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.snapshot.domain.PortfolioCalculator;
import com.project.assetory.snapshot.domain.PortfolioCalculator.HoldingInput;
import com.project.assetory.snapshot.domain.PortfolioCalculator.Profit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 종목 급등락 대상 선정 + 사용자 이벤트 숫자 (FR-DT-03 · 07, 05 §11.3) — 스프링 · DB 의존 없는 순수 계산.
 *
 * 대상: 그날 COMPLETE 기록의 종목 행 중 "그날 종가로 평가된 행"(그 종목 · 날짜 시세가 있고 단가 = 그 종가 —
 * SnapshotRevaluator와 같은 기준). 거래정지 등 시세가 없는 종목은 변동률을 0으로 보지 않고 뺀다.
 * 제외 종목을 빼고, |전일 대비 변동률| ≥ 적용 임계치(상승 · 하락 모두, ETF 포함)만 남긴다.
 *
 * 숫자 (종목 단위, 같은 종목을 가진 계좌는 합산):
 * - change_amount = Σ HALF_UP2[그날 기록 수량 × (오늘 종가 − 전일 종가)] — 가격 변동분만, 매수 · 매도로 바뀐 수량 효과 제외 (A4 E-2).
 *   한계: 액면분할일은 전일 종가가 분할 전 가격이라 크게 틀릴 수 있다 (01 §14 #5와 같은 한계)
 * - position_ratio = 그 종목 평가금액 합 ÷ 그날 총자산 × 100 (PortfolioCalculator.ratio, HALF_UP 2자리)
 * - profit_loss_rate = Σ손익 ÷ Σ원금 × 100 (HALF_UP 4자리), 원금 = 기록 수량 × 현재 평균 매입가 (PortfolioCalculator.profit).
 *   계좌 중 하나라도 원금을 모르면 NULL, 원금 합 0이면 NULL (0으로 채우지 않음, AGENTS 5-C)
 * - 컬럼 정밀도(change_amount 18,2 · position_ratio 5,2 · profit_loss_rate 8,4)를 넘는 값은 NULL — 잘못 잘린 값을 저장하지 않는다
 */
public final class StockEventCalculator {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // DECIMAL(p, s)의 정수부 자리수 = p − s
    private static final int CHANGE_AMOUNT_INT_DIGITS = 16;
    private static final int POSITION_RATIO_INT_DIGITS = 3;
    private static final int PROFIT_RATE_INT_DIGITS = 4;

    private StockEventCalculator() {}

    /** 그날 기록의 detail 행 1개 (asset_detail_snapshots). ticker NULL = 예수금 · 수기 자산 행 */
    public record DetailRow(Long assetId, String ticker, BigDecimal quantity, BigDecimal price, BigDecimal amount) {}

    /** 그 종목의 그날 시세 (stock_prices) */
    public record PriceRow(String ticker, String stockName, BigDecimal closePrice, BigDecimal prevClosePrice,
                           BigDecimal changeRate) {}

    /** 평균 매입가 매칭 키 — (자산, 종목) */
    public record HoldingKey(Long assetId, String ticker) {}

    /**
     * @param rows             그날 기록의 detail 행 전체
     * @param prices           종목코드 → 그날 시세 (없으면 그날 시세 행 없음)
     * @param avgBuyPrices     (자산, 종목) → 현재 평균 매입가. 없거나 값이 null이면 원금 모름
     * @param totalAsset       그날 기록 총자산
     * @param appliedThreshold 적용 임계치(퍼센트). null이면 감지하지 않는다
     * @param excludedTickers  제외 종목
     */
    public record Input(List<DetailRow> rows, Map<String, PriceRow> prices, Map<HoldingKey, BigDecimal> avgBuyPrices,
                        BigDecimal totalAsset, BigDecimal appliedThreshold, Set<String> excludedTickers) {}

    /** 감지된 종목 1개 — 사용자 이벤트에 저장할 값 */
    public record StockEvent(String ticker, String stockName, BigDecimal changeRate, Direction direction,
                             BigDecimal changeAmount, BigDecimal positionRatio, BigDecimal profitLossRate) {}

    /** 급등락 판정 — |전일 대비 변동률| ≥ 적용 임계치 (경계 포함, 상승 · 하락 모두). 값이 없으면 false (0으로 보지 않음) */
    public static boolean exceeds(BigDecimal changeRate, BigDecimal threshold) {
        return changeRate != null && threshold != null && changeRate.abs().compareTo(threshold) >= 0;
    }

    /** 감지된 종목 목록 (종목코드 순) */
    public static List<StockEvent> detect(Input input) {
        if (input.appliedThreshold() == null) {
            return List.of();
        }
        Map<String, List<DetailRow>> byTicker = new TreeMap<>();
        for (DetailRow row : input.rows()) {
            if (row.ticker() == null || input.excludedTickers().contains(row.ticker())) {
                continue;
            }
            PriceRow price = input.prices().get(row.ticker());
            if (!evaluatedAtClose(row, price)) {
                continue;
            }
            byTicker.computeIfAbsent(row.ticker(), t -> new ArrayList<>()).add(row);
        }

        List<StockEvent> events = new ArrayList<>();
        byTicker.forEach((ticker, rows) -> {
            PriceRow price = input.prices().get(ticker);
            if (!exceeds(price.changeRate(), input.appliedThreshold())) {
                return;
            }
            events.add(new StockEvent(ticker, price.stockName(), price.changeRate(), Direction.of(price.changeRate()),
                    changeAmount(rows, price), positionRatio(rows, input.totalAsset()),
                    profitLossRate(rows, input.avgBuyPrices())));
        });
        return events;
    }

    // 그날 종가로 평가한 행 — 그날 시세가 있고 단가가 그 종가 (거래정지 · 직전 종가 평가 행 제외)
    private static boolean evaluatedAtClose(DetailRow row, PriceRow price) {
        return price != null && price.closePrice() != null && row.price() != null
                && row.price().compareTo(price.closePrice()) == 0;
    }

    private static BigDecimal changeAmount(List<DetailRow> rows, PriceRow price) {
        if (price.prevClosePrice() == null) {
            return null;
        }
        BigDecimal priceDiff = price.closePrice().subtract(price.prevClosePrice());
        BigDecimal sum = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        for (DetailRow row : rows) {
            if (row.quantity() == null) {
                return null;
            }
            sum = sum.add(row.quantity().multiply(priceDiff).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        }
        return fits(sum, CHANGE_AMOUNT_INT_DIGITS);
    }

    private static BigDecimal positionRatio(List<DetailRow> rows, BigDecimal totalAsset) {
        BigDecimal evalSum = BigDecimal.ZERO;
        for (DetailRow row : rows) {
            if (row.amount() == null) {
                return null;
            }
            evalSum = evalSum.add(row.amount());
        }
        return fits(PortfolioCalculator.ratio(evalSum, totalAsset), POSITION_RATIO_INT_DIGITS);
    }

    private static BigDecimal profitLossRate(List<DetailRow> rows, Map<HoldingKey, BigDecimal> avgBuyPrices) {
        BigDecimal principalSum = BigDecimal.ZERO;
        BigDecimal profitSum = BigDecimal.ZERO;
        for (DetailRow row : rows) {
            BigDecimal avgBuy = avgBuyPrices.get(new HoldingKey(row.assetId(), row.ticker()));
            Profit profit = PortfolioCalculator.profit(new HoldingInput(row.amount(), row.quantity(), avgBuy));
            if (profit.principal() == null) {
                return null;
            }
            principalSum = principalSum.add(profit.principal());
            profitSum = profitSum.add(profit.profitLoss());
        }
        if (principalSum.signum() == 0) {
            return null;
        }
        BigDecimal rate = profitSum.multiply(HUNDRED).divide(principalSum, RATE_SCALE, RoundingMode.HALF_UP);
        return fits(rate, PROFIT_RATE_INT_DIGITS);
    }

    // 정수부 자리수가 컬럼을 넘으면 null
    private static BigDecimal fits(BigDecimal value, int maxIntegerDigits) {
        if (value == null) {
            return null;
        }
        int integerDigits = value.precision() - value.scale();
        return integerDigits > maxIntegerDigits ? null : value;
    }
}
