package com.project.assetory.analysis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 급등락 방향 = 전 거래일 대비 변동률의 부호 (FR-DT-03 상승 · 하락 모두, A3 결정 5)
class DirectionTest {

    @Test
    @DisplayName("양수 변동률은 UP")
    void positive_up() {
        assertThat(Direction.of(new BigDecimal("5.0001"))).isEqualTo(Direction.UP);
        assertThat(Direction.of(new BigDecimal("0.0001"))).isEqualTo(Direction.UP);
    }

    @Test
    @DisplayName("음수 변동률은 DOWN")
    void negative_down() {
        assertThat(Direction.of(new BigDecimal("-7.25"))).isEqualTo(Direction.DOWN);
        assertThat(Direction.of(new BigDecimal("-0.0001"))).isEqualTo(Direction.DOWN);
    }

    @Test
    @DisplayName("0은 방향이 없어 거부한다 (0.0000 포함 — 임계치 최소 2%라 이벤트가 될 수 없음)")
    void zero_rejected() {
        assertThatThrownBy(() -> Direction.of(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Direction.of(new BigDecimal("0.0000"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null은 거부한다 (0으로 보지 않음)")
    void null_rejected() {
        assertThatThrownBy(() -> Direction.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
