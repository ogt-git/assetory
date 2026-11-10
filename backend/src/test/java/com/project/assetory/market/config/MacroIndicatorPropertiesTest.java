package com.project.assetory.market.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.market.indicator-lookback-days가 바인딩되는지 (DB · Docker 없이)
class MacroIndicatorPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(MacroIndicatorProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    @DisplayName("기본 45일 · 환경변수로 변경 가능 · 2일 미만이면 기동 실패(직전 값 계산에 최소 2일)")
    void lookbackDays() {
        runner.run(context -> assertThat(context.getBean(MacroIndicatorProperties.class).indicatorLookbackDays()).isEqualTo(45));
        runner.withPropertyValues("ASSETORY_MARKET_INDICATOR_LOOKBACK_DAYS=10")
                .run(context -> assertThat(context.getBean(MacroIndicatorProperties.class).indicatorLookbackDays()).isEqualTo(10));
        runner.withPropertyValues("ASSETORY_MARKET_INDICATOR_LOOKBACK_DAYS=1").run(context -> assertThat(context).hasFailed());
    }
}
