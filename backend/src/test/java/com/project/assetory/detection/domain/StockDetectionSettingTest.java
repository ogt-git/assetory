package com.project.assetory.detection.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 실시간 감지(A4) · 소급(A5)이 함께 쓰는 급등락 설정 해석 — 수신 끔이면 감지 없음, 적용 임계치 = COALESCE(내 값, 기본값), 제외 종목
class StockDetectionSettingTest {

    private static DetectionRule rule() {
        return DetectionRule.create(DetectionRule.STOCK_PRICE_CHANGE, "종목 급등락", TriggerType.STOCK_CHANGE,
                new BigDecimal("5"), "%", new BigDecimal("2"), new BigDecimal("20"), 3, true, Severity.WARNING, true, true);
    }

    private static UserDetectionSetting setting(boolean enabled, String threshold, List<String> excluded) {
        UserDetectionSetting s = UserDetectionSetting.create(null, rule());
        s.update(enabled, threshold == null ? null : new BigDecimal(threshold),
                excluded == null ? null : new TargetScope(excluded));
        return s;
    }

    @Test
    @DisplayName("내 설정이 없으면 기본 임계치 · 제외 없음")
    void noSetting_default() {
        StockDetectionSetting resolved = StockDetectionSetting.resolve(rule(), Optional.empty()).orElseThrow();

        assertThat(resolved.threshold()).isEqualByComparingTo("5");
        assertThat(resolved.excludedTickers()).isEmpty();
    }

    @Test
    @DisplayName("내 임계치 · 제외 종목을 적용한다")
    void userSetting_applied() {
        StockDetectionSetting resolved = StockDetectionSetting.resolve(rule(),
                Optional.of(setting(true, "8", List.of("005930")))).orElseThrow();

        assertThat(resolved.threshold()).isEqualByComparingTo("8");
        assertThat(resolved.excludedTickers()).containsExactly("005930");
    }

    @Test
    @DisplayName("수신을 끄면 감지하지 않는다 (empty)")
    void disabled_empty() {
        assertThat(StockDetectionSetting.resolve(rule(), Optional.of(setting(false, null, null)))).isEmpty();
    }

    @Test
    @DisplayName("임계치가 없는 규칙이면 감지하지 않는다 (empty)")
    void noThreshold_empty() {
        DetectionRule noThreshold = DetectionRule.create("X", "x", TriggerType.STOCK_CHANGE, null, null, null, null,
                null, false, Severity.INFO, false, true);
        assertThat(StockDetectionSetting.resolve(noThreshold, Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("임계치 비교: |변동률| ≥ 임계치 (경계 포함, 상승 · 하락), null은 false")
    void exceeds() {
        BigDecimal five = new BigDecimal("5");
        assertThat(StockEventCalculator.exceeds(new BigDecimal("5.0000"), five)).isTrue();
        assertThat(StockEventCalculator.exceeds(new BigDecimal("-5"), five)).isTrue();
        assertThat(StockEventCalculator.exceeds(new BigDecimal("4.9999"), five)).isFalse();
        assertThat(StockEventCalculator.exceeds(null, five)).isFalse();
        assertThat(StockEventCalculator.exceeds(five, null)).isFalse();
    }
}
