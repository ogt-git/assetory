package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.config.EvidenceConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.naver.*가 바인딩되고 네이버 빈이 뜨는지 — 키가 없어도 기동된다 (DB · Docker 없이)
class NaverConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(EvidenceConfig.class, NaverConfig.class, NaverMetrics.class, NaverNewsApiClient.class)
            .withBean(SimpleMeterRegistry.class);

    @Test
    @DisplayName("기본값: API HUB 도메인 · 페이지 상한 3, 키 없이도 빈 생성")
    void defaults() {
        // 로컬 프로필은 .env를 읽을 수 있어 키를 비워 실행 환경과 무관하게 만든다
        runner.withPropertyValues("NAVER_API_HUB_CLIENT_ID=", "NAVER_API_HUB_CLIENT_SECRET=").run(context -> {
            assertThat(context).hasNotFailed();
            NaverProperties properties = context.getBean(NaverProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("https://naverapihub.apigw.ntruss.com");
            assertThat(properties.maxPages()).isEqualTo(3);
            assertThat(properties.hasCredentials()).isFalse();
            assertThat(context).hasSingleBean(NaverNewsApiClient.class);
        });
    }

    @Test
    @DisplayName("환경변수로 키 · 상한을 바꿀 수 있고, toString에 키가 나오지 않는다")
    void overrideByEnv() {
        runner.withPropertyValues("NAVER_API_HUB_CLIENT_ID=" + NaverTestSupport.TEST_CLIENT_ID,
                        "NAVER_API_HUB_CLIENT_SECRET=" + NaverTestSupport.TEST_CLIENT_SECRET, "ASSETORY_NAVER_MAX_PAGES=2")
                .run(context -> {
                    NaverProperties properties = context.getBean(NaverProperties.class);
                    assertThat(properties.hasCredentials()).isTrue();
                    assertThat(properties.maxPages()).isEqualTo(2);
                    assertThat(properties.toString()).doesNotContain(NaverTestSupport.TEST_CLIENT_ID)
                            .doesNotContain(NaverTestSupport.TEST_CLIENT_SECRET);
                });
    }

    @Test
    @DisplayName("페이지 상한 0 이하면 기동 실패")
    void invalidMaxPages_fails() {
        runner.withPropertyValues("ASSETORY_NAVER_MAX_PAGES=0").run(context -> assertThat(context).hasFailed());
    }
}
