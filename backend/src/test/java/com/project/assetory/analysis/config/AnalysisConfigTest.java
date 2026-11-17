package com.project.assetory.analysis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.analysis.*가 바인딩되는지 (DB · Docker 없이)
class AnalysisConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AnalysisConfig.class);

    @Test
    @DisplayName("기본값: 시장 동반 기준 2.0%p (01 §14 #12 임시값)")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AnalysisProperties properties = context.getBean(AnalysisProperties.class);
            assertThat(properties.marketMove().maxExcessRate()).isEqualByComparingTo("2.0");
        });
    }

    @Test
    @DisplayName("환경변수만으로 기준을 바꿀 수 있다")
    void overrideByEnv() {
        runner.withPropertyValues("ASSETORY_ANALYSIS_MARKET_MOVE_MAX_EXCESS_RATE=1.5")
                .run(context -> assertThat(context.getBean(AnalysisProperties.class).marketMove().maxExcessRate())
                        .isEqualTo(new BigDecimal("1.5")));
    }

    @Test
    @DisplayName("기본값: 소급 직후 근거 수집 상한 50건 (Q-4), 환경변수로 바꿀 수 있다")
    void backfillCollectMax() {
        runner.run(context -> assertThat(context.getBean(AnalysisProperties.class).backfillCollectMax()).isEqualTo(50));
        runner.withPropertyValues("ASSETORY_ANALYSIS_BACKFILL_COLLECT_MAX=7")
                .run(context -> assertThat(context.getBean(AnalysisProperties.class).backfillCollectMax()).isEqualTo(7));
    }

    @Test
    @DisplayName("소급 수집 상한 0 이하는 기동 실패")
    void backfillCollectMax_rejectsZero() {
        runner.withPropertyValues("ASSETORY_ANALYSIS_BACKFILL_COLLECT_MAX=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("재시도 기본값(Q-6): 30분 주기 · 24시간 창 · 1회 50건, 켜짐은 일일 배치 설정을 따른다(기본 꺼짐)")
    void retryDefaults() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=false").run(context -> {
            AnalysisProperties.Retry retry = context.getBean(AnalysisProperties.class).retry();
            assertThat(retry.enabled()).isFalse();
            assertThat(retry.cron()).isEqualTo("0 */30 * * * *");
            assertThat(retry.window()).isEqualTo(java.time.Duration.ofHours(24));
            assertThat(retry.maxPerRun()).isEqualTo(50);
        });
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true")
                .run(context -> assertThat(context.getBean(AnalysisProperties.class).retry().enabled()).isTrue());
    }

    @Test
    @DisplayName("재시도 설정은 환경변수로 바꿀 수 있고, 1회 상한 0 이하는 기동 실패")
    void retryOverride() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true", "ASSETORY_ANALYSIS_RETRY_ENABLED=false",
                        "ASSETORY_ANALYSIS_RETRY_CRON=0 */10 * * * *", "ASSETORY_ANALYSIS_RETRY_WINDOW=6h",
                        "ASSETORY_ANALYSIS_RETRY_MAX_PER_RUN=7")
                .run(context -> {
                    AnalysisProperties.Retry retry = context.getBean(AnalysisProperties.class).retry();
                    assertThat(retry.enabled()).isFalse();
                    assertThat(retry.cron()).isEqualTo("0 */10 * * * *");
                    assertThat(retry.window()).isEqualTo(java.time.Duration.ofHours(6));
                    assertThat(retry.maxPerRun()).isEqualTo(7);
                });
        runner.withPropertyValues("ASSETORY_ANALYSIS_RETRY_MAX_PER_RUN=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("음수 기준은 기동 실패")
    void rejectsNegative() {
        runner.withPropertyValues("ASSETORY_ANALYSIS_MARKET_MOVE_MAX_EXCESS_RATE=-0.1")
                .run(context -> assertThat(context).hasFailed());
    }
}
