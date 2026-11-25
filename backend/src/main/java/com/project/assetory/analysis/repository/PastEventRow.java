package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 지난 이벤트 한 줄 (ANL-10, FR-CA-13) — 날짜 · 방향 · 변동률만. 소급 이벤트도 같은 모양이다(금액 필드를 쓰지 않음)
 */
public record PastEventRow(LocalDate eventDate, Direction direction, BigDecimal changeRate) {
}
