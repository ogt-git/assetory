package com.project.assetory.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 비동기 작업은 용도별 전용 executor를 쓴다 (풀·큐 크기 제한). @Async는 항상 이름을 지정한다
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    // CODEF 수집 — 기관 호출이 느려도 요청 스레드를 막지 않게. 큐가 차면 버리고 로그만 남긴다 (다음 수집 때 다시 시도)
    @Bean("codefSyncExecutor")
    public ThreadPoolTaskExecutor codefSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("codef-sync-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("[Async] CODEF 수집 큐 포화 — 이번 작업은 건너뜀 (다음 수집 때 반영)"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    // 인증 메일 발송 (비밀번호 재설정)
    @Bean("authMailExecutor")
    public ThreadPoolTaskExecutor authMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("auth-mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
