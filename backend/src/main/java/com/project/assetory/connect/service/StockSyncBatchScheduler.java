package com.project.assetory.connect.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 증권 동기화 배치 트리거 — assetory.sync.batch.enabled=true일 때만 등록된다 (기본 비활성)
// 기본 cron은 영업일 16:00 (04 §2). 휴일 처리는 01 §14-23 확정 전이라 MON-FRI만 적용
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assetory.sync.batch.enabled", havingValue = "true")
public class StockSyncBatchScheduler {

    private final StockSyncBatchService stockSyncBatchService;

    @Scheduled(cron = "${assetory.sync.batch.cron:0 0 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void run() {
        stockSyncBatchService.syncAllActive();
    }
}
