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
}
