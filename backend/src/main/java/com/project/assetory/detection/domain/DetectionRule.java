package com.project.assetory.detection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * docs/05 §8.1 detection_rules — 감지 조건 · 기본 임계치 · 허용 범위 · 발송 상한 (FR-DT-01).
 * 시스템 기본값이며 원천은 resources/data.sql(id 고정, 기동마다 덮어씀). 사용자 오버라이드는 user_detection_settings.
 * 05 §8.1에 시각 컬럼이 없고 시드가 원천이라 BaseTimeEntity를 상속하지 않는다(institutions와 같은 방식).
 * user_configurable = 사용자가 임계치를 바꿀 수 있는지. 수신 끄기(enabled)는 모든 규칙에서 가능하다(UC-07 3b).
 */
@Entity
@Table(name = "detection_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectionRule {

    // 종목 급등락 규칙 코드 (data.sql id 1) — 감지(A4) · 소급(A5) · 제외 종목 설정(A2)이 함께 쓴다
    public static final String STOCK_PRICE_CHANGE = "STOCK_PRICE_CHANGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 50)
    private String ruleCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType triggerType;

    @Column(name = "default_threshold", precision = 10, scale = 4)
    private BigDecimal defaultThreshold;

    // `%` · `일`, 임계치가 없는 규칙은 NULL
    @Column(length = 20)
    private String unit;

    @Column(name = "min_value", precision = 10, scale = 4)
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 10, scale = 4)
    private BigDecimal maxValue;

    @Column(name = "individual_limit_per_day")
    private Integer individualLimitPerDay;

    @Column(name = "bundle_overflow", nullable = false)
    private boolean bundleOverflow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(name = "user_configurable", nullable = false)
    private boolean userConfigurable;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public static DetectionRule create(String ruleCode, String name, TriggerType triggerType,
                                       BigDecimal defaultThreshold, String unit,
                                       BigDecimal minValue, BigDecimal maxValue,
                                       Integer individualLimitPerDay, boolean bundleOverflow,
                                       Severity severity, boolean userConfigurable, boolean active) {
        DetectionRule rule = new DetectionRule();
        rule.ruleCode = ruleCode;
        rule.name = name;
        rule.triggerType = triggerType;
        rule.defaultThreshold = defaultThreshold;
        rule.unit = unit;
        rule.minValue = minValue;
        rule.maxValue = maxValue;
        rule.individualLimitPerDay = individualLimitPerDay;
        rule.bundleOverflow = bundleOverflow;
        rule.severity = severity;
        rule.userConfigurable = userConfigurable;
        rule.active = active;
        return rule;
    }

    /** 적용 임계치 = COALESCE(사용자값, 기본값) (05 §8.2). 둘 다 없으면 null — 0으로 채우지 않는다 */
    public BigDecimal appliedThreshold(BigDecimal userValue) {
        return userValue != null ? userValue : defaultThreshold;
    }

    /**
     * 사용자 입력 허용 범위 min ≤ value ≤ max (경계 포함). 범위가 없는 규칙은 false.
     * value가 null이면 false — 문서 미정(확인 필요). '기본값으로 되돌리기'는 설정 삭제로 따로 처리한다(05 §8.2)
     */
    public boolean isWithinRange(BigDecimal value) {
        if (value == null || minValue == null || maxValue == null) {
            return false;
        }
        return value.compareTo(minValue) >= 0 && value.compareTo(maxValue) <= 0;
    }
}
