package com.project.assetory.snapshot.domain;

import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;
import com.project.assetory.snapshot.domain.SnapshotRevaluator.PriceCorrection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 16:00 잠정 종가로 저장한 기록을 다음 거래일에 확정 종가로 다시 평가 (01 §14 #23, kis-verification V4) — 스프링 없는 순수 계산
class SnapshotRevaluatorTest {

    private static final long ACC = 1L;
    private static final long ACC2 = 2L;
    private static final long CASH = 10L;
    private static final long DEPOSIT = 11L;
    private static final LocalDate BASE = LocalDate.of(2026, 10, 29);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Detail stock(long assetId, String ticker, String quantity, String price) {
        BigDecimal q = d(quantity);
        BigDecimal p = d(price);
        return new Detail(assetId, ticker, q.multiply(p).setScale(2), q, p);
    }

    /**
     * 그날 저장된 기록: 예수금 100,000 + 005930 10주 × 223,000(잠정) + 069500 5주 × 30,000(잠정) + 현금 500,000 + 예금 1,000,000
     * 증권 = 2,230,000 + 150,000 = 2,380,000, 현금 = 100,000 + 500,000 = 600,000, 총 = 3,980,000
     */
    private static SnapshotJudgement stored(SnapshotStatus status) {
        return new SnapshotJudgement(status, d("3980000.00"), d("2380000.00"), d("1000000.00"), d("600000.00"), List.of(
                Detail.asset(ACC, d("100000.00")),
                stock(ACC, "005930", "10", "223000.00"),
                stock(ACC, "069500", "5", "30000.00"),
                Detail.asset(CASH, d("500000.00")),
                Detail.asset(DEPOSIT, d("1000000.00"))));
    }

    @Test
    @DisplayName("확정 종가가 다른 종목만 단가 · 금액을 바꾼다 — 금액 = HALF_UP2(확정 종가 × 그날 기록 수량), 수량은 그대로")
    void revaluesOnlyChangedTickers() {
        SnapshotJudgement result = SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE), Map.of(
                "005930", new PriceCorrection(d("223000"), d("225000")),
                "069500", new PriceCorrection(d("30000"), d("30000")))).orElseThrow();

        Detail samsung = find(result, "005930");
        assertThat(samsung.price()).isEqualByComparingTo("225000");
        assertThat(samsung.quantity()).isEqualByComparingTo("10");
        assertThat(samsung.amount()).isEqualByComparingTo("2250000.00");
        assertThat(samsung.amount().scale()).isEqualTo(2);
        assertThat(find(result, "069500").amount()).isEqualByComparingTo("150000.00");
    }

    @Test
    @DisplayName("증권 · 총자산은 차액만큼 바뀌고, 예적금 · 현금 · 수집 상태 · 예수금 · 수기 자산 행은 그대로")
    void adjustsSecuritiesAndTotalOnly() {
        SnapshotJudgement result = SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE),
                Map.of("005930", new PriceCorrection(d("223000"), d("225000")))).orElseThrow();

        assertThat(result.status()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(result.securitiesAmount()).isEqualByComparingTo("2400000.00");
        assertThat(result.totalAsset()).isEqualByComparingTo("4000000.00");
        assertThat(result.depositAmount()).isEqualByComparingTo("1000000.00");
        assertThat(result.cashAmount()).isEqualByComparingTo("600000.00");
        assertThat(result.details()).filteredOn(detail -> detail.ticker() == null)
                .containsExactlyElementsOf(stored(SnapshotStatus.COMPLETE).details().stream()
                        .filter(detail -> detail.ticker() == null).toList());
    }

    @Test
    @DisplayName("바뀐 종가가 없으면 empty — 같은 보정을 다시 돌려도 기록을 건드리지 않는다(멱등)")
    void noChangeIsEmpty() {
        assertThat(SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE),
                Map.of("005930", new PriceCorrection(d("223000"), d("223000.00"))))).isEmpty();
        assertThat(SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE), Map.of())).isEmpty();
        assertThat(SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE),
                Map.of("000660", new PriceCorrection(d("100000"), d("101000"))))).isEmpty();
    }

    @Test
    @DisplayName("그날 잠정 종가로 평가하지 않은 행(거래정지 — 직전 종가로 평가)은 건드리지 않는다")
    void skipsRowsNotPricedWithProvisionalClose() {
        SnapshotJudgement halted = new SnapshotJudgement(SnapshotStatus.COMPLETE, d("130000.00"), d("30000.00"),
                d("0"), d("100000.00"), List.of(
                Detail.asset(ACC, d("100000.00")),
                stock(ACC, "111111", "3", "10000.00")));

        assertThat(SnapshotRevaluator.revalue(halted,
                Map.of("111111", new PriceCorrection(d("12000"), d("12500"))))).isEmpty();
    }

    @Test
    @DisplayName("수량 또는 단가가 없는 행은 0으로 채우지 않고 건드리지 않는다")
    void skipsRowsWithoutQuantityOrPrice() {
        SnapshotJudgement legacy = new SnapshotJudgement(SnapshotStatus.COMPLETE, d("2330000.00"), d("2230000.00"),
                d("0"), d("100000.00"), List.of(
                Detail.asset(ACC, d("100000.00")),
                new Detail(ACC, "005930", d("2230000.00"), null, null)));

        assertThat(SnapshotRevaluator.revalue(legacy,
                Map.of("005930", new PriceCorrection(d("223000"), d("225000"))))).isEmpty();
    }

    @Test
    @DisplayName("같은 종목이 두 계좌에 있으면 둘 다 보정한다")
    void correctsSameTickerInTwoAccounts() {
        SnapshotJudgement twoAccounts = new SnapshotJudgement(SnapshotStatus.COMPLETE, d("3345000.00"), d("3345000.00"),
                d("0"), d("0"), List.of(
                stock(ACC, "005930", "10", "223000.00"),
                stock(ACC2, "005930", "5", "223000.00")));

        SnapshotJudgement result = SnapshotRevaluator.revalue(twoAccounts,
                Map.of("005930", new PriceCorrection(d("223000"), d("225000")))).orElseThrow();

        assertThat(result.details()).extracting(Detail::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(d("2250000.00"), d("1125000.00"));
        assertThat(result.securitiesAmount()).isEqualByComparingTo("3375000.00");
        assertThat(result.totalAsset()).isEqualByComparingTo("3375000.00");
    }

    @Test
    @DisplayName("PARTIAL은 상태를 유지한 채 저장된(성공한) 종목만 보정, FAILED(행 없음)는 empty")
    void partialKeepsStatusFailedIsEmpty() {
        SnapshotJudgement result = SnapshotRevaluator.revalue(stored(SnapshotStatus.PARTIAL),
                Map.of("005930", new PriceCorrection(d("223000"), d("225000")))).orElseThrow();
        assertThat(result.status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(result.totalAsset()).isEqualByComparingTo("4000000.00");

        SnapshotJudgement failed = new SnapshotJudgement(SnapshotStatus.FAILED, null, null, null, null, List.of());
        assertThat(SnapshotRevaluator.revalue(failed,
                Map.of("005930", new PriceCorrection(d("223000"), d("225000"))))).isEmpty();
    }

    @Test
    @DisplayName("보정한 판정으로 기존 공식을 다시 쓰면 전일 대비 · 시장가격 영향이 확정 종가 기준으로 바뀐다")
    void recomputedChangesUseConfirmedPrice() {
        // 기준일(직전 COMPLETE): 005930 10주 × 220,000, 069500 5주 × 30,000, 총 3,950,000
        List<Detail> baseDetails = List.of(
                Detail.asset(ACC, d("100000.00")),
                stock(ACC, "005930", "10", "220000.00"),
                stock(ACC, "069500", "5", "30000.00"),
                Detail.asset(CASH, d("500000.00")),
                Detail.asset(DEPOSIT, d("1000000.00")));
        BigDecimal baseTotal = d("3950000.00");

        SnapshotJudgement revalued = SnapshotRevaluator.revalue(stored(SnapshotStatus.COMPLETE),
                Map.of("005930", new PriceCorrection(d("223000"), d("225000")))).orElseThrow();
        DayChange day = SnapshotJudge.dayChange(revalued, BASE, baseTotal);
        MarketChange market = SnapshotJudge.marketChange(baseDetails, revalued.details(), day);

        assertThat(day.amount()).isEqualByComparingTo("50000.00");      // 잠정이면 30,000
        assertThat(market.market()).isEqualByComparingTo("50000.00");   // 10주 × (225,000 − 220,000)
        assertThat(market.other()).isEqualByComparingTo("0.00");
    }

    private static Detail find(SnapshotJudgement judgement, String ticker) {
        Optional<Detail> found = judgement.details().stream().filter(d -> ticker.equals(d.ticker())).findFirst();
        return found.orElseThrow();
    }
}
