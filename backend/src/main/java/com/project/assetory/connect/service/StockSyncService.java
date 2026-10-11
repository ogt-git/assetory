package com.project.assetory.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.codef.CodefApiClient;
import com.project.assetory.connect.codef.CodefErrorClassifier;
import com.project.assetory.connect.codef.CodefErrorType;
import com.project.assetory.connect.codef.stock.ParsedHolding;
import com.project.assetory.connect.codef.stock.ParsedStockAccount;
import com.project.assetory.connect.codef.stock.StockAssetParser;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.SyncJob;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefAccountNotFoundException;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.SyncJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 증권 연동 계정 1개 동기화 — 최초 연동(BACKFILL)·수동 새로고침·배치(HOLDING)가 모두 이 서비스를 호출한다.
 *
 * 1. 동기화 락 (codef:lock:{connectedAccountId}, 300초, UUID 소유 토큰)
 * 2. sync_jobs RUNNING 기록 (커밋)
 * 3. CODEF 종합자산 조회 — DB 트랜잭션 밖. 재시도 가능 유형(NETWORK·TRANSIENT)만 최대 3회
 * 4. 성공 + 응답 완전 → 한 트랜잭션에서 예수금·보유 종목 반영(응답에 없는 종목 삭제), last_synced_at 갱신, SUCCESS
 *    실패·응답 불완전 → 기존 assets·holdings는 그대로 두고 FAILED + 오류 유형별 상태 전이
 */
@Slf4j
@Service
public class StockSyncService {

    static final String STOCK_PRODUCT_URL = "/v1/kr/stock/a/account/financial-assets";
    static final String SYNC_LOCK_PREFIX = "codef:lock:";
    // 최대 동기화 시간보다 길게: CODEF 호출(연결 10초 + 읽기 10초) × 3회 + 토큰 발급 1회 + 대기 1·2초 ≈ 83초 < 300초
    private static final Duration SYNC_LOCK_TTL = Duration.ofSeconds(300);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_ATTEMPTS = 3;
    private static final String CODEF_SUCCESS = "CF-00000";
    private static final String INCOMPLETE_MESSAGE = "금융기관 응답이 일부 누락되어 이번 수집 결과를 저장하지 않았습니다.";

    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    public record SyncResult(Long syncJobId, SyncJobStatus status, int collectedCount, CodefErrorType errorType) {}

    private final CodefApiClient codefApiClient;
    private final ObjectMapper objectMapper;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final SyncJobRepository syncJobRepository;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long[] retryBackoffMs;

    public StockSyncService(CodefApiClient codefApiClient, ObjectMapper objectMapper,
                            ConnectedAccountRepository connectedAccountRepository, AssetRepository assetRepository,
                            HoldingRepository holdingRepository, SyncJobRepository syncJobRepository,
                            StringRedisTemplate redisTemplate, TransactionTemplate transactionTemplate,
                            @Value("${assetory.codef.sync.retry-backoff-ms:1000,2000}") long[] retryBackoffMs) {
        this.codefApiClient = codefApiClient;
        this.objectMapper = objectMapper;
        this.connectedAccountRepository = connectedAccountRepository;
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.syncJobRepository = syncJobRepository;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
        this.retryBackoffMs = retryBackoffMs;
    }

