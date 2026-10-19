package com.project.assetory.market.kis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 05 §12 external.api.* — 태그는 provider · api · result만 (종목코드 · URL 금지, 카디널리티)
// api: token / daily-price / stock-info (05 §12 반영 필요)
@Component
public class KisMetrics {

    static final String API_TOKEN = "token";
    static final String API_DAILY_PRICE = "daily-price";
    static final String API_STOCK_INFO = "stock-info";

    private final MeterRegistry registry;

    public KisMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String api, boolean ok, long startNanos) {
        Counter.builder("external.api.calls")
                .tag("provider", "kis").tag("api", api).tag("result", ok ? "success" : "fail")
                .register(registry).increment();
        Timer.builder("external.api.latency")
                .tag("provider", "kis").tag("api", api)
                .register(registry).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
