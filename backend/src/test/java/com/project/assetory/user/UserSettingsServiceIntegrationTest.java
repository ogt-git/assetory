package com.project.assetory.user;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.domain.UserSettings;
import com.project.assetory.user.repository.UserRepository;
import com.project.assetory.user.repository.UserSettingsRepository;
import com.project.assetory.user.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §3.2 user_settings — 가입 시 만들지 않고, 조회할 때 없으면 기본값으로 만든다(getOrCreate, A1 결정 2 · D-B)
class UserSettingsServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired UserSettingsService userSettingsService;
    @Autowired UserSettingsRepository userSettingsRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("설정이 없으면 기본값으로 만든다")
    void createsDefaults() {
        User user = newUser();

        UserSettings settings = userSettingsService.getOrCreate(user.getId());

        assertThat(settings.getUserId()).isEqualTo(user.getId());
        assertThat(settings.isNotificationEnabled()).isTrue();
        assertThat(settings.getQuietHoursStart()).isNull();
        assertThat(settings.getQuietHoursEnd()).isNull();
        assertThat(settings.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(settings.isOnboardingCompleted()).isFalse();
        assertThat(settings.isBackfillCompleted()).isFalse();
        assertThat(settings.getCreatedAt()).isNotNull();
        assertThat(settings.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("두 번 호출해도 1행만 있고 같은 값을 돌려준다")
    void idempotent() {
        User user = newUser();

        UserSettings first = userSettingsService.getOrCreate(user.getId());
        UserSettings second = userSettingsService.getOrCreate(user.getId());

        assertThat(rowCount(user.getId())).isEqualTo(1);
        assertThat(second.getUserId()).isEqualTo(first.getUserId());
        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
    }

    @Test
    @DisplayName("사용자마다 자기 설정 행을 가진다")
    void separatedPerUser() {
        User a = newUser();
        User b = newUser();

        assertThat(userSettingsService.getOrCreate(a.getId()).getUserId()).isEqualTo(a.getId());
        assertThat(userSettingsService.getOrCreate(b.getId()).getUserId()).isEqualTo(b.getId());
        assertThat(rowCount(a.getId())).isEqualTo(1);
        assertThat(rowCount(b.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("호출자 트랜잭션 안에서 이미 있는 행에 다시 넣어도 예외 · rollback-only가 생기지 않는다")
    void duplicateInsertInsideOuterTransaction_noRollbackOnly() {
        User user = newUser();
        userSettingsService.getOrCreate(user.getId());

        Boolean rollbackOnly = transactionTemplate.execute(status -> {
            // 동시 생성 경합과 같은 상황: 다른 쪽이 먼저 행을 만든 뒤 이쪽도 기본값 삽입을 시도
            userSettingsRepository.insertDefaultIfAbsent(user.getId(), UserSettings.DEFAULT_TIMEZONE, LocalDateTime.now());
            userSettingsService.getOrCreate(user.getId());
            return status.isRollbackOnly();
        });

        assertThat(rollbackOnly).isFalse();
        assertThat(rowCount(user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 사용자 id면 FK 위반으로 실패한다 (조용히 넘어가지 않음)")
    void unknownUser_fails() {
        assertThatThrownBy(() -> userSettingsService.getOrCreate(999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("@MapsId 엔티티의 save()는 merge가 아니라 persist로 동작한다 (같은 인스턴스 반환, id = user_id)")
    void mapsIdSaveUsesPersist() {
        Long userId = newUser().getId();

        transactionTemplate.executeWithoutResult(status -> {
            UserSettings settings = UserSettings.create(userRepository.getReferenceById(userId));

            UserSettings saved = userSettingsRepository.save(settings);

            assertThat(saved).isSameAs(settings);
            assertThat(saved.getUserId()).isEqualTo(userId);
        });
        assertThat(rowCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 트랜잭션에서 읽은(detached) User로 save()하면 persist가 거부한다 — 생성은 getOrCreate만 쓴다")
    void mapsIdSaveWithDetachedUser_fails() {
        User detached = newUser();

        assertThatThrownBy(() -> userSettingsRepository.save(UserSettings.create(detached)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("detached entity passed to persist");
        assertThat(rowCount(detached.getId())).isZero();
    }

    private int rowCount(Long userId) {
        Integer count = jdbc.queryForObject("select count(*) from user_settings where user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }
}
