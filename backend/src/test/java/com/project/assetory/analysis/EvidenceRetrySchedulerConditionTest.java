package com.project.assetory.analysis;

import com.project.assetory.analysis.service.EvidenceRetryScheduler;
import com.project.assetory.analysis.service.EvidenceRetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 재시도 스케줄러 등록 조건 (Q-6) — retry.enabled 기본값이 일일 배치 설정을 따르는지(C1 보정 배치와 같은 방식), fake 프로필에서는 꺼지는지.
// 실제 application.yml을 읽는다. 개발 PC 환경변수에 흔들리지 않게 배치 환경변수 이름을 테스트 속성으로 고정한다
class EvidenceRetrySchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(EvidenceRetryService.class, () -> mock(EvidenceRetryService.class))
            .withUserConfiguration(EvidenceRetryScheduler.class);

    @Test
    @DisplayName("일일 배치를 켜면 재시도 스케줄러도 따로 설정하지 않아도 등록된다")
    void enabledWithDailyBatch() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true")
                .run(context -> assertThat(context).hasSingleBean(EvidenceRetryScheduler.class));
    }

    @Test
    @DisplayName("기본(일일 배치 꺼짐)이면 등록되지 않는다")
    void disabledByDefault() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=false")
                .run(context -> assertThat(context).doesNotHaveBean(EvidenceRetryScheduler.class));
    }

    @Test
    @DisplayName("배치를 켜도 재시도만 끌 수 있다")
    void explicitlyDisabled() {
        runner.withPropertyValues("ASSETORY_SYNC_BATCH_ENABLED=true", "ASSETORY_ANALYSIS_RETRY_ENABLED=false")
                .run(context -> assertThat(context).doesNotHaveBean(EvidenceRetryScheduler.class));
    }

    @Test
    @DisplayName("fake 프로필에서는 배치를 켜도 재시도가 꺼진다 (application-fake.yml 명시)")
    void fakeProfileDisabled() {
        runner.withPropertyValues("spring.profiles.active=fake", "ASSETORY_SYNC_BATCH_ENABLED=true")
                .run(context -> assertThat(context).doesNotHaveBean(EvidenceRetryScheduler.class));
    }

    @Test
    @DisplayName("스케줄러 스레드 풀 3 — 재시도가 오래 걸려도 16:00 배치 · 07:30 보정이 밀리지 않는다 (spring.task.scheduling.pool.size)")
    void schedulingPoolSize() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(com.project.assetory.global.config.SchedulingConfig.class)
                .run(context -> assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor()
                        .getCorePoolSize()).isEqualTo(3));
    }
}
