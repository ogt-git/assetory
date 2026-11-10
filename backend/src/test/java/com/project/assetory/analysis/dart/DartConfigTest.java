package com.project.assetory.analysis.dart;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.dart.*가 바인딩되고 DART 빈이 뜨는지 — 키가 없어도 기동된다 (DB · Docker 없이)
class DartConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(EvidenceConfig.class, DartConfig.class, DartMetrics.class, DartApiClient.class)
            .withBean(SimpleMeterRegistry.class);

    @Test
    @DisplayName("기본값: 공식 도메인 · 유형 B · I · D · 페이지 상한 2 · corpCode 타임아웃 30초, 키 없이도 빈 생성")
    void defaults() {
        // 로컬 프로필은 .env를 읽을 수 있어 키를 비워 실행 환경과 무관하게 만든다
        runner.withPropertyValues("DART_API_KEY=").run(context -> {
            assertThat(context).hasNotFailed();
            DartProperties properties = context.getBean(DartProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("https://opendart.fss.or.kr/api");
            assertThat(properties.disclosureTypes()).containsExactly("B", "I", "D");
            assertThat(properties.maxPages()).isEqualTo(2);
            assertThat(properties.corpCodeTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.hasApiKey()).isFalse();
            assertThat(context).hasSingleBean(DartApiClient.class);
        });
    }

    @Test
    @DisplayName("환경변수로 유형 · 상한을 바꿀 수 있고, toString에 키가 나오지 않는다")
    void overrideByEnv() {
        runner.withPropertyValues("DART_API_KEY=" + DartTestSupport.TEST_KEY, "ASSETORY_DART_DISCLOSURE_TYPES=B,I",
                        "ASSETORY_DART_MAX_PAGES=3")
                .run(context -> {
                    DartProperties properties = context.getBean(DartProperties.class);
                    assertThat(properties.disclosureTypes()).containsExactly("B", "I");
                    assertThat(properties.maxPages()).isEqualTo(3);
                    assertThat(properties.hasApiKey()).isTrue();
                    assertThat(properties.toString()).doesNotContain(DartTestSupport.TEST_KEY);
                });
    }

    @Test
    @DisplayName("공시 유형은 A~J 한 글자만 — 그 밖의 값 · 빈 목록이면 기동 실패")
    void invalidTypes_fail() {
        runner.withPropertyValues("ASSETORY_DART_DISCLOSURE_TYPES=B,X").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ASSETORY_DART_MAX_PAGES=0").run(context -> assertThat(context).hasFailed());
    }
}
