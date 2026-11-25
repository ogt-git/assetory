package com.project.assetory.analysis.config;

import com.project.assetory.analysis.service.NewsEvidenceRetentionJob;
import com.project.assetory.analysis.service.NewsEvidenceRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 실제 application.yml로 assetory.evidence.news-retention.*가 바인딩되고, 기본 설정에서 삭제 배치가 켜지는지 (DB · Docker 없이)
class NewsRetentionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(NewsRetentionConfig.class, NewsEvidenceRetentionJob.class)
            .withBean(NewsEvidenceRetentionService.class, () -> mock(NewsEvidenceRetentionService.class));

    @Test
    @DisplayName("기본값: 켜짐 · 20일 · 매일 03:30 — 하루 1회라 실제 최대 보관이 21일 미만")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NewsRetentionProperties properties = context.getBean(NewsRetentionProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.days()).isEqualTo(20);
            assertThat(properties.cron()).isEqualTo("0 30 3 * * *");
            assertThat(context).hasSingleBean(NewsEvidenceRetentionJob.class);
        });
    }

    @Test
    @DisplayName("enabled=false면 스케줄 빈이 등록되지 않는다 · 환경변수로 기간을 바꿀 수 있다")
    void disabledAndOverride() {
        runner.withPropertyValues("ASSETORY_EVIDENCE_NEWS_RETENTION_ENABLED=false", "ASSETORY_EVIDENCE_NEWS_RETENTION_DAYS=10")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NewsEvidenceRetentionJob.class);
                    assertThat(context.getBean(NewsRetentionProperties.class).days()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("보관 기간이 1일 미만이거나 21일 이상이면 기동 실패 — 약관상 서버 보관 21일 이내(B0 A안)")
    void invalidDays_fail() {
        runner.withPropertyValues("ASSETORY_EVIDENCE_NEWS_RETENTION_DAYS=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ASSETORY_EVIDENCE_NEWS_RETENTION_DAYS=21").run(context -> assertThat(context).hasFailed());
    }
}
