package com.project.assetory.analysis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.evidence.*가 바인딩되는지 (DB · Docker 없이)
class EvidenceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EvidenceConfig.class);

    @Test
    @DisplayName("기본값: 외부 호출 타임아웃 10초, 공시 · 뉴스 수집 범위 이벤트일 전 1일 ~ 후 1일 (임시값)")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EvidenceProperties properties = context.getBean(EvidenceProperties.class);
            assertThat(properties.callTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.disclosure().daysBefore()).isEqualTo(1);
            assertThat(properties.disclosure().daysAfter()).isEqualTo(1);
            assertThat(properties.news().daysBefore()).isEqualTo(1);
            assertThat(properties.news().daysAfter()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("환경변수만으로 타임아웃 · 수집 범위를 바꿀 수 있다")
    void overrideByEnv() {
        runner.withPropertyValues("ASSETORY_EVIDENCE_CALL_TIMEOUT=5s",
                        "ASSETORY_EVIDENCE_DISCLOSURE_DAYS_BEFORE=4", "ASSETORY_EVIDENCE_DISCLOSURE_DAYS_AFTER=3",
                        "ASSETORY_EVIDENCE_NEWS_DAYS_BEFORE=2", "ASSETORY_EVIDENCE_NEWS_DAYS_AFTER=0")
                .run(context -> {
                    EvidenceProperties properties = context.getBean(EvidenceProperties.class);
                    assertThat(properties.callTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.disclosure().daysBefore()).isEqualTo(4);
                    assertThat(properties.disclosure().daysAfter()).isEqualTo(3);
                    assertThat(properties.news().daysBefore()).isEqualTo(2);
                    assertThat(properties.news().daysAfter()).isZero();
                });
    }

    @Test
    @DisplayName("수집 범위가 음수면 기동에 실패한다")
    void negativeWindow_fails() {
        runner.withPropertyValues("ASSETORY_EVIDENCE_NEWS_DAYS_BEFORE=-1")
                .run(context -> assertThat(context).hasFailed());
    }
}
