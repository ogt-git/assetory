package com.project.assetory.detection.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// docs/05 §8.1 · §8.2 — 적용 임계치 = COALESCE(사용자값, 기본값), 사용자 입력 허용 범위는 min ≤ v ≤ max (경계 포함)
class DetectionRuleTest {

    // 05 §8.1 STOCK_PRICE_CHANGE: 기본 5.0 %, 허용 2 ~ 20
    private static DetectionRule stockRule() {
        return DetectionRule.create("STOCK_PRICE_CHANGE", "종목 급등락", TriggerType.STOCK_CHANGE,
                new BigDecimal("5.0"), "%", new BigDecimal("2"), new BigDecimal("20"),
                3, true, Severity.WARNING, true, true);
    }

    // 05 §8.1 MONTHLY_REPORT: 임계치 · 범위 없음
    private static DetectionRule monthlyRule() {
        return DetectionRule.create("MONTHLY_REPORT", "월간 결산", TriggerType.MONTHLY_REPORT,
                null, null, null, null, null, false, Severity.INFO, false, true);
    }

    @Test
    @DisplayName("사용자값이 없으면 기본값을 적용한다")
    void appliedThreshold_userNull_default() {
        assertThat(stockRule().appliedThreshold(null)).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("사용자값이 있으면 사용자값이 기본값보다 우선한다")
    void appliedThreshold_userValueWins() {
        assertThat(stockRule().appliedThreshold(new BigDecimal("8"))).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("기본값도 사용자값도 없으면 적용 임계치는 null이다 (0으로 채우지 않음)")
    void appliedThreshold_noDefault_null() {
        assertThat(monthlyRule().appliedThreshold(null)).isNull();
    }

    @Test
    @DisplayName("허용 범위 경계값 2와 20은 허용한다")
    void isWithinRange_boundsInclusive() {
        DetectionRule rule = stockRule();
        assertThat(rule.isWithinRange(new BigDecimal("2"))).isTrue();
        assertThat(rule.isWithinRange(new BigDecimal("20"))).isTrue();
        assertThat(rule.isWithinRange(new BigDecimal("2.0000"))).isTrue();
        assertThat(rule.isWithinRange(new BigDecimal("8"))).isTrue();
    }

    @Test
    @DisplayName("허용 범위 밖 1.9999와 20.0001은 거부한다")
    void isWithinRange_outside() {
        DetectionRule rule = stockRule();
        assertThat(rule.isWithinRange(new BigDecimal("1.9999"))).isFalse();
        assertThat(rule.isWithinRange(new BigDecimal("20.0001"))).isFalse();
    }

    @Test
    @DisplayName("허용 범위가 없는 규칙은 어떤 값도 거부한다")
    void isWithinRange_noRange_rejects() {
        assertThat(monthlyRule().isWithinRange(new BigDecimal("5"))).isFalse();
    }

    @Test
    @DisplayName("입력값이 null이면 거부한다 (문서 미정 — 확인 필요, 기본값 되돌리기는 A2에서 별도 처리)")
    void isWithinRange_nullValue_rejects() {
        assertThat(stockRule().isWithinRange(null)).isFalse();
    }
}
