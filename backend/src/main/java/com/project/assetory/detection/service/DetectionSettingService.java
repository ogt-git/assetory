package com.project.assetory.detection.service;

import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.TargetScope;
import com.project.assetory.detection.domain.UserDetectionSetting;
import com.project.assetory.detection.dto.DetectionSettingResponse;
import com.project.assetory.detection.dto.DetectionSettingUpdateRequest;
import com.project.assetory.detection.exception.DetectionRuleNotFoundException;
import com.project.assetory.detection.exception.DetectionSettingInvalidException;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.detection.repository.UserDetectionSettingRepository;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 감지 설정 (FR-DT-02 · UC-07) — 시스템 기본값(detection_rules) 위에 사용자 오버라이드(user_detection_settings).
 * - 목록: 활성 규칙 전체(id 순) + 내 설정. 쿼리 2회(규칙 · 내 설정)
 * - 저장: 전체 교체. 검증을 모두 통과한 뒤에만 행을 만들거나 바꾼다(거부된 요청은 기존 값에 영향 없음)
 * - 삭제: 기본값으로 되돌리기 = 행 삭제 (05 §8.2). 행이 없어도 성공
 * 요청 형식(종목코드 형식 · 개수 상한 · 소수 자리)은 DTO 제약으로 먼저 걸러진다.
 */
@Service
@RequiredArgsConstructor
public class DetectionSettingService {

    private static final String UNIT_DAYS = "일";

    private final DetectionRuleRepository detectionRuleRepository;
    private final UserDetectionSettingRepository userDetectionSettingRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<DetectionSettingResponse> getSettings(Long userId) {
        List<DetectionRule> rules = detectionRuleRepository.findAllByActiveTrueOrderById();
        // 설정 행의 규칙 id는 LAZY 프록시에서 초기화 없이 읽는다 (N+1 없음)
        Map<Long, UserDetectionSetting> byRuleId = userDetectionSettingRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(s -> s.getDetectionRule().getId(), Function.identity()));
        return rules.stream()
                .map(rule -> DetectionSettingResponse.of(rule, byRuleId.get(rule.getId())))
                .toList();
    }

    @Transactional
    public DetectionSettingResponse update(Long userId, String ruleCode, DetectionSettingUpdateRequest request) {
        DetectionRule rule = findActiveRule(ruleCode);
        BigDecimal threshold = request.thresholdValue();
        validateThreshold(rule, threshold);
        TargetScope targetScope = toTargetScope(rule, request.excludedTickers());

        UserDetectionSetting setting = userDetectionSettingRepository
                .findByUserIdAndDetectionRuleId(userId, rule.getId())
                .orElse(null);
        if (setting == null) {
            setting = UserDetectionSetting.create(userRepository.getReferenceById(userId), rule);
            setting.update(request.enabled(), threshold, targetScope);
            // 처음 저장이 동시에 두 번 오면 UNIQUE 위반 → 409 DUPLICATE_DATA (A2 결정 6, 업서트 없음)
            userDetectionSettingRepository.save(setting);
        } else {
            setting.update(request.enabled(), threshold, targetScope);
        }
        return DetectionSettingResponse.of(rule, setting);
    }

    @Transactional
    public DetectionSettingResponse reset(Long userId, String ruleCode) {
        DetectionRule rule = findActiveRule(ruleCode);
        userDetectionSettingRepository.findByUserIdAndDetectionRuleId(userId, rule.getId())
                .ifPresent(userDetectionSettingRepository::delete);
        return DetectionSettingResponse.of(rule, null);
    }

    private DetectionRule findActiveRule(String ruleCode) {
        return detectionRuleRepository.findByRuleCode(ruleCode)
                .filter(DetectionRule::isActive)
                .orElseThrow(DetectionRuleNotFoundException::new);
    }

    private void validateThreshold(DetectionRule rule, BigDecimal threshold) {
        if (threshold == null) {
            return;
        }
        if (!rule.isUserConfigurable()) {
            throw new DetectionSettingInvalidException(ErrorCode.THRESHOLD_NOT_CONFIGURABLE);
        }
        boolean fractionalDays = UNIT_DAYS.equals(rule.getUnit()) && threshold.stripTrailingZeros().scale() > 0;
        if (!rule.isWithinRange(threshold) || fractionalDays) {
            throw new DetectionSettingInvalidException(ErrorCode.THRESHOLD_OUT_OF_RANGE, rangeData(rule));
        }
    }

    // null · 빈 목록 = 제외 없음(target_scope NULL). 값이 있으면 종목 급등락 규칙만, 중복 제거(입력 순서 유지)
    private TargetScope toTargetScope(DetectionRule rule, List<String> excludedTickers) {
        if (excludedTickers == null || excludedTickers.isEmpty()) {
            return null;
        }
        // 제외 종목(target_scope)을 받는 유일한 규칙 (A2 결정 3)
        if (!DetectionRule.STOCK_PRICE_CHANGE.equals(rule.getRuleCode())) {
            throw new DetectionSettingInvalidException(ErrorCode.TARGET_SCOPE_NOT_SUPPORTED);
        }
        return new TargetScope(List.copyOf(new LinkedHashSet<>(excludedTickers)));
    }

    private static Map<String, Object> rangeData(DetectionRule rule) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minValue", rule.getMinValue());
        data.put("maxValue", rule.getMaxValue());
        data.put("unit", rule.getUnit());
        return data;
    }
}
