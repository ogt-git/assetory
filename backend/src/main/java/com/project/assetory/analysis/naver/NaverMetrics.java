package com.project.assetory.analysis.naver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 05 §12 external.api.* — KisMetrics · DartMetrics와 같은 이름 · 태그(provider · api · result만, 검색어 · URL 금지)
// api: news (05 §12 반영 필요)
@Component
public class NaverMetrics {

    static final String API_NEWS = "news";

    private final MeterRegistry registry;

    public NaverMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(boolean ok, long startNanos) {
        Counter.builder("external.api.calls")
                .tag("provider", "naver").tag("api", API_NEWS).tag("result", ok ? "success" : "fail")
                .register(registry).increment();
        Timer.builder("external.api.latency")
                .tag("provider", "naver").tag("api", API_NEWS)
                .register(registry).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
