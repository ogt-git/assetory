package com.project.assetory.market.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 장 마감 시각(Q-9 · ANL-8 판정 기준)이 바인딩되는지 — 수집 가드(15:40)와 다른 값 (DB · Docker 없이)
class MarketClosePropertiesTest {

    @Configuration
    @EnableConfigurationProperties(MarketCloseProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    @DisplayName("기본값 15:30 · 지연일 16:30 — 지연일(2026-11-19)이면 16:30, 평일이면 15:30")
    void defaults() {
        runner.withPropertyValues("ASSETORY_MARKET_LATE_CLOSE_DATES=2026-11-19").run(context -> {
            assertThat(context).hasNotFailed();
            MarketCloseProperties p = context.getBean(MarketCloseProperties.class);
            assertThat(p.closeTime()).isEqualTo(LocalTime.of(15, 30));
            assertThat(p.lateCloseTime()).isEqualTo(LocalTime.of(16, 30));
            assertThat(p.closeTime(LocalDate.of(2026, 11, 19))).isEqualTo(LocalTime.of(16, 30));
            assertThat(p.closeTime(LocalDate.of(2026, 11, 18))).isEqualTo(LocalTime.of(15, 30));
        });
    }

    @Test
    @DisplayName("환경변수로 마감 시각 · 지연일을 바꿀 수 있다")
    void overrideByEnv() {
        runner.withPropertyValues("ASSETORY_MARKET_CLOSE_TIME=15:20", "ASSETORY_MARKET_LATE_CLOSE_TIME=16:20",
                        "ASSETORY_MARKET_LATE_CLOSE_DATES=2026-12-01")
                .run(context -> {
                    MarketCloseProperties p = context.getBean(MarketCloseProperties.class);
                    assertThat(p.closeTime(LocalDate.of(2026, 11, 19))).isEqualTo(LocalTime.of(15, 20));
                    assertThat(p.closeTime(LocalDate.of(2026, 12, 1))).isEqualTo(LocalTime.of(16, 20));
                });
    }
}
