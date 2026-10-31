package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판정 결과 — asset_snapshots 1행 + asset_detail_snapshots 행들에 넣을 값.
 * FAILED면 total · 자산군 금액이 모두 NULL. PARTIAL은 성공분 합계(비교 · 감지에는 쓰지 않음 — 05 §4.7).
 *
 * @param details 성공한 자산 · 종목만 (실패 대상은 행을 만들지 않는다)
 */
public record SnapshotJudgement(
        SnapshotStatus status,
        BigDecimal totalAsset,
        BigDecimal securitiesAmount,
        BigDecimal depositAmount,
        BigDecimal cashAmount,
        List<Detail> details
) {

    /**
     * asset_detail_snapshots 1행 — ticker NULL은 자산 단위(증권 계좌 예수금 · 수기 자산)
     *
     * @param quantity 종목 행만 — 평가에 쓴 보유수량. ticker NULL 행은 null
     * @param price    종목 행만 — 평가에 쓴 단가(정상 = 그날 종가, 거래정지 = 직전 종가). ticker NULL 행은 null
     */
    public record Detail(Long assetId, String ticker, BigDecimal amount, BigDecimal quantity, BigDecimal price) {

        public static Detail asset(Long assetId, BigDecimal amount) {
            return new Detail(assetId, null, amount, null, null);
        }
    }
}
