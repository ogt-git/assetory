package com.project.assetory.ai.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 application.yml로 assetory.ai.output-guard.*가 바인딩되는지 — 패턴 목록이 테스트 픽스처(기대값)와 같은지 (DB · Docker 없이)
class OutputGuardConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OutputGuardConfig.class);

    @Test
    @DisplayName("기본 패턴이 바인딩되고 픽스처와 같다 — 검사기 빈이 만들어진다")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OutputGuardProperties p = context.getBean(OutputGuardProperties.class);
            assertThat(p.causal()).containsExactlyElementsOf(OutputGuardFixtures.CAUSAL);
            assertThat(p.solicit()).containsExactlyElementsOf(OutputGuardFixtures.SOLICIT);
            assertThat(p.forecast()).containsExactlyElementsOf(OutputGuardFixtures.FORECAST);
            assertThat(context).hasSingleBean(LlmOutputGuard.class);
        });
    }

    @Test
    @DisplayName("잘못된 정규식이면 기동이 실패한다")
    void invalidRegexFailsStartup() {
        runner.withPropertyValues("assetory.ai.output-guard.causal[0]=(")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("규칙 목록이 비면 검증 오류로 기동이 실패한다")
    void emptyListFailsStartup() {
        runner.withPropertyValues("assetory.ai.output-guard.solicit=")
                .run(context -> assertThat(context).hasFailed());
    }
}
