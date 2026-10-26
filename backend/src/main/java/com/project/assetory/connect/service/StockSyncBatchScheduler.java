package com.project.assetory.connect.service;

import com.project.assetory.snapshot.service.DailyAssetBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 일일 자산 기록 배치 트리거 — assetory.sync.batch.enabled=true일 때만 등록된다 (기본 비활성)
// 기본 cron은 영업일 16:00 (04 배치 일정). 휴장일 판정은 배치 안에서 한다(MON-FRI는 주말 호출만 줄인다)
// CODEF 동기화는 일일 배치 안에서 계정마다 함께 돈다 (이식했던 단독 동기화 배치는 일일 배치로 대체해 삭제 — port-record §2)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assetory.sync.batch.enabled", havingValue = "true")
public class StockSyncBatchScheduler {

    private final DailyAssetBatchService dailyAssetBatchService;

    @Scheduled(cron = "${assetory.sync.batch.cron:0 0 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void run() {
        dailyAssetBatchService.run();
    }

    // 장 마감 지연일(수능일) 전용 — 지연일이 아니면 배치 안에서 바로 끝난다. 단일 스레드 스케줄러라 16:00 실행과 겹치지 않는다
    @Scheduled(cron = "${assetory.sync.batch.late-close-cron:0 50 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void runLateClose() {
        dailyAssetBatchService.runLateClose();
    }
}
