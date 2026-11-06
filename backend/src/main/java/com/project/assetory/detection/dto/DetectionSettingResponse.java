package com.project.assetory.detection.dto;

import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.TriggerType;
import com.project.assetory.detection.domain.UserDetectionSetting;

import java.math.BigDecimal;
import java.util.List;

/**
 * 규칙 하나의 감지 설정 (FR-DT-02 · UC-07 2) — 규칙 정보 + 내 설정 + 실제 적용값. S-09 화면을 이 응답만으로 그린다
 *
 * @param thresholdValue    내 임계치 — null이면 기본값 사용
 * @param appliedThreshold  감지에 쓰이는 값 = COALESCE(내 값, 기본값). 임계치가 없는 규칙은 null
 * @param enabled           수신 여부 — 내 설정이 없으면 true
 * @param excludedTickers   제외 종목 — 없으면 빈 목록
 * @param customized        내 설정 행이 있는지 (false = 모두 기본값)
 */
public record DetectionSettingResponse(
        String ruleCode,
        String name,
        TriggerType triggerType,
        String unit,
        BigDecimal defaultThreshold,
        BigDecimal minValue,
        BigDecimal maxValue,
        boolean userConfigurable,
        BigDecimal thresholdValue,
        BigDecimal appliedThreshold,
        boolean enabled,
        List<String> excludedTickers,
        boolean customized
) {

    /** setting이 null이면 기본값 응답 */
    public static DetectionSettingResponse of(DetectionRule rule, UserDetectionSetting setting) {
        BigDecimal userValue = setting == null ? null : setting.getThresholdValue();
        List<String> excluded = setting == null || setting.getTargetScope() == null
                || setting.getTargetScope().excludedTickers() == null
                ? List.of() : List.copyOf(setting.getTargetScope().excludedTickers());
        return new DetectionSettingResponse(
                rule.getRuleCode(), rule.getName(), rule.getTriggerType(), rule.getUnit(),
                rule.getDefaultThreshold(), rule.getMinValue(), rule.getMaxValue(), rule.isUserConfigurable(),
                userValue, rule.appliedThreshold(userValue),
                setting == null || setting.isEnabled(),
                excluded,
                setting != null);
    }
}
