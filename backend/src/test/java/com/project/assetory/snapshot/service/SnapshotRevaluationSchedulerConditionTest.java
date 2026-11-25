package com.project.assetory.snapshot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 잠정 종가 보정 스케줄러 등록 조건 — application.yml의 confirm.enabled 기본값이 일일 배치 설정을 따라가는지 (01 §14 #23)
// 실제 application.yml을 읽는다. 개발 PC 환경변수에 흔들리지 않게 배치 환경변수 이름을 테스트 속성으로 고정한다
class SnapshotRevaluationSchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(SnapshotRevaluationService.class, () -> mock(SnapshotRevaluationService.class))
            .withUserConfiguration(SnapshotRevaluationScheduler.class);

    @Test
    @DisplayName("일일 배치를 켜면 보정 스케줄러도 따로 설정하지 않아도 등록된다")
    void enabledWithDailyBatch() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true")
                .run(context -> assertThat(context).hasSingleBean(SnapshotRevaluationScheduler.class));
    }

    @Test
    @DisplayName("일일 배치가 꺼져 있으면 보정 스케줄러도 등록되지 않는다")
    void disabledWithDailyBatch() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=false")
                .run(context -> assertThat(context).doesNotHaveBean(SnapshotRevaluationScheduler.class));
    }

    @Test
    @DisplayName("프로필 설정으로 배치를 켜도(assetory.sync.batch.enabled) 보정이 따라 켜진다")
    void followsBatchPropertyNotOnlyEnv() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=false", "assetory.sync.batch.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SnapshotRevaluationScheduler.class));
    }

    @Test
    @DisplayName("배치를 켜도 보정만 명시적으로 끌 수 있다")
    void explicitlyDisabled() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true", "ASSETORY_SYNC_CONFIRM_ENABLED=false")
                .run(context -> assertThat(context).doesNotHaveBean(SnapshotRevaluationScheduler.class));
    }

    @Test
    @DisplayName("배치가 꺼져 있어도 보정만 명시적으로 켤 수 있다")
    void explicitlyEnabled() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=false", "ASSETORY_SYNC_CONFIRM_ENABLED=true")
                .run(context -> assertThat(context).hasSingleBean(SnapshotRevaluationScheduler.class));
    }
}
