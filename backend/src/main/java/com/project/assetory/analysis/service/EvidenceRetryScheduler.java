package com.project.assetory.analysis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// PENDING 분석 근거 재수집 트리거 (ANL-7, Q-6) — assetory.analysis.retry.enabled=true일 때만 등록된다
// (기본값은 assetory.sync.batch.enabled를 따른다 — application.yml). 기본 cron은 30분마다
// 스케줄러 스레드 풀 3(spring.task.scheduling.pool.size) — 재시도가 길어져도 16:00 배치 · 07:30 보정이 밀리지 않는다
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assetory.analysis.retry.enabled", havingValue = "true")
public class EvidenceRetryScheduler {

    private final EvidenceRetryService retryService;

    @Scheduled(cron = "${assetory.analysis.retry.cron:0 */30 * * * *}", zone = "Asia/Seoul")
    public void run() {
        retryService.run();
    }
}
