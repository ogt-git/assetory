package com.project.assetory.ai.openai;

import com.project.assetory.ai.LlmPurpose;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 05 §12 LLM 지표 (Q-5) — 태그는 purpose · result · kind만(프롬프트 · 모델 응답 · 키 금지).
 * - llm.calls{purpose, result}: result = success 또는 실패 유형 소문자(auth · rate_limit · quota_exceeded · server_error ·
 *   unknown_status · network_error · invalid_response · truncated · bad_request · config_missing) — 05 §12 반영 필요
 * - llm.tokens{purpose, kind=prompt|completion}: 응답에 토큰 수가 있을 때만 (없으면 기록하지 않음)
 * - 지연: 외부 API 공통 external.api.latency{provider=openai, api=chat} 재사용. 호출 수는 llm.calls로만 센다(external.api.calls 미기록 — 중복 집계 방지)
 */
@Component
public class OpenAiMetrics {

    static final String RESULT_SUCCESS = "success";

    private final MeterRegistry registry;

    public OpenAiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCall(LlmPurpose purpose, String result) {
        Counter.builder("llm.calls").tag("purpose", purpose.tag()).tag("result", result).register(registry).increment();
    }

    void recordFailure(LlmPurpose purpose, OpenAiErrorType type) {
        recordCall(purpose, type.name().toLowerCase(Locale.ROOT));
    }

    void recordTokens(LlmPurpose purpose, Integer promptTokens, Integer completionTokens) {
        if (promptTokens != null) {
            Counter.builder("llm.tokens").tag("purpose", purpose.tag()).tag("kind", "prompt").register(registry).increment(promptTokens);
        }
        if (completionTokens != null) {
            Counter.builder("llm.tokens").tag("purpose", purpose.tag()).tag("kind", "completion").register(registry)
                    .increment(completionTokens);
        }
    }

    void recordLatency(long startNanos) {
        Timer.builder("external.api.latency").tag("provider", "openai").tag("api", "chat")
                .register(registry).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
