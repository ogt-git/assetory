package com.project.assetory.connect.event;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.service.StockSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 최초 연동 직후 자산 수집 — 연동 저장이 커밋된 뒤 전용 executor에서 비동기로 실행 (연결 API 응답을 기다리게 하지 않음)
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialSyncListener {

    private final StockSyncService stockSyncService;

    @Async("codefSyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLinked(ConnectedAccountLinkedEvent event) {
        if (event.businessType() != BusinessType.ST) {
            return; // 은행 수집(FR-CN-04)은 이후 개발
        }
        try {
            StockSyncService.SyncResult result = stockSyncService.sync(event.connectedAccountId(), SyncJobType.BACKFILL);
            log.info("[InitialSync] 최초 수집 완료 connectedAccountId={} status={} count={}",
                    event.connectedAccountId(), result.status(), result.collectedCount());
        } catch (CodefSyncLockConflictException e) {
            log.info("[InitialSync] 다른 수집이 진행 중 — 건너뜀 connectedAccountId={}", event.connectedAccountId());
        } catch (RuntimeException e) {
            log.warn("[InitialSync] 최초 수집 실패 connectedAccountId={} error={}",
                    event.connectedAccountId(), e.getClass().getSimpleName());
        }
    }
}
