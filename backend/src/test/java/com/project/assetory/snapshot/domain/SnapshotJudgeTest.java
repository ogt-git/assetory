package com.project.assetory.snapshot.domain;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 일별 스냅샷 수집 상태 판정 (FR-AS-02, 05 §4.7 · §4.8, AGENTS 5-C) — 스프링 없는 순수 판정
class SnapshotJudgeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime AT = DAY.atTime(16, 5);

    private static final long ACC1 = 1L;
    private static final long ACC2 = 2L;
    private static final long CASH_ASSET = 10L;
    private static final long DEPOSIT_ASSET = 11L;
    private static final long SAVINGS_ASSET = 12L;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static SnapshotJudgement judge(List<Account> accounts, List<Manual> manuals) {
        return SnapshotJudge.judge(new SnapshotInput(DAY, AT, accounts, manuals)).orElseThrow();
    }

    private static Account okAccount(long assetId, String deposit, HoldingPrice... holdings) {
        return Account.success(assetId, deposit == null ? null : d(deposit), List.of(holdings));
    }

    private static HoldingPrice collected(String ticker, String qty, String close) {
        return HoldingPrice.collected(ticker, qty == null ? null : d(qty), close == null ? null : d(close));
    }

    private static Manual cash(String balance) {
        return new Manual(CASH_ASSET, AssetType.CASH, balance == null ? null : d(balance));
    }

    private static Optional<Detail> detail(SnapshotJudgement j, long assetId, String ticker) {
        return j.details().stream()
                .filter(x -> x.assetId() == assetId && java.util.Objects.equals(x.ticker(), ticker))
                .findFirst();
    }

    @Nested
    @DisplayName("판정 규칙 (Step 3 표 1~15)")
    class Rules {

        @Test
        @DisplayName("1. 모든 연동 SUCCESS + 모든 종목 COLLECTED + 수기 유효 → COMPLETE")
        void allSuccess_complete() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "100000", collected("005930", "10", "71200"))),
                    List.of(cash("50000")));

            assertThat(j.status()).isEqualTo(SnapshotStatus.COMPLETE);
            assertThat(j.securitiesAmount()).isEqualByComparingTo("712000");
            assertThat(j.cashAmount()).isEqualByComparingTo("150000");
            assertThat(j.totalAsset()).isEqualByComparingTo("862000");
        }

        @Test
        @DisplayName("2. 일부 종목 시세 FAILED → PARTIAL, 실패 종목 detail 없음, securities_amount = 성공분 합")
        void someTickerFailed_partial() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "100000",
                    collected("005930", "10", "71200"),
                    HoldingPrice.failed("000660", d("3")))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.securitiesAmount()).isEqualByComparingTo("712000");
            assertThat(detail(j, ACC1, "000660")).isEmpty();
            assertThat(detail(j, ACC1, "005930")).isPresent();
            assertThat(detail(j, ACC1, null)).isPresent(); // 예수금 행은 남는다
        }

        @Test
        @DisplayName("3. 연동 FAILED + 수기 있음 → PARTIAL, 그 계좌는 예수금 포함 detail 없음 (이월 없음)")
        void accountFailed_withManual_partial() {
            SnapshotJudgement j = judge(List.of(Account.of(ACC1, AccountStatus.FAILED)), List.of(cash("50000")));

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.details()).extracting(Detail::assetId).containsExactly(CASH_ASSET);
            assertThat(j.cashAmount()).isEqualByComparingTo("50000");
            assertThat(j.securitiesAmount()).isNull(); // 증권 자산군 단위가 전부 실패 → NULL (확정 7)
            assertThat(j.totalAsset()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("4. 연동 FAILED + 수기 없음 → FAILED, 금액 전부 NULL, detail 없음")
        void accountFailed_noManual_failed() {
            SnapshotJudgement j = judge(List.of(Account.of(ACC1, AccountStatus.FAILED)), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.FAILED);
            assertThat(j.totalAsset()).isNull();
            assertThat(j.securitiesAmount()).isNull();
            assertThat(j.depositAmount()).isNull();
            assertThat(j.cashAmount()).isNull();
            assertThat(j.details()).isEmpty();
        }

        @Test
        @DisplayName("5. EXCLUDED_AUTH 연동만, 수기 없음 → FAILED")
        void excludedAuthOnly_failed() {
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.EXCLUDED_AUTH)), List.of()).status())
                    .isEqualTo(SnapshotStatus.FAILED);
        }

        @Test
        @DisplayName("6. EXCLUDED_AUTH + 수기 있음 → PARTIAL")
        void excludedAuth_withManual_partial() {
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.EXCLUDED_AUTH)), List.of(cash("50000"))).status())
                    .isEqualTo(SnapshotStatus.PARTIAL);
        }

        @Test
        @DisplayName("7. 판정 대상 자산이 하나도 없음 → 결과 없음 (COMPLETE 공집합을 만들지 않는다)")
        void noTargets_empty() {
            assertThat(SnapshotJudge.judge(new SnapshotInput(DAY, AT, List.of(), List.of()))).isEmpty();
        }

        @Test
        @DisplayName("8. 연동 0개 + 수기 자산만 → COMPLETE")
        void manualOnly_complete() {
            SnapshotJudgement j = judge(List.of(), List.of(cash("50000"),
                    new Manual(DEPOSIT_ASSET, AssetType.DEPOSIT, d("1000000"))));

            assertThat(j.status()).isEqualTo(SnapshotStatus.COMPLETE);
            assertThat(j.totalAsset()).isEqualByComparingTo("1050000");
        }

        @Test
        @DisplayName("9. 예수금 정상 0원 + 종목 정상 → COMPLETE (정상 0은 유효)")
        void zeroDeposit_complete() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0", collected("005930", "10", "71200"))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.COMPLETE);
            assertThat(j.cashAmount()).isEqualByComparingTo("0");
            assertThat(detail(j, ACC1, null).orElseThrow().amount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("10. EMPTY_RESULT → 그 계좌 누락: 수기 있으면 PARTIAL, 없으면 FAILED")
        void emptyResult_missing() {
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.EMPTY_RESULT)), List.of(cash("1"))).status())
                    .isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.EMPTY_RESULT)), List.of()).status())
                    .isEqualTo(SnapshotStatus.FAILED);
        }

        @Test
        @DisplayName("11. LOCK_FAILED → 그 계좌 누락: 수기 있으면 PARTIAL, 없으면 FAILED")
        void lockFailed_missing() {
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.LOCK_FAILED)), List.of(cash("1"))).status())
                    .isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(judge(List.of(Account.of(ACC1, AccountStatus.LOCK_FAILED)), List.of()).status())
                    .isEqualTo(SnapshotStatus.FAILED);
        }

        @Test
        @DisplayName("12. HALTED + 직전 종가 있음, 나머지 정상 → COMPLETE, 평가 = 직전 종가 × 수량")
        void halted_withPrevClose_complete() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    collected("005930", "10", "71200"),
                    HoldingPrice.halted("111111", d("5"), d("12000")))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.COMPLETE);
            assertThat(detail(j, ACC1, "111111").orElseThrow().amount()).isEqualByComparingTo("60000");
            assertThat(j.securitiesAmount()).isEqualByComparingTo("772000");
        }

        @Test
        @DisplayName("13. HALTED + 직전 종가 없음 → 그 종목 실패 → PARTIAL")
        void halted_noPrevClose_partial() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    collected("005930", "10", "71200"),
                    HoldingPrice.halted("111111", d("5"), null))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(detail(j, ACC1, "111111")).isEmpty();
        }

        @Test
        @DisplayName("14. 보유 종목 0개 + 예수금만 있는 증권 계좌 SUCCESS → COMPLETE")
        void depositOnly_complete() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "300000")), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.COMPLETE);
            assertThat(j.securitiesAmount()).isEqualByComparingTo("0");
            assertThat(j.totalAsset()).isEqualByComparingTo("300000");
        }

        @Test
        @DisplayName("15. 연동 여러 개 중 하나만 FAILED → PARTIAL")
        void oneOfManyFailed_partial() {
            SnapshotJudgement j = judge(List.of(
                    okAccount(ACC1, "100000", collected("005930", "10", "71200")),
                    Account.of(ACC2, AccountStatus.FAILED)), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.details()).extracting(Detail::assetId).containsOnly(ACC1);
        }
    }

    @Nested
    @DisplayName("금액 집계 (05 §4.4 asset_class)")
    class Amounts {

        @Test
        @DisplayName("증권 예수금 + 수기 CASH → cash, 수기 DEPOSIT · SAVINGS → deposit, 종목 → securities, total = 합")
        void assetClassAggregation() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "100000", collected("005930", "10", "71200"))),
                    List.of(cash("50000"),
                            new Manual(DEPOSIT_ASSET, AssetType.DEPOSIT, d("1000000")),
                            new Manual(SAVINGS_ASSET, AssetType.SAVINGS, d("200000"))));

            assertThat(j.securitiesAmount()).isEqualByComparingTo("712000");
            assertThat(j.cashAmount()).isEqualByComparingTo("150000");
            assertThat(j.depositAmount()).isEqualByComparingTo("1200000");
            assertThat(j.totalAsset()).isEqualByComparingTo("2062000");
            assertThat(j.details()).hasSize(5); // 예수금 · 종목 · 수기 3
        }

        @Test
        @DisplayName("평가액 = 종가 × 그 실행의 보유수량, 종목별 HALF_UP 소수 2자리 후 합산 — detail 합 = 자산군 금액")
        void valuationRounding() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    collected("A00001", "0.3333", "1000.55"),   // 333.483315 → 333.48
                    collected("A00002", "0.0005", "999.99"))),  // 0.499995 → 0.50
                    List.of());

            assertThat(detail(j, ACC1, "A00001").orElseThrow().amount()).isEqualByComparingTo("333.48");
            assertThat(detail(j, ACC1, "A00002").orElseThrow().amount()).isEqualByComparingTo("0.50");
            assertThat(j.securitiesAmount()).isEqualByComparingTo("333.98");
        }
    }

    @Nested
    @DisplayName("detail 수량 · 단가 — 평가에 쓴 값 그대로 (05 §4.8)")
    class DetailQuantityPrice {

        @Test
        @DisplayName("종목 detail: quantity · price = 입력값, amount = HALF_UP(quantity × price, 2)")
        void tickerDetail_keepsQuantityAndPrice() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    collected("A00001", "0.3333", "1000.55"),
                    collected("005930", "10", "71200"))), List.of());

            Detail fractional = detail(j, ACC1, "A00001").orElseThrow();
            assertThat(fractional.quantity()).isEqualByComparingTo("0.3333");
            assertThat(fractional.price()).isEqualByComparingTo("1000.55");
            assertThat(fractional.amount()).isEqualByComparingTo(
                    fractional.quantity().multiply(fractional.price()).setScale(2, java.math.RoundingMode.HALF_UP));

            Detail samsung = detail(j, ACC1, "005930").orElseThrow();
            assertThat(samsung.quantity()).isEqualByComparingTo("10");
            assertThat(samsung.price()).isEqualByComparingTo("71200");
            assertThat(samsung.amount()).isEqualByComparingTo("712000");
        }

        @Test
        @DisplayName("거래정지 종목: price = 직전 종가")
        void haltedDetail_priceIsPreviousClose() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    HoldingPrice.halted("111111", d("5"), d("12000")))), List.of());

            Detail halted = detail(j, ACC1, "111111").orElseThrow();
            assertThat(halted.quantity()).isEqualByComparingTo("5");
            assertThat(halted.price()).isEqualByComparingTo("12000");
            assertThat(halted.amount()).isEqualByComparingTo("60000");
        }

        @Test
        @DisplayName("ticker NULL detail(증권 예수금 · 수기 자산): quantity · price 모두 NULL")
        void assetLevelDetail_nullQuantityAndPrice() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "100000", collected("005930", "10", "71200"))),
                    List.of(cash("50000"), new Manual(DEPOSIT_ASSET, AssetType.DEPOSIT, d("1000000"))));

            for (long assetId : new long[]{ACC1, CASH_ASSET, DEPOSIT_ASSET}) {
                Detail assetLevel = detail(j, assetId, null).orElseThrow();
                assertThat(assetLevel.quantity()).isNull();
                assertThat(assetLevel.price()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("자산군 금액 — 단위가 전부 실패하면 NULL, 대상 자체가 없으면 0 (Step 4 확정 7)")
    class ClassAmounts {

        @Test
        @DisplayName("증권 계좌 전부 실패 + 수기 CASH 있음 → securities NULL, cash = 수기만, deposit 0(대상 없음)")
        void securitiesAllFailed_null() {
            SnapshotJudgement j = judge(List.of(Account.of(ACC1, AccountStatus.FAILED),
                    Account.of(ACC2, AccountStatus.LOCK_FAILED)), List.of(cash("50000")));

            assertThat(j.securitiesAmount()).isNull();
            assertThat(j.cashAmount()).isEqualByComparingTo("50000");
            assertThat(j.depositAmount()).isEqualByComparingTo("0");
            assertThat(j.totalAsset()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("증권 연동이 없는 사용자 → securities 0 (대상 없음)")
        void noSecuritiesTarget_zero() {
            SnapshotJudgement j = judge(List.of(), List.of(cash("50000")));

            assertThat(j.securitiesAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("계좌는 성공했지만 보유 종목 시세가 전부 실패 → securities NULL, 예수금은 cash")
        void allHoldingsFailed_null() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "100000",
                    HoldingPrice.failed("005930", d("10")))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.securitiesAmount()).isNull();
            assertThat(j.cashAmount()).isEqualByComparingTo("100000");
            assertThat(j.totalAsset()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("수기 DEPOSIT 전부 balance 누락 → deposit NULL")
        void depositAllFailed_null() {
            SnapshotJudgement j = judge(List.of(), List.of(cash("1"),
                    new Manual(DEPOSIT_ASSET, AssetType.DEPOSIT, null)));

            assertThat(j.depositAmount()).isNull();
            assertThat(j.cashAmount()).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("0 채움 금지 — 입력의 null은 실패 (AGENTS 5-C)")
    class NoZeroFill {

        @Test
        @DisplayName("SUCCESS인데 예수금 null → 그 계좌 전체 실패 (부분 반영 없음)")
        void nullDeposit_accountFails() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, null, collected("005930", "10", "71200"))),
                    List.of(cash("50000")));

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.details()).extracting(Detail::assetId).containsOnly(CASH_ASSET);
        }

        @Test
        @DisplayName("종목 수량 null · COLLECTED인데 종가 null → 그 종목 실패")
        void nullQuantityOrClose_tickerFails() {
            SnapshotJudgement j = judge(List.of(okAccount(ACC1, "0",
                    collected("005930", null, "71200"),
                    collected("000660", "3", null),
                    collected("035720", "2", "40000"))), List.of());

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(detail(j, ACC1, "005930")).isEmpty();
            assertThat(detail(j, ACC1, "000660")).isEmpty();
            assertThat(j.securitiesAmount()).isEqualByComparingTo("80000");
        }

        @Test
        @DisplayName("수기 balance null → 그 자산 실패")
        void nullManualBalance_fails() {
            SnapshotJudgement j = judge(List.of(), List.of(cash(null),
                    new Manual(DEPOSIT_ASSET, AssetType.DEPOSIT, d("1000"))));

            assertThat(j.status()).isEqualTo(SnapshotStatus.PARTIAL);
            assertThat(j.cashAmount()).isNull(); // CASH 단위가 전부 실패 → NULL (0으로 채우지 않음)
            assertThat(j.details()).extracting(Detail::assetId).containsOnly(DEPOSIT_ASSET);
        }

        @Test
        @DisplayName("모든 대상이 null이면 FAILED")
        void allNull_failed() {
            assertThat(judge(List.of(), List.of(cash(null))).status()).isEqualTo(SnapshotStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("전일 대비 — COMPLETE일 때만, 기준은 직전 COMPLETE")
    class DayChanges {

        private final SnapshotJudgement complete = judge(List.of(), List.of(cash("110000")));

        @Test
        @DisplayName("직전 COMPLETE 대비 변화액 · 변화율(퍼센트, HALF_UP 4자리)")
        void computed() {
            DayChange change = SnapshotJudge.dayChange(complete, LocalDate.of(2026, 10, 8), d("100000"));

            assertThat(change.baseSnapshotDate()).isEqualTo(LocalDate.of(2026, 10, 8));
            assertThat(change.amount()).isEqualByComparingTo("10000");
            assertThat(change.rate()).isEqualByComparingTo("10.0000");
        }

        @Test
        @DisplayName("변화율은 소수 4자리 HALF_UP — 1/3 증가는 33.3333%")
        void rateRounding() {
            SnapshotJudgement now = judge(List.of(), List.of(cash("4")));
            assertThat(SnapshotJudge.dayChange(now, DAY.minusDays(1), d("3")).rate()).isEqualByComparingTo("33.3333");
        }

        @Test
        @DisplayName("직전 COMPLETE 없음 → base · 변화액 · 변화율 모두 NULL")
        void noPrevious() {
            DayChange change = SnapshotJudge.dayChange(complete, null, null);

            assertThat(change.baseSnapshotDate()).isNull();
            assertThat(change.amount()).isNull();
            assertThat(change.rate()).isNull();
        }

        @Test
        @DisplayName("직전 total이 0 → 변화율 NULL, 변화액은 계산")
        void previousZero() {
            DayChange change = SnapshotJudge.dayChange(complete, DAY.minusDays(1), d("0"));

            assertThat(change.amount()).isEqualByComparingTo("110000");
            assertThat(change.rate()).isNull();
        }

        @Test
        @DisplayName("PARTIAL · FAILED는 직전 COMPLETE가 있어도 전일 대비 3개 컬럼 모두 NULL")
        void notComplete_allNull() {
            SnapshotJudgement partial = judge(List.of(Account.of(ACC1, AccountStatus.FAILED)), List.of(cash("1")));
            SnapshotJudgement failed = judge(List.of(Account.of(ACC1, AccountStatus.FAILED)), List.of());

            assertThat(SnapshotJudge.dayChange(partial, DAY.minusDays(1), d("100"))).isEqualTo(DayChange.NONE);
            assertThat(SnapshotJudge.dayChange(failed, DAY.minusDays(1), d("100"))).isEqualTo(DayChange.NONE);
        }
    }

    @Nested
    @DisplayName("시장가격 영향 — 계속 보유한 수량 × 가격 변화 (05 §4.7, 01 §14 #1 · #2)")
    class MarketChanges {

        private static final LocalDate BASE = DAY.minusDays(1);

        // 종목 행 — amount = HALF_UP2(q × p) (05 §4.8 불변식)
        private static Detail stock(long assetId, String ticker, String qty, String price) {
            BigDecimal q = qty == null ? null : d(qty);
            BigDecimal p = price == null ? null : d(price);
            BigDecimal amount = q == null || p == null ? d("0") : q.multiply(p).setScale(2, java.math.RoundingMode.HALF_UP);
            return new Detail(assetId, ticker, amount, q, p);
        }

        private static DayChange change(String amount) {
            return new DayChange(BASE, d(amount), null);
        }

        @Test
        @DisplayName("1. 계속 보유 · 가격 상승 → market = q × Δp, other = day_change − market")
        void held_priceUp() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "70000")),
                    List.of(stock(ACC1, "005930", "10", "71200")),
                    change("112000"));

            assertThat(m.market()).isEqualByComparingTo("12000");
            assertThat(m.other()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("2. 추가 매수(10 → 15주) → 계속 보유 10주만 반영")
        void added_minIsBase() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "70000")),
                    List.of(stock(ACC1, "005930", "15", "71200")),
                    change("368000"));

            assertThat(m.market()).isEqualByComparingTo("12000");
            assertThat(m.other()).isEqualByComparingTo("356000");
        }

        @Test
        @DisplayName("3. 일부 매도(10 → 4주) → 계속 보유 4주만 반영")
        void sold_minIsToday() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "70000")),
                    List.of(stock(ACC1, "005930", "4", "71200")),
                    change("-415200"));

            assertThat(m.market()).isEqualByComparingTo("4800");
            assertThat(m.other()).isEqualByComparingTo("-420000");
        }

        @Test
        @DisplayName("4. 전량 매도 종목 · 신규 종목 → 그 종목 기여 0")
        void soldOutAndNew_zero() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "70000"), stock(ACC1, "000660", "3", "200000")),
                    List.of(stock(ACC1, "000660", "3", "210000"), stock(ACC1, "035720", "2", "40000")),
                    change("-590000"));

            assertThat(m.market()).isEqualByComparingTo("30000"); // 000660만
            assertThat(m.other()).isEqualByComparingTo("-620000");
        }

        @Test
        @DisplayName("5. 같은 ticker를 두 계좌에 보유 → (asset_id, ticker)별로 따로 계산")
        void sameTickerTwoAccounts_separate() {
            // 합쳐서 계산하면 min(10+5, 2+8) = 10 × 1,000 = 10,000이 되지만, 계좌별로는 min(10,2) + min(5,8) = 7
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "70000"), stock(ACC2, "005930", "5", "70000")),
                    List.of(stock(ACC1, "005930", "2", "71000"), stock(ACC2, "005930", "8", "71000")),
                    change("0"));

            assertThat(m.market()).isEqualByComparingTo("7000");
            assertThat(m.other()).isEqualByComparingTo("-7000");
        }

        @Test
        @DisplayName("6. 거래정지(오늘 단가 = 기준일 단가) → 기여 0")
        void halted_zero() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "111111", "5", "12000")),
                    List.of(stock(ACC1, "111111", "5", "12000")),
                    change("50000"));

            assertThat(m.market()).isEqualByComparingTo("0");
            assertThat(m.other()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("7. 종목 없는 사용자(예수금 · 수기만) → market 0(정상 0), other = day_change")
        void noStocks_zero() {
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(Detail.asset(CASH_ASSET, d("280000"))),
                    List.of(Detail.asset(CASH_ASSET, d("300000"))),
                    change("20000"));

            assertThat(m.market()).isNotNull().isEqualByComparingTo("0");
            assertThat(m.other()).isEqualByComparingTo("20000");
        }

        @Test
        @DisplayName("8. 계속 보유 종목의 기준일 quantity 또는 price가 NULL(컬럼 추가 전 행 등) → 둘 다 NULL, 0으로 채우지 않음")
        void baseQuantityOrPriceMissing_null() {
            for (Detail base : List.of(stock(ACC1, "005930", null, "70000"), stock(ACC1, "005930", "10", null))) {
                MarketChange m = SnapshotJudge.marketChange(
                        List.of(base, stock(ACC1, "000660", "3", "200000")),
                        List.of(stock(ACC1, "005930", "10", "71200"), stock(ACC1, "000660", "3", "210000")),
                        change("42000"));

                assertThat(m).isEqualTo(MarketChange.NONE);
            }
        }

        @Test
        @DisplayName("9. 기준(직전 COMPLETE) 없음 · day_change NULL → 둘 다 NULL")
        void noBaseOrNoDayChange_null() {
            List<Detail> base = List.of(stock(ACC1, "005930", "10", "70000"));
            List<Detail> today = List.of(stock(ACC1, "005930", "10", "71200"));

            assertThat(SnapshotJudge.marketChange(base, today, DayChange.NONE)).isEqualTo(MarketChange.NONE);
            assertThat(SnapshotJudge.marketChange(base, today, new DayChange(BASE, null, null))).isEqualTo(MarketChange.NONE);
        }

        @Test
        @DisplayName("10. 종목별 HALF_UP 2자리 후 합산 — 합산 후 반올림과 다른 입력")
        void roundPerTickerThenSum() {
            // 종목별: 0.5 × 0.01 = 0.005 → 0.01, 두 종목 0.02 / 합산 후 반올림이면 0.010 → 0.01
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "A00001", "0.5", "100.00"), stock(ACC1, "A00002", "0.5", "200.00")),
                    List.of(stock(ACC1, "A00001", "0.5", "100.01"), stock(ACC1, "A00002", "0.5", "200.01")),
                    change("0.01"));

            assertThat(m.market()).isEqualByComparingTo("0.02");
            assertThat(m.other()).isEqualByComparingTo("-0.01");
        }

        @Test
        @DisplayName("11. 액면분할(10주 × 100,000 → 20주 × 50,000) → market −500,000, other +500,000 (문서화된 한계)")
        void stockSplit_documentedLimitation() {
            // 05 §4.7 한계 ①: 수량 × 가격 기준이라 분할일은 시장가격 영향 · 기타 자산 변화가 크게 나뉘고 합계만 맞다 (자체 보정 없음, 01 §14 #5와 같은 방침)
            MarketChange m = SnapshotJudge.marketChange(
                    List.of(stock(ACC1, "005930", "10", "100000")),
                    List.of(stock(ACC1, "005930", "20", "50000")),
                    change("0"));

            assertThat(m.market()).isEqualByComparingTo("-500000");
            assertThat(m.other()).isEqualByComparingTo("500000");
        }
    }
}
