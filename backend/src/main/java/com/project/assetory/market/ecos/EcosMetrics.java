package com.project.assetory.market.ecos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 05 §12 external.api.* — KisMetrics와 같은 이름 · 태그(provider · api · result만, 통계표 · URL 금지)
// provider=ecos, api=statistic-search (05 §12 반영 필요)
@Component
public class EcosMetrics {

    static final String API_STATISTIC_SEARCH = "statistic-search";

    private final MeterRegistry registry;

    public EcosMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(boolean ok, long startNanos) {
        Counter.builder("external.api.calls")
                .tag("provider", "ecos").tag("api", API_STATISTIC_SEARCH).tag("result", ok ? "success" : "fail")
                .register(registry).increment();
        Timer.builder("external.api.latency")
                .tag("provider", "ecos").tag("api", API_STATISTIC_SEARCH)
                .register(registry).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