    public SyncResult sync(Long connectedAccountId, SyncJobType jobType) {
        ConnectedAccount account = connectedAccountRepository.findById(connectedAccountId)
                .filter(a -> a.getBusinessType() == BusinessType.ST)
                .orElseThrow(() -> new CodefAccountNotFoundException("동기화할 증권 연동 계정이 없습니다."));
        if (!account.isActive()) {
            throw new IllegalStateException("연동 상태가 정상이 아니어서 수집할 수 없습니다. 다시 연결해주세요.");
        }

        String lockKey = SYNC_LOCK_PREFIX + connectedAccountId;
        String lockToken = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, SYNC_LOCK_TTL))) {
            throw new CodefSyncLockConflictException();
        }

        try {
            SyncJob job = syncJobRepository.save(
                    SyncJob.start(account, jobType, LocalDate.now(KST), LocalDateTime.now(KST)));
            return runJob(job.getId(), connectedAccountId);
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
        }
    }

    private SyncResult runJob(Long jobId, Long connectedAccountId) {
        Asset asset = assetRepository.findByConnectedAccountIdAndAssetType(connectedAccountId, AssetType.SECURITIES)
                .orElse(null);
        if (asset == null || asset.getAccountNumber() == null) {
            return fail(jobId, connectedAccountId, CodefErrorType.UNKNOWN, "증권 계좌번호가 등록되지 않았습니다. 다시 연결해주세요.");
        }

        ConnectedAccount account = connectedAccountRepository.findWithInstitutionById(connectedAccountId).orElseThrow();
        String organization = account.getInstitution().getCodefOrgCode();

        JsonNode root;
        try {
            root = requestWithRetry(account, organization, asset.getAccountNumber());
        } catch (Exception e) {
            // 메시지에 요청 값이 섞일 수 있어 예외 종류만 남긴다
            log.warn("[StockSync] CODEF 호출 실패 connectedAccountId={} error={}", connectedAccountId, e.getClass().getSimpleName());
            return fail(jobId, connectedAccountId, CodefErrorType.UNKNOWN, CodefErrorType.UNKNOWN.getUserMessage());
        }

        String resultCode = root.path("result").path("code").asText();
        if (!CODEF_SUCCESS.equals(resultCode)) {
            CodefErrorType type = CodefErrorClassifier.classify(resultCode);
            if (type == CodefErrorType.EMPTY_RESULT) {
                // 조회 결과 없음 — 정상으로 보되 기존 데이터는 건드리지 않는다 (원본 동작)
                return succeed(jobId, connectedAccountId, null, null, 0);
            }
            log.warn("[StockSync] CODEF 실패 connectedAccountId={} code={} type={}", connectedAccountId, resultCode, type);
            return fail(jobId, connectedAccountId, type, type.getUserMessage());
        }

        ParsedStockAccount parsed = StockAssetParser.parse(root.path("data"), organization);
        log.info("[StockSync] 파싱 connectedAccountId={} 보유={} 매도제외={} 해외제외={} 불완전={}",
                connectedAccountId, parsed.holdings().size(), parsed.soldOutCount(), parsed.foreignCount(), parsed.invalidCount());
        if (!parsed.complete()) {
            return fail(jobId, connectedAccountId, CodefErrorType.UNKNOWN, INCOMPLETE_MESSAGE);
        }
        return succeed(jobId, connectedAccountId, asset.getId(), parsed, parsed.holdings().size());
    }

    private JsonNode requestWithRetry(ConnectedAccount account, String organization, String accountNumber) throws Exception {
        Long connectedAccountId = account.getId();
        HashMap<String, Object> params = new HashMap<>();
        params.put("connectedId", account.getConnectedId());
        params.put("organization", organization);
        params.put("account", accountNumber);
        if (account.getAccountPassword() != null && !account.getAccountPassword().isBlank()) {
            params.put("accountPassword", codefApiClient.encryptRSA(account.getAccountPassword()));
        }

        JsonNode root = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            root = objectMapper.readTree(codefApiClient.requestProduct(STOCK_PRODUCT_URL, params));
            String code = root.path("result").path("code").asText();
            if (CODEF_SUCCESS.equals(code) || !CodefErrorClassifier.classify(code).isRetryable() || attempt == MAX_ATTEMPTS) {
                return root;
            }
            log.warn("[StockSync] 일시 오류 재시도 {}/{} connectedAccountId={} code={}", attempt, MAX_ATTEMPTS, connectedAccountId, code);
            sleep(attempt);
        }
        return root;
    }

    private SyncResult succeed(Long jobId, Long connectedAccountId, Long assetId, ParsedStockAccount parsed, int count) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(KST);
            if (parsed != null) {
                Asset asset = assetRepository.findById(assetId).orElseThrow();
                // 완전한 응답만 여기 온다 — 예수금이 빠진 응답은 FAILED로 끝나고 기존 값을 오늘 값으로 쓰지 않는다
                asset.updateBalance(parsed.depositReceived());
                reconcileHoldings(asset, parsed.holdings());
            }
            connectedAccountRepository.findById(connectedAccountId).orElseThrow().markSynced(now);
            syncJobRepository.findById(jobId).orElseThrow().succeed(count, now);
        });
        return new SyncResult(jobId, SyncJobStatus.SUCCESS, count, null);
    }

    // UNIQUE(asset_id, ticker) 기준 upsert, 응답에 없는 기존 종목은 매도로 보고 삭제
    private void reconcileHoldings(Asset asset, List<ParsedHolding> parsedHoldings) {
        Map<String, Holding> existing = holdingRepository.findAllByAssetId(asset.getId()).stream()
                .collect(Collectors.toMap(Holding::getTicker, Function.identity()));
        Set<String> tickers = parsedHoldings.stream().map(ParsedHolding::ticker).collect(Collectors.toSet());

        existing.values().stream()
                .filter(holding -> !tickers.contains(holding.getTicker()))
                .forEach(holdingRepository::delete);
        holdingRepository.flush(); // 같은 종목코드가 삭제 후 다시 들어오는 경우 UNIQUE 충돌 방지

        for (ParsedHolding parsed : parsedHoldings) {
            Holding holding = existing.getOrDefault(parsed.ticker(), Holding.create(asset, parsed.ticker()));
            // security_type은 CODEF가 구분해 주지 않으므로 NULL (M-1 — 시세 수집 단계에서 다른 소스로 채움)
            holding.update(parsed.stockName(), null, parsed.quantity(), parsed.avgBuyPrice(),
                    parsed.currentPrice(), parsed.evalAmount(), parsed.profitLoss(), parsed.profitLossRate());
            if (holding.getId() == null) {
                holdingRepository.save(holding); // 값을 채운 뒤 저장 (quantity NOT NULL)
            }
        }
    }

    // 실패 — 기존 자산 데이터는 건드리지 않고 sync_jobs FAILED + 상태 전이만 적용
    private SyncResult fail(Long jobId, Long connectedAccountId, CodefErrorType type, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            type.statusTransition().ifPresent(next ->
                    connectedAccountRepository.findById(connectedAccountId).orElseThrow().changeStatus(next));
            syncJobRepository.findById(jobId).orElseThrow().fail(type, message, LocalDateTime.now(KST));
        });
        return new SyncResult(jobId, SyncJobStatus.FAILED, 0, type);
    }

    private void sleep(int attempt) {
        long millis = retryBackoffMs.length == 0 ? 0 : retryBackoffMs[Math.min(attempt - 1, retryBackoffMs.length - 1)];
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
