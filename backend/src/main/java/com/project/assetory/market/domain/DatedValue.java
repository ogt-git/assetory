package com.project.assetory.market.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

// 외부 응답에서 꺼낸 (날짜, 값) 1쌍 — 지수 · 거시지표 공용. 날짜 · 값은 원본 그대로
public record DatedValue(LocalDate date, BigDecimal value) {
}
