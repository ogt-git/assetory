package com.project.assetory.detection;

import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.TargetScope;
import com.project.assetory.detection.domain.UserDetectionSetting;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.detection.repository.UserDetectionSettingRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §8.2 user_detection_settings — UNIQUE (user_id, detection_rule_id), target_scope JSON, 소유자 기준 조회 (AGENTS 9장)
// 각 repository 호출이 자기 트랜잭션에서 돌므로 재조회는 DB에서 새로 읽는다(JSON 왕복 확인)
class UserDetectionSettingRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired UserDetectionSettingRepository settingRepository;
    @Autowired DetectionRuleRepository ruleRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (사용자, 규칙) 설정을 두 번 저장하면 UNIQUE 위반")
    void duplicateUserRule_rejected() {
        User user = newUser();
        DetectionRule rule = stockRule();
        settingRepository.saveAndFlush(UserDetectionSetting.create(user, rule));

        assertThatThrownBy(() -> settingRepository.saveAndFlush(UserDetectionSetting.create(user, rule)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("새 설정은 수신 켬 · 임계치 NULL(기본값 적용) · 대상 · 채널 NULL")
    void defaults() {
        User user = newUser();
        UserDetectionSetting saved = settingRepository.saveAndFlush(UserDetectionSetting.create(user, stockRule()));

        UserDetectionSetting found = settingRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getThresholdValue()).isNull();
        assertThat(found.getTargetScope()).isNull();
        assertThat(found.getChannelPreference()).isNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("target_scope JSON을 저장한 뒤 다시 읽으면 같은 값")
    void targetScopeJsonRoundTrip() {
        User user = newUser();
        UserDetectionSetting setting = UserDetectionSetting.create(user, stockRule());
        setting.changeTargetScope(new TargetScope(List.of("005930", "000660")));
        UserDetectionSetting saved = settingRepository.saveAndFlush(setting);

        UserDetectionSetting found = settingRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTargetScope()).isEqualTo(new TargetScope(List.of("005930", "000660")));
        assertThat(found.getTargetScope().excludedTickers()).containsExactly("005930", "000660");
        assertThat(found.getChannelPreference()).isNull();
    }

    @Test
    @DisplayName("빈 제외 목록도 그대로 저장된다 (NULL과 구분)")
    void targetScopeEmptyList() {
        User user = newUser();
        UserDetectionSetting setting = UserDetectionSetting.create(user, stockRule());
        setting.changeTargetScope(new TargetScope(List.of()));
        UserDetectionSetting saved = settingRepository.saveAndFlush(setting);

        assertThat(settingRepository.findById(saved.getId()).orElseThrow().getTargetScope().excludedTickers()).isEmpty();
    }

    @Test
    @DisplayName("사용자별 조회에 다른 사용자의 설정이 섞이지 않는다")
    void ownerScopedQueries() {
        User a = newUser();
        User b = newUser();
        DetectionRule stock = stockRule();
        DetectionRule asset = ruleRepository.findByRuleCode("TOTAL_ASSET_CHANGE").orElseThrow();
        UserDetectionSetting aStock = settingRepository.saveAndFlush(UserDetectionSetting.create(a, stock));
        UserDetectionSetting aAsset = settingRepository.saveAndFlush(UserDetectionSetting.create(a, asset));
        UserDetectionSetting bStock = settingRepository.saveAndFlush(UserDetectionSetting.create(b, stock));

        assertThat(settingRepository.findAllByUserId(a.getId())).extracting(UserDetectionSetting::getId)
                .containsExactlyInAnyOrder(aStock.getId(), aAsset.getId());
        assertThat(settingRepository.findAllByUserId(b.getId())).extracting(UserDetectionSetting::getId)
                .containsExactly(bStock.getId());
        assertThat(settingRepository.findByUserIdAndDetectionRuleId(a.getId(), stock.getId()))
                .get().extracting(UserDetectionSetting::getId).isEqualTo(aStock.getId());
        assertThat(settingRepository.findByUserIdAndDetectionRuleId(b.getId(), asset.getId())).isEmpty();
    }

    @Test
    @DisplayName("target_scope · channel_preference는 MySQL json 컬럼으로 만들어진다")
    void jsonColumnTypes() {
        List<String> types = jdbc.queryForList("""
                select data_type from information_schema.columns
                where table_schema = database() and table_name = 'user_detection_settings'
                  and column_name in ('target_scope', 'channel_preference')""", String.class);

        assertThat(types).containsExactly("json", "json");
    }

    private DetectionRule stockRule() {
        return ruleRepository.findByRuleCode("STOCK_PRICE_CHANGE").orElseThrow();
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }
}
