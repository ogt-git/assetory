package com.project.assetory.snapshot.domain;

import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 잠정 종가 보정 — 16:00 배치가 읽은 한투 종가는 잠정값이고 다음 거래일 아침에 확정된다(01 §14 #23, kis-verification V4).
 * 저장된 그날 판정을 확정 종가로 다시 평가한다. 바꾸는 것은 가격에서 나온 값뿐이다.
 * - 종목 행: 단가 = 확정 종가, 금액 = HALF_UP2(확정 종가 × 그날 기록 수량). 수량은 그대로(같은 기준 시점 보유수량)
 * - 증권 · 총자산: 바뀐 금액의 차액만큼. 예적금 · 현금 · 예수금 · 수기 자산 행 · 수집 상태는 그대로
 * - 그날 잠정 종가로 평가한 행만 바꾼다 — 거래정지(직전 종가로 평가)처럼 단가가 잠정 종가와 다른 행은 건드리지 않는다
 * - 수량 · 단가가 없는 행은 0으로 채우지 않고 건드리지 않는다
 * 전일 대비 · 시장가격 영향은 이 결과로 SnapshotJudge.dayChange · marketChange를 다시 계산한다(공식 재사용).
 */
public final class SnapshotRevaluator {

    private static final int AMOUNT_SCALE = 2;

    private SnapshotRevaluator() {}

    /**
     * 종목 1개의 종가 보정
     *
     * @param provisional 16:00 배치가 저장한 그날 종가(잠정)
     * @param confirmed   다음 거래일에 다시 받은 그날 종가(확정)
     */
    public record PriceCorrection(BigDecimal provisional, BigDecimal confirmed) {}

    /**
     * @param stored      그날 저장된 판정(asset_snapshots + asset_detail_snapshots 값)
     * @param corrections ticker → 종가 보정
     * @return 바뀐 판정. 바꿀 행이 없으면 empty(같은 보정을 다시 돌려도 기록을 건드리지 않는다)
     */
    public static Optional<SnapshotJudgement> revalue(SnapshotJudgement stored, Map<String, PriceCorrection> corrections) {
        BigDecimal delta = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        boolean changed = false;
        List<Detail> details = new ArrayList<>(stored.details().size());

        for (Detail detail : stored.details()) {
            PriceCorrection correction = detail.ticker() == null ? null : corrections.get(detail.ticker());
            if (!correctable(detail, correction)) {
                details.add(detail);
                continue;
            }
            BigDecimal amount = detail.quantity().multiply(correction.confirmed()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            details.add(new Detail(detail.assetId(), detail.ticker(), amount, detail.quantity(), correction.confirmed()));
            delta = delta.add(amount.subtract(detail.amount()));
            changed = true;
        }

        if (!changed) {
            return Optional.empty();
        }
        // 종목 행이 저장돼 있으면 증권 · 총자산도 값이 있다(FAILED는 행이 없다)
        return Optional.of(new SnapshotJudgement(stored.status(),
                stored.totalAsset().add(delta), stored.securitiesAmount().add(delta),
                stored.depositAmount(), stored.cashAmount(), List.copyOf(details)));
    }

    // 그날 잠정 종가로 평가했고(단가 = 잠정 종가) 확정 종가가 다른 종목 행만
    private static boolean correctable(Detail detail, PriceCorrection correction) {
        return correction != null
                && detail.quantity() != null
                && detail.price() != null
                && correction.provisional() != null
                && correction.confirmed() != null
                && detail.price().compareTo(correction.provisional()) == 0
                && detail.price().compareTo(correction.confirmed()) != 0;
    }
}
