package com.project.assetory.detection.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import com.project.assetory.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

/**
 * docs/05 §8.2 user_detection_settings — 규칙별 사용자 오버라이드 (FR-DT-02). UNIQUE (user_id, detection_rule_id).
 * - threshold_value NULL = 규칙 기본값 적용 (적용값 = DetectionRule.appliedThreshold)
 * - '기본값으로 되돌리기' = 행 삭제 (05 §8.2)
 * - channel_preference는 12주차 알림 채널 작업 전까지 항상 NULL
 */
@Entity
@Table(name = "user_detection_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_detection_settings_user_rule",
                columnNames = {"user_id", "detection_rule_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDetectionSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "detection_rule_id", nullable = false)
    private DetectionRule detectionRule;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "threshold_value", precision = 10, scale = 4)
    private BigDecimal thresholdValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_scope")
    private TargetScope targetScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_preference")
    private Map<String, Object> channelPreference;

    public static UserDetectionSetting create(User user, DetectionRule detectionRule) {
        UserDetectionSetting setting = new UserDetectionSetting();
        setting.user = user;
        setting.detectionRule = detectionRule;
        setting.enabled = true;
        return setting;
    }

    public void changeTargetScope(TargetScope targetScope) {
        this.targetScope = targetScope;
    }

    /** 전체 교체 (PUT) — 검증은 호출 전에 끝낸다. thresholdValue · targetScope null = 기본값 · 제외 없음 */
    public void update(boolean enabled, BigDecimal thresholdValue, TargetScope targetScope) {
        this.enabled = enabled;
        this.thresholdValue = thresholdValue;
        this.targetScope = targetScope;
    }
}
