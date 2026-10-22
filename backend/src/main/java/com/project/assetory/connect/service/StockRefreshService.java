package com.project.assetory.connect.service;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.dto.StockRefreshResponse;
import com.project.assetory.connect.dto.StockRefreshResponse.AccountResult;
import com.project.assetory.connect.exception.CodefAccountNotFoundException;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.exception.RefreshCooldownException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 증권 수동 새로고침 — 사용자의 정상(ACTIVE) 증권 연동을 차례로 동기화한다.
 *
 * 쿨다운: codef:refresh:cooldown:{userId}:STOCK, 5분, 사용자 단위 (기존 프로젝트와 같음)
 * - 연동이 없으면 쿨다운을 걸지 않고 거절한다 (수집 전 검증 실패)
 * - 수집을 시작하면 결과(성공·실패)와 관계없이 쿨다운이 유지된다
 * - 모든 연동이 다른 동기화와 겹쳐 아무것도 실행하지 못했으면 쿨다운을 돌려주고 "이미 동기화 중"으로 응답한다
 * 계정 단위 동시 실행 방지는 StockSyncService의 동기화 락이 맡는다 (최초 연동·배치와 같은 락)
 */
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
        List<ConnectedAccount> accounts = connectedAccountRepository
                .findAllByUserIdAndBusinessTypeAndStatusOrderByIdAsc(userId, BusinessType.ST, ConnectedAccountStatus.ACTIVE);
        if (accounts.isEmpty()) {
            throw new CodefAccountNotFoundException("새로고침할 증권 연동이 없습니다.");
        }

        String key = cooldownKey(userId);
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", COOLDOWN))) {
            throw new RefreshCooldownException(remainingSeconds(key));
        }
        LocalDateTime nextAvailableAt = LocalDateTime.now(KST).plus(COOLDOWN);

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

        if (results.stream().allMatch(r -> "LOCKED".equals(r.status()))) {
            redisTemplate.delete(key); // 아무것도 수집하지 않았으니 쿨다운을 쓰지 않은 것으로 본다
            throw new CodefSyncLockConflictException();
        }
        return new StockRefreshResponse(results, LocalDateTime.now(KST), nextAvailableAt);
    }

    static String cooldownKey(Long userId) {
        return COOLDOWN_PREFIX + userId + ":STOCK";
    }

    // TTL 조회 사이에 키가 만료됐을 수 있으므로 최소 1초로 응답한다
    private long remainingSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 1 ? 1 : ttl;
    }
}
