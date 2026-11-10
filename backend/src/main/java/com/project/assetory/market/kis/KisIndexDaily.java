package com.project.assetory.market.kis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 업종기간별시세(일) output2 1행 (inquire-daily-indexchartprice). 필드명은 공식 예제 기준 — 실응답 미대조(B0 K1).
 *
 * @param value         bstp_nmix_prpr 업종 지수(그날 종가 자리)
 * @param kisChangeRate bstp_nmix_prdy_ctrt 한투 대비율 — 일별 행에 있는지 미확인이라 없으면 null.
 *                      저장에는 쓰지 않고 직접 계산값과 비교만 한다 (B4 확정 사항 4)
 */
public record KisIndexDaily(LocalDate tradeDate, BigDecimal value, BigDecimal kisChangeRate) {
}
