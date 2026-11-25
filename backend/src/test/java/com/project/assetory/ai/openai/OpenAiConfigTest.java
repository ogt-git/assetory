package com.project.assetory.ai.openai;

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

// 실제 application.yml로 assetory.ai.openai.*가 바인딩되는지 — 키 · 모델이 없어도 기동된다 (DB · Docker 없이)
class OpenAiConfigTest {

    // 테스트 전용 더미 키 — 실제 키가 아니다
    private static final String TEST_KEY = "fake-only-openai-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(OpenAiConfig.class, OpenAiMetrics.class, OpenAiApiClient.class)
            .withBean(SimpleMeterRegistry.class);

    @Test
    @DisplayName("기본값: 공식 도메인 · 출력 상한 300 · 타임아웃 10초, 키 · 모델 · temperature 없음 — 그래도 기동")
    void defaults() {
        // 로컬 프로필은 .env를 읽을 수 있어 키를 비워 실행 환경과 무관하게 만든다
        runner.withPropertyValues("OPENAI_API_KEY=", "ASSETORY_AI_OPENAI_MODEL=").run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiProperties p = context.getBean(OpenAiProperties.class);
            assertThat(p.baseUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(p.maxOutputTokens()).isEqualTo(300);
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.hasApiKey()).isFalse();
            assertThat(p.hasModel()).isFalse();
            assertThat(p.temperature()).isNull();
            assertThat(context).hasSingleBean(OpenAiApiClient.class);
        });
    }

    @Test
    @DisplayName("환경변수로 키 · 모델 · 상한 · temperature를 바꿀 수 있고, toString에 키가 나오지 않는다")
    void overrideByEnv() {
        runner.withPropertyValues("OPENAI_API_KEY=" + TEST_KEY, "ASSETORY_AI_OPENAI_MODEL=test-model",
                        "ASSETORY_AI_OPENAI_MAX_OUTPUT_TOKENS=200", "ASSETORY_AI_OPENAI_TEMPERATURE=0.2")
                .run(context -> {
                    OpenAiProperties p = context.getBean(OpenAiProperties.class);
                    assertThat(p.hasApiKey()).isTrue();
                    assertThat(p.model()).isEqualTo("test-model");
                    assertThat(p.maxOutputTokens()).isEqualTo(200);
                    assertThat(p.temperature()).isEqualByComparingTo("0.2");
                    assertThat(p.toString()).doesNotContain(TEST_KEY).contains("test-model");
                });
    }

    @Test
    @DisplayName("출력 상한 0 이하면 기동 실패")
    void invalidMaxOutputTokens_fails() {
        runner.withPropertyValues("ASSETORY_AI_OPENAI_MAX_OUTPUT_TOKENS=0").run(context -> assertThat(context).hasFailed());
    }
}
