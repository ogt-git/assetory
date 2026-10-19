package com.project.assetory.market.kis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 실제 application.yml로 assetory.kis.*가 바인딩되고 KIS 빈이 뜨는지 — 키가 없어도 기동된다 (DB · Docker 없이)
class KisConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(KisConfig.class, KisMetrics.class, KisTokenManager.class, KisApiClient.class)
            .withBean(SimpleMeterRegistry.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    @DisplayName("기본값: 실전 도메인 · 50ms 간격 · 타임아웃 10초 · 여유 10분 · 재시도 2회, 키 없이도 빈 생성")
    void defaults() {
        // 로컬 프로필은 .env를 읽으므로 키를 비워 실행 환경과 무관하게 만든다
        runner.withPropertyValues("KIS_APP_KEY=", "KIS_APP_SECRET=").run(context -> {
            assertThat(context).hasNotFailed();
            KisProperties properties = context.getBean(KisProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("https://openapi.koreainvestment.com:9443");
            assertThat(properties.callIntervalMs()).isEqualTo(50);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.tokenRefreshMargin()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.rateLimit().maxRetries()).isEqualTo(2);
            assertThat(properties.rateLimit().backoffMs()).isEqualTo(1000);
            assertThat(properties.hasCredentials()).isFalse();
            assertThat(context).hasSingleBean(KisApiClient.class);
        });
    }

    @Test
    @DisplayName("환경변수만으로 모의 도메인 · 간격을 바꿀 수 있다")
    void overrideByEnv() {
        runner.withPropertyValues("KIS_BASE_URL=https://openapivts.koreainvestment.com:29443", "KIS_CALL_INTERVAL_MS=500")
                .run(context -> {
                    KisProperties properties = context.getBean(KisProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo("https://openapivts.koreainvestment.com:29443");
                    assertThat(properties.callIntervalMs()).isEqualTo(500);
                });
    }
}
