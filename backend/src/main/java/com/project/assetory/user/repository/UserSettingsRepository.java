package com.project.assetory.user.repository;

import com.project.assetory.user.domain.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    /**
     * 기본값 행을 넣고, 이미 있으면 아무것도 바꾸지 않는다(MySQL 업서트). 중복이어도 예외가 나지 않아
     * 호출자 트랜잭션이 rollback-only가 되지 않는다. 없는 user_id는 FK 위반으로 실패한다(INSERT IGNORE처럼 숨기지 않음).
     * 기본값은 UserSettings.create와 같다. 시각은 JVM(Asia/Seoul) 기준 값을 넘긴다 — DB NOW()는 서버 시간대를 따르므로 쓰지 않는다
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_settings (user_id, notification_enabled, timezone, onboarding_completed, backfill_completed,
                                       created_at, updated_at)
            VALUES (:userId, true, :timezone, false, false, :now, :now)
            ON DUPLICATE KEY UPDATE user_id = user_id""", nativeQuery = true)
    int insertDefaultIfAbsent(@Param("userId") Long userId, @Param("timezone") String timezone,
                              @Param("now") LocalDateTime now);
}
