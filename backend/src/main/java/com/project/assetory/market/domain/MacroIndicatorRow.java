package com.project.assetory.market.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 저장할 지표 1행 — 외부 응답을 해석 · 계산한 결과. source는 code에서 정해진다.
 *
 * @param changeRate 직전 행 대비 %(HALF_UP 4자리). BASE_RATE는 NULL (B4 확정 사항 4)
 */
public record MacroIndicatorRow(IndicatorCode code, LocalDate baseDate, BigDecimal value, BigDecimal changeRate) {
}
