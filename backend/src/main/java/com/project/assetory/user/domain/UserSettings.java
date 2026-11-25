package com.project.assetory.user.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * docs/05 §3.2 user_settings — 사용자당 1행, PK = users.id.
 * 가입 시 만들지 않고 UserSettingsService.getOrCreate가 처음 조회할 때 기본값으로 만든다(이미 가입한 사용자도 같은 경로).
 * backfill_completed = 최초 연동 시 보유 종목 시세 · 과거 이벤트 소급 완료 여부 (갱신은 A5)
 */
@Entity
@Table(name = "user_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSettings extends BaseTimeEntity {

    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "backfill_completed", nullable = false)
    private boolean backfillCompleted;

    /** 과거 이벤트 소급 완료 (FR-DT-08, A5 결정 H-5) — 켜기만 하고 false로 되돌리지 않는다 */
    public void markBackfillCompleted() {
        this.backfillCompleted = true;
    }

    /** 기본값 행 — UserSettingsRepository.insertDefaultIfAbsent와 같은 값이어야 한다 */
    public static UserSettings create(User user) {
        UserSettings settings = new UserSettings();
        settings.user = user;
        settings.notificationEnabled = true;
        settings.timezone = DEFAULT_TIMEZONE;
        settings.onboardingCompleted = false;
        settings.backfillCompleted = false;
        return settings;
    }
}
