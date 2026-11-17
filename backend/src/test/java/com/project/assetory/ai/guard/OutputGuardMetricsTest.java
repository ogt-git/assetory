package com.project.assetory.ai.guard;

import com.project.assetory.ai.LlmPurpose;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// llm.output.rejected{purpose, rule} (D3) — 탈락 규칙마다 1, 통과면 기록 없음. 호출 수(llm.calls)는 클라이언트 몫이라 건드리지 않는다
class OutputGuardMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutputGuardMetrics metrics = new OutputGuardMetrics(registry);

    @Test
    @DisplayName("탈락하면 걸린 규칙마다 1씩 센다 (D5)")
    void rejectedPerRule() {
        metrics.recordRejected(LlmPurpose.CAUSE,
                new OutputGuardResult(Set.of(OutputRule.SOLICIT, OutputRule.UNGROUNDED_NUMBER)));

        assertThat(count("solicit")).isEqualTo(1.0);
        assertThat(count("ungrounded_number")).isEqualTo(1.0);
        assertThat(registry.find("llm.output.rejected").tag("rule", "causal").counter()).isNull();
        assertThat(registry.find("llm.calls").counter()).isNull();
    }

    @Test
    @DisplayName("통과하면 기록하지 않는다")
    void passedRecordsNothing() {
        metrics.recordRejected(LlmPurpose.CAUSE, new OutputGuardResult(Set.of()));

        assertThat(registry.find("llm.output.rejected").counters()).isEmpty();
        assertThat(registry.find("llm.calls").counter()).isNull();
    }

    private double count(String rule) {
        return registry.get("llm.output.rejected").tag("purpose", "cause").tag("rule", rule).counter().count();
    }
}
