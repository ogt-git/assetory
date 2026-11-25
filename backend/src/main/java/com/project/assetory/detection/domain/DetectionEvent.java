package com.project.assetory.detection.domain;

import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.global.entity.BaseTimeEntity;
import com.project.assetory.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * docs/05 §8.3 detection_events — 감지 기록, 알림 파이프라인(12주차)의 입력. UNIQUE (user_id, event_key) = 감지 멱등성 (FR-DT-06).
 * - user_market_event: 급등락일 때만 연결(NULL 허용). 1:0..1은 (user, event_key) · (user, analysis) UNIQUE로 보장된다
 * - severity: 감지 당시 규칙의 값을 복사해 넘긴다 · applied_threshold: 감지 당시 실제 적용값 (FR-DT-07)
 * - payload: 유형별 근거 수치. 급등락은 {ticker, stockName, changeRate, direction, eventDate}
 * - detected_at: 감지 기준 시각(배치 기준 시각 T). created_at(저장 시각)과 다르다
 * 소급 이벤트(FR-DT-08)는 이 행을 만들지 않는다 (10주차 결정 D3).
 */
// 감지 기록은 만든 뒤 바꾸지 않는다. @Immutable이 없으면 JSON payload(Map)를 flush마다 "변경됨"으로 판정해
// 불필요한 UPDATE가 나간다(메모리 BigDecimal ≠ JSON 역직렬화 값) — A4 쿼리 수 테스트에서 발견
@Immutable
@Entity
@Table(name = "detection_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_detection_events_user_key", columnNames = {"user_id", "event_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectionEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "detection_rule_id", nullable = false)
    private DetectionRule detectionRule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_market_event_id")
    private UserMarketEvent userMarketEvent;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(name = "applied_threshold", precision = 10, scale = 4)
    private BigDecimal appliedThreshold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    public static DetectionEvent create(User user, DetectionRule detectionRule, UserMarketEvent userMarketEvent,
                                        String eventKey, Severity severity, BigDecimal appliedThreshold,
                                        Map<String, Object> payload, LocalDateTime detectedAt) {
        DetectionEvent event = new DetectionEvent();
        event.user = user;
        event.detectionRule = detectionRule;
        event.userMarketEvent = userMarketEvent;
        event.eventKey = eventKey;
        event.severity = severity;
        event.appliedThreshold = appliedThreshold;
        event.payload = payload;
        event.detectedAt = detectedAt;
        return event;
    }
}
