package com.project.assetory.ai.openai;

import java.math.BigDecimal;
import java.time.Duration;

// OpenAI 테스트 공용 — 테스트 전용 더미 키 · 설정
final class OpenAiTestSupport {

    static final String BASE_URL = "https://openai.test/v1";
    /** 테스트 전용 가짜 키 — 실제 키가 아니다. 로그 · 예외 · 메트릭에 이 문자열이 나오면 유출이다 */
    static final String TEST_KEY = "TEST-ONLY-OPENAI-KEY-7f3a";
    static final String TEST_MODEL = "test-model";

    private OpenAiTestSupport() {
    }

    static OpenAiProperties properties(String apiKey, String model, BigDecimal temperature) {
        return new OpenAiProperties(BASE_URL, apiKey, model, 300, temperature, Duration.ofSeconds(10), Duration.ofSeconds(10));
    }
}
