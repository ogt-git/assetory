package com.project.assetory.detection.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * 종목 급등락 감지에 실제로 적용할 설정 — 실시간 감지(A4) · 과거 이벤트 소급(A5)이 같은 규칙을 쓴다.
 * - 수신 끔(enabled=false) → 감지하지 않음 (A4 결정 E-4)
 * - 적용 임계치 = COALESCE(내 값, 규칙 기본값) (05 §8.2, FR-DT-07). 둘 다 없으면 감지하지 않음
 * - 제외 종목 = target_scope.excludedTickers (없으면 빈 집합)
 *
 * @param threshold       적용 임계치 (퍼센트)
 * @param excludedTickers 감지에서 뺄 종목코드
 */
public record StockDetectionSetting(BigDecimal threshold, Set<String> excludedTickers) {

    /** 감지할 수 없으면(수신 끔 · 임계치 없음) empty */
    public static Optional<StockDetectionSetting> resolve(DetectionRule rule, Optional<UserDetectionSetting> setting) {
        if (setting.isPresent() && !setting.get().isEnabled()) {
            return Optional.empty();
        }
        BigDecimal threshold = rule.appliedThreshold(setting.map(UserDetectionSetting::getThresholdValue).orElse(null));
        if (threshold == null) {
            return Optional.empty();
        }
        Set<String> excluded = setting.map(UserDetectionSetting::getTargetScope)
                .map(scope -> scope.excludedTickers() == null ? Set.<String>of() : Set.copyOf(scope.excludedTickers()))
                .orElse(Set.of());
        return Optional.of(new StockDetectionSetting(threshold, excluded));
    }
}
