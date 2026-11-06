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
import com.project.assetory.detection.service.StockEventBackfillService;
import com.project.assetory.market.service.StockPriceBackfillService;
import com.project.assetory.market.service.StockPriceBackfillService.BackfillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 최초 연동 직후 자산 수집 — 연동 저장이 커밋된 뒤 전용 executor에서 비동기로 실행 (연결 API 응답을 기다리게 하지 않음)
// 수집이 성공하면 같은 작업 안에서 보유 종목 시세를 소급한다 (FR-MK-04, 05 §11.1) — 소급 대상이 수집 결과라 순서를 보장한다
// 시세 소급 뒤 같은 작업에서 과거 이벤트를 소급한다 (FR-DT-08, A5) — 이벤트 판정이 방금 저장한 시세를 쓰므로 같은 순서를 지킨다
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialSyncListener {

    private final StockSyncService stockSyncService;
    private final StockPriceBackfillService backfillService;
    private final AssetRepository assetRepository;
    private final StockEventBackfillService eventBackfillService;

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
        Long assetId;
        BackfillResult prices;
        try {
            assetId = assetRepository.findByConnectedAccountIdAndAssetType(connectedAccountId, AssetType.SECURITIES)
                    .map(Asset::getId).orElse(null);
            if (assetId == null) {
                return;
            }
            prices = backfillService.backfillHoldings(assetId);
        } catch (RuntimeException e) {
            log.warn("[InitialSync] 시세 소급 실패 — 연동 · 수집 결과는 유지 connectedAccountId={} error={}",
                    connectedAccountId, e.getClass().getSimpleName());
            return;
        }
        backfillEvents(connectedAccountId, assetId, prices);
    }

    // 과거 이벤트 소급 (FR-DT-08, A5) — 시세 소급이 끝난 뒤(결과가 있을 때만). 실패해도 연동 · 수집 · 시세는 유지, 예외는 밖으로 던지지 않는다.
    // 시세 소급에 실패한 종목이 없을 때만 완료 표시(backfill_completed)를 켠다 (A5 결정 H-5)
    private void backfillEvents(Long connectedAccountId, Long assetId, BackfillResult prices) {
        boolean pricesComplete = prices.failed() == 0;
        try {
            int created = eventBackfillService.backfill(assetId, pricesComplete);
            log.info("[InitialSync] 과거 이벤트 소급 완료 connectedAccountId={} 생성={} 시세실패={} 완료표시={}",
                    connectedAccountId, created, prices.failed(), pricesComplete);
        } catch (RuntimeException e) {
            log.warn("[InitialSync] 과거 이벤트 소급 실패 — 연동 · 수집 · 시세 결과는 유지, 완료 표시 안 함 connectedAccountId={} error={}",
                    connectedAccountId, e.getClass().getSimpleName());
        }
    }
}
