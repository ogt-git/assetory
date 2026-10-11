package com.project.assetory.connect.service;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.dto.StockRefreshResponse;
import com.project.assetory.connect.dto.StockRefreshResponse.AccountResult;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.global.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

// 증권 수동 새로고침 — 쿨다운 5분 (키: codef:refresh:cooldown:{userId}:STOCK)
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRefreshService {

    static final String COOLDOWN_PREFIX = "codef:refresh:cooldown:";
    static final Duration COOLDOWN = Duration.ofMinutes(5);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ConnectedAccountRepository connectedAccountRepository;
    private final StockSyncService stockSyncService;
    private final StringRedisTemplate redisTemplate;

    public StockRefreshResponse refresh(Long userId) {
        String key = COOLDOWN_PREFIX + userId + ":STOCK";
        Boolean cooldownSet = redisTemplate.opsForValue().setIfAbsent(key, "1", COOLDOWN);
        if (Boolean.FALSE.equals(cooldownSet)) {
            throw new TooManyRequestsException("새로고침은 5분에 한 번만 가능합니다.");
        }
        LocalDateTime nextAvailableAt = LocalDateTime.now(KST).plus(COOLDOWN);

        List<ConnectedAccount> accounts = connectedAccountRepository
                .findAllByUserIdAndBusinessTypeAndStatusOrderByIdAsc(userId, BusinessType.ST, ConnectedAccountStatus.ACTIVE);

        List<AccountResult> results = new ArrayList<>();
        for (ConnectedAccount account : accounts) {
            String institutionName = account.getInstitution().getName();
            try {
                StockSyncService.SyncResult result = stockSyncService.sync(account.getId(), SyncJobType.HOLDING);
                String message = result.errorType() == null ? null : result.errorType().getUserMessage();
                results.add(new AccountResult(account.getId(), institutionName, result.status().name(),
                        result.collectedCount(), message));
            } catch (CodefSyncLockConflictException e) {
                log.info("[ManualRefresh] 다른 동기화 진행 중 — 건너뜀 connectedAccountId={}", account.getId());
                results.add(new AccountResult(account.getId(), institutionName, "LOCKED", 0, e.getMessage()));
            }
        }
        return new StockRefreshResponse(results, LocalDateTime.now(KST), nextAvailableAt);
    }
}
