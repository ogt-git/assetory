package com.project.assetory.connect.service;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

// 정상(ACTIVE) 증권 연동 전체 동기화 — 계정마다 결과는 sync_jobs에 남고, 한 계정이 실패해도 나머지는 계속한다
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSyncBatchService {

    public record BatchResult(int total, int success, int failed, int skipped) {}

    private final ConnectedAccountRepository connectedAccountRepository;
    private final StockSyncService stockSyncService;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public BatchResult syncAllActive() {
        List<Long> ids = connectedAccountRepository.findIdsByBusinessTypeAndStatus(BusinessType.ST, ConnectedAccountStatus.ACTIVE);
        log.info("[Batch] CODEF 동기화 시작 — 활성 연동 수={}", ids.size());

        int success = 0, failed = 0, skipped = 0;
        for (Long id : ids) {
            try {
                StockSyncService.SyncResult result = stockSyncService.sync(id, SyncJobType.HOLDING);
                if (result.status() == SyncJobStatus.SUCCESS) {
                    success++;
                } else {
                    failed++;
                    log.warn("[Batch] 동기화 실패 connectedAccountId={} type={}", id, result.errorType());
                }
            } catch (CodefSyncLockConflictException e) {
                // 수동 새로고침과 락 충돌 — 다른 동기화 진행 중이므로 이번 배치 스킵
                skipped++;
                log.info("[Batch] 락 충돌 — 다른 동기화 진행 중, 스킵 connectedAccountId={}", id);
            } catch (RuntimeException e) {
                failed++;
                log.warn("[Batch] 동기화 중단 connectedAccountId={} error={}", id, e.getClass().getSimpleName());
            }
        }

        log.info("[Batch] CODEF 동기화 완료 — 대상={} 성공={} 실패={} 스킵={}", ids.size(), success, failed, skipped);
        return new BatchResult(ids.size(), success, failed, skipped);
    }
}
