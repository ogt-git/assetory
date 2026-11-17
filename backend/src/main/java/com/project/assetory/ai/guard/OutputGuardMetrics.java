package com.project.assetory.ai.guard;

import com.project.assetory.ai.LlmPurpose;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * LLM 출력 검사 탈락 지표 (Q-7, D3) — llm.output.rejected{purpose, rule}: 걸린 규칙마다 1 (05 §12 반영 필요).
 * rule = OutputRule 소문자(causal · solicit · forecast · ungrounded_number · empty)로 값이 고정된다. 요약 원문은 태그에 넣지 않는다.
 * 호출 수 · 비용은 응답을 받은 시점에 클라이언트가 llm.calls{result=success}로 이미 셌다 — 여기서 다시 세지 않는다(중복 집계 방지)
 */
@Component
public class OutputGuardMetrics {

    private final MeterRegistry registry;

    public OutputGuardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRejected(LlmPurpose purpose, OutputGuardResult result) {
        for (OutputRule rule : result.violations()) {
            Counter.builder("llm.output.rejected").tag("purpose", purpose.tag()).tag("rule", rule.tag())
                    .register(registry).increment();
        }
    }
}
