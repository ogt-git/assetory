package com.project.assetory.ai.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// HTTP 상태(+ 429는 본문 error.code) → 유형 · 상위 재시도 가능 여부 (Q-5 쟁점 B: 쿼터 소진은 재시도 금지)
class OpenAiErrorTypeTest {

    @ParameterizedTest(name = "[{index}] http {0} · code {1} → {2}")
    @DisplayName("상태 코드 분류")
    @CsvSource(nullValues = "null", value = {
            "401, null, AUTH",
            "403, invalid_api_key, AUTH",
            "400, null, BAD_REQUEST",
            "429, rate_limit_exceeded, RATE_LIMIT",
            "429, null, RATE_LIMIT",
            "429, insufficient_quota, QUOTA_EXCEEDED",
            "500, null, SERVER_ERROR",
            "503, null, SERVER_ERROR",
            "404, null, UNKNOWN_STATUS",
            "409, null, UNKNOWN_STATUS"
    })
    void fromHttpStatus(int status, String code, OpenAiErrorType expected) {
        assertThat(OpenAiErrorType.fromHttpStatus(status, code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("재시도 금지 — 인증 · 잘못된 요청 · 쿼터 소진 · 키/모델 없음 (다시 해도 같다)")
    void retryable() {
        assertThat(OpenAiErrorType.AUTH.isRetryable()).isFalse();
        assertThat(OpenAiErrorType.BAD_REQUEST.isRetryable()).isFalse();
        assertThat(OpenAiErrorType.QUOTA_EXCEEDED.isRetryable()).isFalse();
        assertThat(OpenAiErrorType.CONFIG_MISSING.isRetryable()).isFalse();
        assertThat(OpenAiErrorType.RATE_LIMIT.isRetryable()).isTrue();
        assertThat(OpenAiErrorType.SERVER_ERROR.isRetryable()).isTrue();
        assertThat(OpenAiErrorType.NETWORK_ERROR.isRetryable()).isTrue();
        assertThat(OpenAiErrorType.INVALID_RESPONSE.isRetryable()).isTrue();
        assertThat(OpenAiErrorType.TRUNCATED.isRetryable()).isTrue();
        assertThat(OpenAiErrorType.UNKNOWN_STATUS.isRetryable()).isTrue();
    }
}
