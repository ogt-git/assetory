package com.project.assetory.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 잠정 종가 확정 재조회 결과 (01 §14 #23, kis-verification V4).
 *
 * @param changes 종가가 바뀐 종목만 — ticker → (16:00 잠정 종가, 확정 종가). 거래량 · 변동률만 바뀐 종목은 넣지 않는다
 * @param checked 그날 잠정 행이 있어 확인 대상이 된 종목 수
 * @param failed  호출 실패 · 그날(또는 직전 거래일) 행 없음 · 중단으로 확인하지 못한 종목 수
 * @param aborted 연속 호출 실패로 남은 종목 호출을 멈췄는지
 */
public record ClosePriceConfirmResult(
        LocalDate tradeDate,
        Map<String, ClosePriceChange> changes,
        int checked,
        int failed,
        boolean aborted
) {

    public record ClosePriceChange(BigDecimal provisional, BigDecimal confirmed) {}

    static ClosePriceConfirmResult skipped(LocalDate tradeDate) {
        return new ClosePriceConfirmResult(tradeDate, Map.of(), 0, 0, false);
    }
}
