package com.project.assetory.detection.service;

import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.Severity;
import com.project.assetory.detection.domain.TriggerType;
import com.project.assetory.detection.dto.DetectionSettingResponse;
import com.project.assetory.detection.dto.DetectionSettingUpdateRequest;
import com.project.assetory.detection.exception.DetectionRuleNotFoundException;
import com.project.assetory.detection.exception.DetectionSettingInvalidException;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.detection.repository.UserDetectionSettingRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// FR-DT-02 · UC-07 — 사용자 임계치 · 수신 여부 · 제외 종목 설정 (A2). 요청 형식 검증(@Valid)은 Validator로 직접 확인한다
class DetectionSettingServiceIntegrationTest extends IntegrationTestSupport {

    private static final String STOCK = "STOCK_PRICE_CHANGE";
    private static final String ASSET = "TOTAL_ASSET_CHANGE";
    private static final String MATURITY = "DEPOSIT_MATURITY";
    private static final String MONTHLY = "MONTHLY_REPORT";

    @Autowired DetectionSettingService service;
    @Autowired UserDetectionSettingRepository settingRepository;
    @Autowired DetectionRuleRepository ruleRepository;
    @Autowired UserRepository userRepository;
    @Autowired Validator validator;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = newUser();
    }

    // ── 조회 ──

    @Test
    @DisplayName("설정이 없으면 활성 규칙 6개를 id 순으로 기본값 · customized=false로 돌려준다")
    void list_defaults() {
        List<DetectionSettingResponse> list = service.getSettings(userId);

        assertThat(list).extracting(DetectionSettingResponse::ruleCode).containsExactly(
                STOCK, ASSET, MATURITY, "DAILY_BRIEFING", "GOAL_PROGRESS_WEEKLY", MONTHLY);
        assertThat(list).allSatisfy(s -> {
            assertThat(s.customized()).isFalse();
            assertThat(s.enabled()).isTrue();
            assertThat(s.thresholdValue()).isNull();
            assertThat(s.excludedTickers()).isEmpty();
        });
        DetectionSettingResponse stock = list.get(0);
        assertThat(stock.name()).isEqualTo("종목 급등락");
        assertThat(stock.triggerType()).isEqualTo(TriggerType.STOCK_CHANGE);
        assertThat(stock.unit()).isEqualTo("%");
        assertThat(stock.defaultThreshold()).isEqualByComparingTo("5");
        assertThat(stock.appliedThreshold()).isEqualByComparingTo("5");
        assertThat(stock.minValue()).isEqualByComparingTo("2");
        assertThat(stock.maxValue()).isEqualByComparingTo("20");
        assertThat(stock.userConfigurable()).isTrue();
        assertThat(list.get(5).appliedThreshold()).isNull();
        assertThat(list.get(5).userConfigurable()).isFalse();
    }

    @Test
    @DisplayName("목록 조회는 규칙 수와 관계없이 쿼리 2회 (규칙 · 내 설정, N+1 없음)")
    void list_queryCount() {
        service.update(userId, STOCK, request(true, "8", null));
        service.update(userId, ASSET, request(false, null, null));
        service.update(userId, MONTHLY, request(false, null, null));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        List<DetectionSettingResponse> list;
        try {
            list = service.getSettings(userId);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(list).filteredOn(DetectionSettingResponse::customized).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("비활성 규칙은 목록에 없고, 설정 · 삭제 요청은 NOT_FOUND")
    void inactiveRule_hiddenAndNotFound() {
        DetectionRule inactive = ruleRepository.save(DetectionRule.create("TEST_INACTIVE_RULE", "테스트 전용 비활성 규칙",
                TriggerType.STOCK_CHANGE, new BigDecimal("5"), "%", new BigDecimal("2"), new BigDecimal("20"),
                null, false, Severity.INFO, true, false));
        try {
            assertThat(service.getSettings(userId)).extracting(DetectionSettingResponse::ruleCode)
                    .doesNotContain("TEST_INACTIVE_RULE").hasSize(6);
            assertThatThrownBy(() -> service.update(userId, "TEST_INACTIVE_RULE", request(true, "8", null)))
                    .isInstanceOf(DetectionRuleNotFoundException.class);
            assertThatThrownBy(() -> service.reset(userId, "TEST_INACTIVE_RULE"))
                    .isInstanceOf(DetectionRuleNotFoundException.class);
        } finally {
            // 시드 6행을 고정하는 다른 테스트와 같은 DB를 쓰므로 바로 지운다
            ruleRepository.delete(inactive);
        }
    }

    @Test
    @DisplayName("없는 ruleCode는 NOT_FOUND")
    void unknownRule_notFound() {
        assertThatThrownBy(() -> service.update(userId, "NO_SUCH_RULE", request(true, null, null)))
                .isInstanceOf(DetectionRuleNotFoundException.class);
        assertThatThrownBy(() -> service.reset(userId, "NO_SUCH_RULE"))
                .isInstanceOf(DetectionRuleNotFoundException.class);
    }

    // ── 저장 ──

    @Test
    @DisplayName("8% 저장 → 적용 임계치 8, customized=true, 목록에도 반영")
    void update_threshold() {
        DetectionSettingResponse saved = service.update(userId, STOCK, request(true, "8", null));

        assertThat(saved.thresholdValue()).isEqualByComparingTo("8");
        assertThat(saved.appliedThreshold()).isEqualByComparingTo("8");
        assertThat(saved.customized()).isTrue();
        assertThat(saved.enabled()).isTrue();
        assertThat(find(STOCK).appliedThreshold()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("다시 저장하면 전체 교체 — 행은 1개, 값은 새 요청 기준")
    void update_replacesWhole() {
        service.update(userId, STOCK, request(true, "8", List.of("005930")));

        DetectionSettingResponse replaced = service.update(userId, STOCK, request(false, null, null));

        assertThat(replaced.enabled()).isFalse();
        assertThat(replaced.thresholdValue()).isNull();
        assertThat(replaced.appliedThreshold()).isEqualByComparingTo("5");
        assertThat(replaced.excludedTickers()).isEmpty();
        assertThat(settingRepository.findAllByUserId(userId)).hasSize(1);
    }

    @Test
    @DisplayName("허용 범위 경계 2와 20은 저장된다")
    void update_boundsInclusive() {
        assertThat(service.update(userId, STOCK, request(true, "2", null)).appliedThreshold()).isEqualByComparingTo("2");
        assertThat(service.update(userId, STOCK, request(true, "20", null)).appliedThreshold()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("1.9999 · 20.0001은 범위 밖으로 거부되고 기존 값이 그대로 남는다 (범위 data 포함)")
    void update_outOfRange_keepsPrevious() {
        service.update(userId, STOCK, request(true, "8", List.of("005930")));

        for (String value : List.of("1.9999", "20.0001")) {
            assertThatThrownBy(() -> service.update(userId, STOCK, request(false, value, null)))
                    .isInstanceOfSatisfying(DetectionSettingInvalidException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.THRESHOLD_OUT_OF_RANGE);
                        assertThat(e.getData()).containsEntry("unit", "%");
                        assertThat((BigDecimal) e.getData().get("minValue")).isEqualByComparingTo("2");
                        assertThat((BigDecimal) e.getData().get("maxValue")).isEqualByComparingTo("20");
                    });
        }

        DetectionSettingResponse kept = find(STOCK);
        assertThat(kept.appliedThreshold()).isEqualByComparingTo("8");
        assertThat(kept.enabled()).isTrue();
        assertThat(kept.excludedTickers()).containsExactly("005930");
    }

    @Test
    @DisplayName("범위 밖 값으로 처음 저장을 시도하면 행이 만들어지지 않는다")
    void update_outOfRange_noRowCreated() {
        assertThatThrownBy(() -> service.update(userId, STOCK, request(true, "21", null)))
                .isInstanceOf(DetectionSettingInvalidException.class);

        assertThat(settingRepository.findAllByUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("'일' 단위 규칙은 정수만 — 30.5는 거부(범위 data 포함), 7과 7.0000은 허용")
    void update_daysMustBeInteger() {
        assertThatThrownBy(() -> service.update(userId, MATURITY, request(true, "30.5", null)))
                .isInstanceOfSatisfying(DetectionSettingInvalidException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.THRESHOLD_OUT_OF_RANGE);
                    assertThat(e.getData()).containsEntry("unit", "일");
                });

        assertThat(service.update(userId, MATURITY, request(true, "7", null)).appliedThreshold()).isEqualByComparingTo("7");
        assertThat(service.update(userId, MATURITY, request(true, "7.0000", null)).appliedThreshold()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("임계치를 바꿀 수 없는 규칙에 임계치를 넣으면 거부")
    void update_notConfigurable() {
        assertThatThrownBy(() -> service.update(userId, MONTHLY, request(true, "5", null)))
                .isInstanceOfSatisfying(DetectionSettingInvalidException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.THRESHOLD_NOT_CONFIGURABLE));
        assertThat(settingRepository.findAllByUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("수신만 끄기 — 임계치 조정 불가 규칙에서도 동작, 적용 임계치는 기본값 유지 (UC-07 3b)")
    void update_disableOnly() {
        DetectionSettingResponse monthly = service.update(userId, MONTHLY, request(false, null, null));
        DetectionSettingResponse stock = service.update(userId, STOCK, request(false, null, null));

        assertThat(monthly.enabled()).isFalse();
        assertThat(monthly.customized()).isTrue();
        assertThat(stock.enabled()).isFalse();
        assertThat(stock.appliedThreshold()).isEqualByComparingTo("5");
    }

    // ── 제외 종목 ──

    @Test
    @DisplayName("제외 종목은 중복을 지우고 입력 순서대로 저장한다")
    void excludedTickers_deduplicated() {
        DetectionSettingResponse saved = service.update(userId, STOCK,
                request(true, null, List.of("005930", "000660", "005930")));

        assertThat(saved.excludedTickers()).containsExactly("005930", "000660");
        assertThat(find(STOCK).excludedTickers()).containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("제외 종목 null과 빈 목록은 모두 '제외 없음'으로 저장된다 (target_scope NULL)")
    void excludedTickers_nullAndEmptyMeanNone() {
        service.update(userId, STOCK, request(true, null, List.of()));
        assertThat(settingRepository.findAllByUserId(userId).get(0).getTargetScope()).isNull();

        service.update(userId, STOCK, request(true, null, null));
        assertThat(settingRepository.findAllByUserId(userId).get(0).getTargetScope()).isNull();
        assertThat(find(STOCK).excludedTickers()).isEmpty();
    }

    @Test
    @DisplayName("종목 급등락 외 규칙에 제외 종목이 있으면 거부, 빈 목록은 허용")
    void excludedTickers_onlyStockRule() {
        assertThatThrownBy(() -> service.update(userId, ASSET, request(true, null, List.of("005930"))))
                .isInstanceOfSatisfying(DetectionSettingInvalidException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TARGET_SCOPE_NOT_SUPPORTED));

        assertThat(service.update(userId, ASSET, request(true, null, List.of())).excludedTickers()).isEmpty();
    }

    @Test
    @DisplayName("요청 형식: 종목코드는 대문자 영숫자 6자리만, null 원소 거부")
    void request_tickerFormat() {
        assertThat(violations(request(true, null, List.of("005930", "0000J0")))).isEmpty();
        for (String bad : List.of("00593", "0059301", "abcdef", "00593A ", "005-30")) {
            assertThat(violations(request(true, null, List.of(bad)))).as(bad).isNotEmpty();
        }
        assertThat(violations(request(true, null, Arrays.asList("005930", null)))).isNotEmpty();
    }

    @Test
    @DisplayName("요청 형식: 제외 종목은 설정 상한(기본 50개)까지만")
    void request_maxExcludedTickers() {
        assertThat(violations(request(true, null, tickers(50)))).isEmpty();
        assertThat(violations(request(true, null, tickers(51)))).isNotEmpty();
    }

    @Test
    @DisplayName("요청 형식: 수신 여부 필수, 임계치는 소수 넷째 자리까지")
    void request_enabledAndDigits() {
        assertThat(violations(new DetectionSettingUpdateRequest(null, null, null))).isNotEmpty();
        assertThat(violations(request(true, "8.1234", null))).isEmpty();
        assertThat(violations(request(true, "8.12345", null))).isNotEmpty();
    }

    // ── 삭제 ──

    @Test
    @DisplayName("삭제하면 행이 사라지고 기본값을 돌려준다, 없는 행을 삭제해도 성공 (UC-07 3c)")
    void reset_restoresDefault() {
        service.update(userId, STOCK, request(false, "8", List.of("005930")));

        DetectionSettingResponse reset = service.reset(userId, STOCK);

        assertThat(reset.customized()).isFalse();
        assertThat(reset.enabled()).isTrue();
        assertThat(reset.thresholdValue()).isNull();
        assertThat(reset.appliedThreshold()).isEqualByComparingTo("5");
        assertThat(reset.excludedTickers()).isEmpty();
        assertThat(settingRepository.findAllByUserId(userId)).isEmpty();

        assertThat(service.reset(userId, STOCK).customized()).isFalse();
    }

    // ── 사용자 분리 ──

    @Test
    @DisplayName("다른 사용자의 설정은 바뀌지 않고 조회에 섞이지 않는다")
    void otherUserUnaffected() {
        Long other = newUser();
        service.update(other, STOCK, request(true, "12", List.of("000660")));

        service.update(userId, STOCK, request(false, "8", null));
        service.reset(userId, STOCK);

        DetectionSettingResponse othersStock = service.getSettings(other).get(0);
        assertThat(othersStock.appliedThreshold()).isEqualByComparingTo("12");
        assertThat(othersStock.excludedTickers()).containsExactly("000660");
        assertThat(service.getSettings(userId).get(0).customized()).isFalse();
    }

    private DetectionSettingResponse find(String ruleCode) {
        return service.getSettings(userId).stream().filter(s -> s.ruleCode().equals(ruleCode)).findFirst().orElseThrow();
    }

    private static DetectionSettingUpdateRequest request(boolean enabled, String threshold, List<String> tickers) {
        return new DetectionSettingUpdateRequest(enabled, threshold == null ? null : new BigDecimal(threshold), tickers);
    }

    private static List<String> tickers(int count) {
        List<String> list = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> list.add(String.format("%06d", i)));
        return list;
    }

    private Set<ConstraintViolation<DetectionSettingUpdateRequest>> violations(DetectionSettingUpdateRequest request) {
        return validator.validate(request);
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private Long newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10")).getId();
    }
}
