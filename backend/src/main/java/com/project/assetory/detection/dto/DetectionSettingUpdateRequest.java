package com.project.assetory.detection.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * 감지 설정 저장 (FR-DT-02 · UC-07) — 전체 교체. 허용 범위 · 조정 가능 여부 · 대상 규칙은 서비스에서 검증한다
 *
 * @param enabled          수신 여부 (UC-07 3b — 끄기 = false)
 * @param thresholdValue   내 임계치. null이면 규칙 기본값 사용 (DECIMAL(10,4))
 * @param excludedTickers  감지에서 뺄 종목코드 — 종목 급등락 규칙만. null · 빈 목록 = 제외 없음, 중복은 서비스에서 제거
 */
public record DetectionSettingUpdateRequest(

        @NotNull(message = "수신 여부는 필수입니다.")
        Boolean enabled,

        @Digits(integer = 6, fraction = 4, message = "기준값은 소수점 넷째 자리까지 입력할 수 있습니다.")
        BigDecimal thresholdValue,

        @MaxExcludedTickers
        List<@NotNull(message = "종목코드는 비울 수 없습니다.")
             @Pattern(regexp = "^[0-9A-Z]{6}$", message = "종목코드는 영문 대문자 · 숫자 6자리입니다.") String> excludedTickers
) {
}
