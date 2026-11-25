package com.project.assetory.market.ecos;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.ecos.*가 바인딩되고 ECOS 빈이 뜨는지 — 키가 없어도 기동된다 (DB · Docker 없이)
class EcosConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(EcosConfig.class, EcosMetrics.class, EcosApiClient.class)
            .withBean(SimpleMeterRegistry.class);

    @Test
    @DisplayName("기본값: 공식 도메인 · 타임아웃 10초, 키 없이도 빈 생성")
    void defaults() {
        // 로컬 프로필은 .env를 읽을 수 있어 키를 비워 실행 환경과 무관하게 만든다
        runner.withPropertyValues("ECOS_API_KEY=").run(context -> {
            assertThat(context).hasNotFailed();
            EcosProperties properties = context.getBean(EcosProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("https://ecos.bok.or.kr/api");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.hasApiKey()).isFalse();
            assertThat(context).hasSingleBean(EcosApiClient.class);
        });
    }

    @Test
    @DisplayName("환경변수로 키를 넣을 수 있고, toString에 키가 나오지 않는다")
    void overrideByEnv() {
        runner.withPropertyValues("ECOS_API_KEY=" + EcosTestSupport.TEST_KEY).run(context -> {
            EcosProperties properties = context.getBean(EcosProperties.class);
            assertThat(properties.hasApiKey()).isTrue();
            assertThat(properties.toString()).doesNotContain(EcosTestSupport.TEST_KEY);
        });
    }
}
