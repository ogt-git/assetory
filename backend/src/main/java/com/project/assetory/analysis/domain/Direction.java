package com.project.assetory.analysis.domain;

import java.math.BigDecimal;

// docs/05 §5.3 user_market_events.direction — 급등락 방향 (FR-DT-03 상승 · 하락 모두)
public enum Direction {
    UP,
    DOWN;

    /** 전 거래일 대비 변동률의 부호. 0 · null은 방향이 없어 거부한다 (임계치 최소 2%라 이벤트가 될 수 없음) */
    public static Direction of(BigDecimal changeRate) {
        if (changeRate == null || changeRate.signum() == 0) {
            throw new IllegalArgumentException("변동률이 없거나 0이면 방향을 정할 수 없습니다.");
        }
        return changeRate.signum() > 0 ? UP : DOWN;
    }
}
