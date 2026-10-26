package com.project.assetory.snapshot.domain;

import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotInput.PriceStatus;
import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 일별 스냅샷 수집 상태 판정 (FR-AS-02, 05 §4.7 · §4.8, 01 §3.1 "기록의 완전성").
 * 판정 단위: 증권 계좌(실패하면 예수금 · 종목 전체가 실패 1건), 성공한 계좌의 예수금 1건 + 종목별 1건, 수기 자산별 1건.
 * - 전부 성공 → COMPLETE / 일부 성공 → PARTIAL / 성공 0 → FAILED / 대상 0 → 결과 없음(행을 만들지 않음)
 * - null은 0으로 바꾸지 않고 그 단위의 실패다. 실패 단위는 detail을 만들지 않고 직전 값을 복사하지 않는다 (AGENTS 5-C)
 * - 평가액 = 종가(거래정지면 직전 거래일 종가) × 이번 실행의 보유수량, 종목별 HALF_UP 소수 2자리
 * - 자산군 금액: 그 자산군 단위가 전부 실패하면 NULL, 대상 자체가 없으면 0. 실패한 증권 계좌는 증권 · 현금(예수금) 양쪽의 실패로 센다
 */
public final class SnapshotJudge {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SnapshotJudge() {}

    public static Optional<SnapshotJudgement> judge(SnapshotInput input) {
        ClassTally securities = new ClassTally();
        ClassTally deposit = new ClassTally();
        ClassTally cash = new ClassTally();
        int targets = 0;
        int successes = 0;
        List<Detail> details = new ArrayList<>();

        for (Account account : input.accounts()) {
            targets++;
            // 수집 실패 · 결과 없음 · 인증 제외 · 락 실패, 또는 예수금이 없는 응답 → 계좌 전체가 실패 1건 (부분 반영 없음)
            // 종목 구성을 알 수 없으므로 증권 · 현금 양쪽 자산군의 실패로 센다
            if (account.status() != AccountStatus.SUCCESS || account.deposit() == null) {
                securities.fail();
                cash.fail();
                continue;
            }
            successes++;
            BigDecimal accountDeposit = scale(account.deposit());
            cash.succeed(accountDeposit);
            details.add(new Detail(account.assetId(), null, accountDeposit));

            for (HoldingPrice holding : account.holdings()) {
                targets++;
                BigDecimal value = valuation(holding);
                if (value == null) {
                    securities.fail();
                    continue;
                }
                successes++;
                securities.succeed(value);
                details.add(new Detail(account.assetId(), holding.ticker(), value));
            }
        }

        for (Manual manual : input.manualAssets()) {
            targets++;
            // 05 §4.4: 예금 · 적금 → DEPOSIT, 현금 · 입출금 → CASH
            ClassTally tally = AssetClass.of(manual.assetType()) == AssetClass.DEPOSIT ? deposit : cash;
            if (manual.balance() == null) {
                tally.fail();
                continue;
            }
            successes++;
            BigDecimal balance = scale(manual.balance());
            tally.succeed(balance);
            details.add(new Detail(manual.assetId(), null, balance));
        }

        if (targets == 0) {
            return Optional.empty();
        }
        if (successes == 0) {
            return Optional.of(new SnapshotJudgement(SnapshotStatus.FAILED, null, null, null, null, List.of()));
        }
        SnapshotStatus status = successes == targets ? SnapshotStatus.COMPLETE : SnapshotStatus.PARTIAL;
        BigDecimal total = securities.sumOrZero().add(deposit.sumOrZero()).add(cash.sumOrZero());
        return Optional.of(new SnapshotJudgement(status, total,
                securities.amount(), deposit.amount(), cash.amount(), List.copyOf(details)));
    }

    /**
     * 직전 COMPLETE 대비 변화 — COMPLETE일 때만 계산한다.
     *
     * @param previousCompleteDate  기준일 이전 직전 COMPLETE 날짜 (없으면 null)
     * @param previousCompleteTotal 그 날의 total_asset
     */
    public static DayChange dayChange(SnapshotJudgement current, LocalDate previousCompleteDate,
                                      BigDecimal previousCompleteTotal) {
        if (current.status() != SnapshotStatus.COMPLETE || previousCompleteDate == null || previousCompleteTotal == null) {
            return DayChange.NONE;
        }
        BigDecimal amount = current.totalAsset().subtract(previousCompleteTotal);
        BigDecimal rate = previousCompleteTotal.signum() == 0
                ? null
                : amount.multiply(HUNDRED).divide(previousCompleteTotal, RATE_SCALE, RoundingMode.HALF_UP);
        return new DayChange(previousCompleteDate, amount, rate);
    }

    // 평가할 수 없으면 null — 시세 실패, 거래정지인데 직전 종가 없음, 수량 · 가격 누락
    private static BigDecimal valuation(HoldingPrice holding) {
        if (holding.priceStatus() == PriceStatus.FAILED || holding.quantity() == null || holding.price() == null) {
            return null;
        }
        return scale(holding.price().multiply(holding.quantity()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    // 자산군별 단위 집계 — 대상이 없으면 0, 대상이 있는데 성공이 없으면 NULL, 아니면 성공분 합
    private static final class ClassTally {
        private int targets;
        private int successes;
        private BigDecimal sum = BigDecimal.ZERO.setScale(AMOUNT_SCALE);

        void succeed(BigDecimal value) {
            targets++;
            successes++;
            sum = sum.add(value);
        }

        void fail() {
            targets++;
        }

        BigDecimal amount() {
            return targets > 0 && successes == 0 ? null : sum;
        }

        BigDecimal sumOrZero() {
            return sum;
        }
    }
}
