package com.project.assetory.analysis.dart;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 05 §12 external.api.* — KisMetrics와 같은 이름 · 태그(provider · api · result만, 종목 · 고유번호 · URL 금지)
// api: list / corp-code (05 §12 반영 필요)
@Component
public class DartMetrics {

    static final String API_LIST = "list";
    static final String API_CORP_CODE = "corp-code";

    private final MeterRegistry registry;

    public DartMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String api, boolean ok, long startNanos) {
        Counter.builder("external.api.calls")
                .tag("provider", "dart").tag("api", api).tag("result", ok ? "success" : "fail")
                .register(registry).increment();
        Timer.builder("external.api.latency")
                .tag("provider", "dart").tag("api", api)
                .register(registry).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
