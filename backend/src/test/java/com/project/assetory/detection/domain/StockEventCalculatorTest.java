package com.project.assetory.detection.domain;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.detection.domain.StockEventCalculator.DetailRow;
import com.project.assetory.detection.domain.StockEventCalculator.HoldingKey;
import com.project.assetory.detection.domain.StockEventCalculator.Input;
import com.project.assetory.detection.domain.StockEventCalculator.PriceRow;
import com.project.assetory.detection.domain.StockEventCalculator.StockEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 종목 급등락 대상 선정 + 사용자 이벤트 숫자 (FR-DT-03 · 07, A4 결정 E-2 · E-3) — 스프링 · DB 없는 순수 계산. 값은 모두 테스트 전용 더미
class StockEventCalculatorTest {

    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal TOTAL = new BigDecimal("10000000");

    // 그날 종가로 평가한 종목 행: amount = HALF_UP2(quantity × close)
    private static DetailRow row(long assetId, String ticker, String quantity, String close) {
        BigDecimal q = new BigDecimal(quantity);
        BigDecimal p = new BigDecimal(close);
        return new DetailRow(assetId, ticker, q, p, q.multiply(p).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    private static PriceRow price(String ticker, String close, String prevClose, String changeRate) {
        return new PriceRow(ticker, ticker + " 종목", new BigDecimal(close), new BigDecimal(prevClose), new BigDecimal(changeRate));
    }

    private static List<StockEvent> detect(List<DetailRow> rows, List<PriceRow> prices, BigDecimal threshold,
                                           Set<String> excluded, Map<HoldingKey, BigDecimal> avgBuy) {
        Map<String, PriceRow> byTicker = new HashMap<>();
        prices.forEach(p -> byTicker.put(p.ticker(), p));
        return StockEventCalculator.detect(new Input(rows, byTicker, avgBuy, TOTAL, threshold, excluded));
    }

    private static List<StockEvent> detect(List<DetailRow> rows, List<PriceRow> prices) {
        return detect(rows, prices, FIVE, Set.of(), Map.of());
    }

    // ── 대상 선정 ──

    @Test
    @DisplayName("경계: 5.0000은 감지, 4.9999는 미감지, −5는 감지(DOWN), 상승은 UP")
    void thresholdBoundary() {
        List<StockEvent> events = detect(
                List.of(row(1, "AAA111", "10", "1050"), row(1, "BBB222", "10", "1049.99"),
                        row(1, "CCC333", "10", "950"), row(1, "DDD444", "10", "1200")),
                List.of(price("AAA111", "1050", "1000", "5.0000"), price("BBB222", "1049.99", "1000", "4.9999"),
                        price("CCC333", "950", "1000", "-5"), price("DDD444", "1200", "1000", "20")));

        assertThat(events).extracting(StockEvent::ticker).containsExactly("AAA111", "CCC333", "DDD444");
        assertThat(events).extracting(StockEvent::direction).containsExactly(Direction.UP, Direction.DOWN, Direction.UP);
        assertThat(events.get(1).changeRate()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("사용자 임계치 8이면 기본 5 대신 8로 판정한다 (7은 미감지, 8은 감지)")
    void userThreshold() {
        List<StockEvent> events = detect(
                List.of(row(1, "AAA111", "1", "1070"), row(1, "BBB222", "1", "920")),
                List.of(price("AAA111", "1070", "1000", "7"), price("BBB222", "920", "1000", "-8")),
                new BigDecimal("8"), Set.of(), Map.of());

        assertThat(events).extracting(StockEvent::ticker).containsExactly("BBB222");
    }

    @Test
    @DisplayName("제외 종목은 대상에서 빠진다")
    void excludedTickers() {
        List<StockEvent> events = detect(
                List.of(row(1, "AAA111", "1", "1100"), row(1, "BBB222", "1", "900")),
                List.of(price("AAA111", "1100", "1000", "10"), price("BBB222", "900", "1000", "-10")),
                FIVE, Set.of("AAA111"), Map.of());

        assertThat(events).extracting(StockEvent::ticker).containsExactly("BBB222");
    }

    @Test
    @DisplayName("거래정지(그날 시세 없음) 종목은 변동률을 0으로 보지 않고 대상에서 뺀다")
    void haltedExcluded() {
        List<StockEvent> events = detect(List.of(row(1, "HALT01", "10", "1000")), List.of());

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("그날 종가가 아닌 단가로 평가된 행(직전 종가 평가)은 대상에서 뺀다")
    void notTodayClose_excluded() {
        List<StockEvent> events = detect(List.of(row(1, "AAA111", "10", "1000")),
                List.of(price("AAA111", "1100", "1000", "10")));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("예수금 · 수기 자산 행(ticker 없음)은 무시한다")
    void nonStockRowsIgnored() {
        List<StockEvent> events = detect(
                List.of(new DetailRow(1L, null, null, null, new BigDecimal("500000")), row(1, "AAA111", "1", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")));

        assertThat(events).extracting(StockEvent::ticker).containsExactly("AAA111");
    }

    @Test
    @DisplayName("임계치가 없으면(기본값 없는 규칙) 아무것도 감지하지 않는다")
    void noThreshold_none() {
        List<StockEvent> events = detect(List.of(row(1, "AAA111", "1", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")), null, Set.of(), Map.of());

        assertThat(events).isEmpty();
    }

    // ── 금액 · 비중 · 손익 ──

    @Test
    @DisplayName("두 계좌가 같은 종목을 가지면 금액 · 비중 · 손익이 합산된다")
    void twoAccountsAggregated() {
        // 계좌1: 10주, 평단 900 / 계좌2: 30주, 평단 1000 — 오늘 1100, 어제 1000
        List<StockEvent> events = detect(
                List.of(row(1, "AAA111", "10", "1100"), row(2, "AAA111", "30", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")), FIVE, Set.of(),
                Map.of(new HoldingKey(1L, "AAA111"), new BigDecimal("900"),
                        new HoldingKey(2L, "AAA111"), new BigDecimal("1000")));

        StockEvent e = events.get(0);
        // (10 + 30) × (1100 − 1000)
        assertThat(e.changeAmount()).isEqualByComparingTo("4000.00");
        // 평가 44,000 ÷ 총자산 10,000,000 × 100 = 0.44
        assertThat(e.positionRatio()).isEqualByComparingTo("0.44");
        // 원금 9,000 + 30,000 = 39,000, 손익 2,000 + 3,000 = 5,000 → 5,000 ÷ 39,000 × 100 = 12.8205
        assertThat(e.profitLossRate()).isEqualByComparingTo("12.8205");
        assertThat(e.stockName()).isEqualTo("AAA111 종목");
    }

    @Test
    @DisplayName("추가 매수는 change_amount에 섞이지 않는다 — 기록 수량 × (오늘 종가 − 전일 종가)만")
    void buyNotMixedIntoChangeAmount() {
        // 어제 10주였는데 오늘 100주로 늘어도, 오늘 기록 수량 100 × 가격차 −60만 반영한다(매수 금액 미포함)
        List<StockEvent> events = detect(List.of(row(1, "AAA111", "100", "940")),
                List.of(price("AAA111", "940", "1000", "-6")));

        assertThat(events.get(0).changeAmount()).isEqualByComparingTo("-6000.00");
    }

    @Test
    @DisplayName("원금을 모르는 계좌가 하나라도 있으면 손익률은 NULL (0으로 채우지 않음), 비중 · 금액은 그대로")
    void unknownPrincipal_nullRate() {
        List<StockEvent> events = detect(
                List.of(row(1, "AAA111", "10", "1100"), row(2, "AAA111", "10", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")), FIVE, Set.of(),
                Map.of(new HoldingKey(1L, "AAA111"), new BigDecimal("900")));

        StockEvent e = events.get(0);
        assertThat(e.profitLossRate()).isNull();
        assertThat(e.changeAmount()).isEqualByComparingTo("2000.00");
        assertThat(e.positionRatio()).isEqualByComparingTo("0.22");
    }

    @Test
    @DisplayName("원금 합계가 0이면 손익률은 NULL")
    void zeroPrincipal_nullRate() {
        List<StockEvent> events = detect(List.of(row(1, "AAA111", "10", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")), FIVE, Set.of(),
                Map.of(new HoldingKey(1L, "AAA111"), BigDecimal.ZERO));

        assertThat(events.get(0).profitLossRate()).isNull();
    }

    @Test
    @DisplayName("반올림은 HALF_UP — change_amount 소수 2자리, 비중 2자리, 손익률 4자리")
    void rounding() {
        // 수량 3.3333주(소수 주식) × 가격차 (1000.55 − 950.00) = 168.4983... → 168.50
        BigDecimal q = new BigDecimal("3.3333");
        BigDecimal close = new BigDecimal("1000.55");
        DetailRow r = new DetailRow(1L, "AAA111", q, close, q.multiply(close).setScale(2, java.math.RoundingMode.HALF_UP));
        List<StockEvent> events = detect(List.of(r), List.of(price("AAA111", "1000.55", "950.00", "5.3211")),
                FIVE, Set.of(), Map.of(new HoldingKey(1L, "AAA111"), new BigDecimal("777.77")));

        StockEvent e = events.get(0);
        assertThat(e.changeAmount()).isEqualByComparingTo("168.50");
        assertThat(e.changeAmount().scale()).isEqualTo(2);
        assertThat(e.positionRatio().scale()).isEqualTo(2);
        assertThat(e.profitLossRate().scale()).isEqualTo(4);
        // 평가 HALF_UP2(3.3333 × 1000.55) = 3335.13, 원금 HALF_UP2(3.3333 × 777.77) = 2592.54, 손익 742.59
        // → 742.59 ÷ 2592.54 × 100 = 28.64334… → 28.6433 (HALF_UP 4자리)
        assertThat(e.profitLossRate()).isEqualByComparingTo("28.6433");
    }

    @Test
    @DisplayName("컬럼 정밀도를 넘는 값은 저장하지 않고 NULL — 손익률 DECIMAL(8,4) · 비중 DECIMAL(5,2)")
    void overPrecision_null() {
        // 평단 1원 → 손익률 109,900% (정수부 6자리 > 4자리)
        List<StockEvent> events = detect(List.of(row(1, "AAA111", "10", "1100")),
                List.of(price("AAA111", "1100", "1000", "10")), FIVE, Set.of(),
                Map.of(new HoldingKey(1L, "AAA111"), BigDecimal.ONE));

        assertThat(events.get(0).profitLossRate()).isNull();
    }

    @Test
    @DisplayName("결과는 종목코드 순 — 같은 입력이면 항상 같은 순서")
    void orderedByTicker() {
        List<StockEvent> events = detect(
                List.of(row(1, "ZZZ999", "1", "1100"), row(1, "AAA111", "1", "1100")),
                List.of(price("ZZZ999", "1100", "1000", "10"), price("AAA111", "1100", "1000", "10")));

        assertThat(events).extracting(StockEvent::ticker).containsExactly("AAA111", "ZZZ999");
    }
}
