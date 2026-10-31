package com.project.assetory.connect.event;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.codef.CodefErrorType;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.service.StockSyncService;
import com.project.assetory.market.service.StockPriceBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 최초 연동 직후 자산 수집 — 연동 저장이 커밋된 뒤 전용 executor에서 비동기로 실행 (연결 API 응답을 기다리게 하지 않음)
// 수집이 성공하면 같은 작업 안에서 보유 종목 시세를 소급한다 (FR-MK-04, 05 §11.1) — 소급 대상이 수집 결과라 순서를 보장한다
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialSyncListener {

    private final StockSyncService stockSyncService;
    private final StockPriceBackfillService backfillService;
    private final AssetRepository assetRepository;

    @Async("codefSyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLinked(ConnectedAccountLinkedEvent event) {
        if (event.businessType() != BusinessType.ST) {
            return; // 은행 수집(FR-CN-04)은 이후 개발
        }
        StockSyncService.SyncResult result;
        try {
            result = stockSyncService.sync(event.connectedAccountId(), SyncJobType.BACKFILL);
            log.info("[InitialSync] 최초 수집 완료 connectedAccountId={} status={} count={}",
                    event.connectedAccountId(), result.status(), result.collectedCount());
        } catch (CodefSyncLockConflictException e) {
            log.info("[InitialSync] 다른 수집이 진행 중 — 건너뜀 connectedAccountId={}", event.connectedAccountId());
            return;
        } catch (RuntimeException e) {
            log.warn("[InitialSync] 최초 수집 실패 connectedAccountId={} error={}",
                    event.connectedAccountId(), e.getClass().getSimpleName());
            return;
        }

        // 조회 결과 없음(EMPTY_RESULT)은 sync_jobs가 SUCCESS여도 보유 종목을 갱신하지 않았으므로 소급하지 않는다
        if (result.status() == SyncJobStatus.SUCCESS && result.errorType() != CodefErrorType.EMPTY_RESULT) {
            backfill(event.connectedAccountId());
        }
    }

    // 소급 실패는 연동 · 수집 결과에 영향을 주지 않는다 — 로그만 남긴다 (05 §11.1 "수집이 실패해도 연동은 유지"와 같은 방침)
    private void backfill(Long connectedAccountId) {
        try {
            assetRepository.findByConnectedAccountIdAndAssetType(connectedAccountId, AssetType.SECURITIES)
                    .map(Asset::getId)
                    .ifPresent(backfillService::backfillHoldings);
        } catch (RuntimeException e) {
            log.warn("[InitialSync] 시세 소급 실패 — 연동 · 수집 결과는 유지 connectedAccountId={} error={}",
                    connectedAccountId, e.getClass().getSimpleName());
        }
    }
}
