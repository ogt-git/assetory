package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.AssetRepository.ManualSnapshotTarget;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.codef.CodefErrorType;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.ConnectedAccountRepository.SnapshotTarget;
import com.project.assetory.connect.service.StockSyncService;
import com.project.assetory.market.config.MarketProperties;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.domain.TradingDayStatus;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.StockPriceCollectResult;
import com.project.assetory.market.service.StockPriceCollectResult.TickerResult;
import com.project.assetory.market.service.StockPriceCollectResult.TickerStatus;
import com.project.assetory.market.service.StockPriceCollectService;
import com.project.assetory.market.service.TradingDayService;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 영업일 16:00 일일 자산 기록 배치 (04 배치 일정, FR-AS-02, 05 §11.2).
 * 거래일 판정 → CODEF 증권 수집 → 보유 종목 시세 → 사용자별 스냅샷 판정 · 저장.
 * - 기준 시각 T와 snapshotDate는 시작할 때 정하고 끝까지 바꾸지 않는다
 * - 판정 입력은 이번 실행의 결과만: 동기화가 SUCCESS인 계좌만 직후 DB의 assets · holdings 값을 읽는다(그 외 계좌는 읽지 않음 — 이월 방지)
 * - 휴장일은 아무것도 만들지 않고, 판정 불가(UNKNOWN)는 실행 전체를 중단한다(휴장으로 처리하지 않음)
 * - 장 마감 지연일(수능일)은 16:00 실행을 건너뛰고(장중 가격을 종가로 저장하지 않음), runLateClose()가 마감 뒤 한 번 실행한다
 * - 실행 단위 격리: 연동 계정 · 종목 · 사용자 단위로 실패를 가두고 다음으로 넘어간다
 * 스케줄러는 단일 스레드(Boot 기본 TaskScheduler)라 자기 자신과 겹치지 않는다.
 */
@Slf4j
@Service
public class DailyAssetBatchService {

    private final TradingDayService tradingDayService;
    private final StockSyncService stockSyncService;
    private final StockPriceCollectService stockPriceCollectService;
    private final SnapshotSaveService snapshotSaveService;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketProperties marketProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final long lockRetryWaitMs;

    public DailyAssetBatchService(TradingDayService tradingDayService, StockSyncService stockSyncService,
                                  StockPriceCollectService stockPriceCollectService, SnapshotSaveService snapshotSaveService,
                                  ConnectedAccountRepository connectedAccountRepository, AssetRepository assetRepository,
                                  HoldingRepository holdingRepository, StockPriceRepository stockPriceRepository,
                                  MarketProperties marketProperties, MeterRegistry meterRegistry, Clock clock,
                                  @Value("${assetory.sync.batch.lock-retry-wait-ms:10000}") long lockRetryWaitMs) {
        this.tradingDayService = tradingDayService;
        this.stockSyncService = stockSyncService;
        this.stockPriceCollectService = stockPriceCollectService;
        this.snapshotSaveService = snapshotSaveService;
        this.connectedAccountRepository = connectedAccountRepository;
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.marketProperties = marketProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.lockRetryWaitMs = lockRetryWaitMs;
    }

    public record BatchSummary(TradingDayStatus tradingDay, int accounts, Map<AccountStatus, Integer> accountResults,
                               int priceCollected, int priceHalted, int priceFailed,
                               Map<SnapshotStatus, Integer> snapshots, int userErrors) {}

    // 이번 실행에서 계좌 1개를 수집한 결과 — SUCCESS일 때만 예수금 · 종목(종목코드 · 수량)을 담는다
    private record CollectedAccount(Long userId, Long assetId, AccountStatus status,
                                    BigDecimal deposit, List<HoldingQuantity> holdings) {}

    private record HoldingQuantity(String ticker, BigDecimal quantity) {}

    /**
     * 장 마감 지연일 전용 트리거 — 지연일이 아니면 아무것도 하지 않는다.
     *
     * @return 실행했으면 요약, 지연일이 아니면 empty
     */
    public Optional<BatchSummary> runLateClose() {
        if (!marketProperties.isLateCloseDate(LocalDate.now(clock))) {
            return Optional.empty();
        }
        return Optional.of(run());
    }

    public BatchSummary run() {
        long startNanos = System.nanoTime();
        LocalDateTime batchTime = LocalDateTime.now(clock);
        LocalDate snapshotDate = batchTime.toLocalDate();

        // 0. 그날 장 마감 가드 전이면 수집하지 않는다 (장중 가격을 종가로 저장하지 않음)
        if (batchTime.toLocalTime().isBefore(marketProperties.closeGuardTime(snapshotDate))) {
            if (marketProperties.isLateCloseDate(snapshotDate)) {
                // 장 마감 지연일(수능일)의 16:00 실행 — 지연 실행(runLateClose)이 기록한다
                log.info("[DailyBatch] 장 마감 지연일 — 이번 실행 건너뜀 (지연 실행에서 기록) date={} guard={}",
                        snapshotDate, marketProperties.closeGuardTime(snapshotDate));
                countRun("deferred");
            } else {
                // 일반일인데 가드 전 — 미룰 실행이 없어 이대로면 그날 기록이 빠진다(cron 오설정 등). 거래일 판정 불가와 같이 중단으로 남긴다
                log.error("[DailyBatch] 장 마감 가드 전 실행 — 이번 실행 전체 중단 (cron 설정 확인 필요) date={} time={} guard={}",
                        snapshotDate, batchTime.toLocalTime(), marketProperties.closeGuardTime(snapshotDate));
                countRun("aborted");
            }
            return emptySummary(TradingDayStatus.UNKNOWN);
        }

        // 1. 거래일 판정
        TradingDayStatus tradingDay = tradingDayService.check(snapshotDate);
        if (tradingDay == TradingDayStatus.HOLIDAY) {
            log.info("[DailyBatch] 휴장일 — 수집 · 기록하지 않음 date={}", snapshotDate);
            countRun("holiday");
            return emptySummary(tradingDay);
        }
        if (tradingDay == TradingDayStatus.UNKNOWN) {
            log.error("[DailyBatch] 거래일 판정 불가 — 이번 실행 전체 중단 (휴장으로 처리하지 않음) date={}", snapshotDate);
            countRun("aborted");
            return emptySummary(tradingDay);
        }

        // 판정 대상은 T 기준으로 고정 — 연동은 T 이전 생성 · 해지 아님, 수기 자산은 T 시점 값
        List<SnapshotTarget> accountTargets = connectedAccountRepository.findSnapshotTargets(
                BusinessType.ST, ConnectedAccountStatus.DISCONNECTED, batchTime);
        List<ManualSnapshotTarget> manualTargets = assetRepository.findActiveManualSnapshotTargets();

        // 2. CODEF 증권 수집 — 연동 계정 단위 실패 격리
        List<CollectedAccount> collected = new ArrayList<>();
        for (SnapshotTarget target : accountTargets) {
            collected.add(collect(target));
        }

        // 3. 보유 종목 시세 — CODEF 수집 뒤라 이번에 새로 생긴 보유 종목도 포함된다
        StockPriceCollectResult prices = stockPriceCollectService.collect(snapshotDate);
        LocalDateTime collectedAt = LocalDateTime.now(clock);

        // 4. 사용자별 스냅샷 — 사용자 단위 실패 격리 (예외를 FAILED 스냅샷으로 바꾸지 않는다)
        Map<Long, List<Account>> accountsByUser = new HashMap<>();
        Map<String, Optional<BigDecimal>> priceCache = new HashMap<>();
        for (CollectedAccount account : collected) {
            accountsByUser.computeIfAbsent(account.userId(), id -> new ArrayList<>())
                    .add(toInput(account, prices, snapshotDate, priceCache));
        }
        Map<Long, List<Manual>> manualsByUser = new HashMap<>();
        for (ManualSnapshotTarget manual : manualTargets) {
            manualsByUser.computeIfAbsent(manual.getUserId(), id -> new ArrayList<>())
                    .add(new Manual(manual.getId(), manual.getAssetType(), manual.getBalance()));
        }

        TreeSet<Long> userIds = new TreeSet<>(accountsByUser.keySet());
        userIds.addAll(manualsByUser.keySet());
        Map<SnapshotStatus, Integer> snapshots = new EnumMap<>(SnapshotStatus.class);
        int userErrors = 0;
        for (Long userId : userIds) {
            try {
                SnapshotInput input = new SnapshotInput(snapshotDate, collectedAt,
                        accountsByUser.getOrDefault(userId, List.of()), manualsByUser.getOrDefault(userId, List.of()));
                snapshotSaveService.save(userId, input)
                        .map(AssetSnapshot::getStatus)
                        .ifPresent(status -> snapshots.merge(status, 1, Integer::sum));
            } catch (RuntimeException e) {
                userErrors++;
                log.error("[DailyBatch] 사용자 스냅샷 처리 실패 — 행을 만들지 않고 계속 userId={} error={}",
                        userId, e.getClass().getSimpleName());
            }
        }

        // 5. 종료
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        Timer.builder("batch.step.duration").tag("step", "collect").register(meterRegistry).record(elapsed);
        countRun("completed");
        Map<AccountStatus, Integer> accountResults = new EnumMap<>(AccountStatus.class);
        collected.forEach(a -> accountResults.merge(a.status(), 1, Integer::sum));
        BatchSummary summary = new BatchSummary(tradingDay, accountTargets.size(), accountResults,
                (int) prices.count(TickerStatus.COLLECTED), (int) prices.count(TickerStatus.HALTED),
                (int) prices.count(TickerStatus.FAILED), snapshots, userErrors);
        log.info("[DailyBatch] 완료 date={} 연동={} 계좌결과={} 시세(성공/정지/실패)={}/{}/{} 시세중단={} 스냅샷={} 사용자오류={} 소요={}ms",
                snapshotDate, summary.accounts(), accountResults, summary.priceCollected(), summary.priceHalted(),
                summary.priceFailed(), prices.aborted(), snapshots, userErrors, elapsed.toMillis());
        return summary;
    }

    private CollectedAccount collect(SnapshotTarget target) {
        Long assetId = assetRepository.findByConnectedAccountIdAndAssetType(target.getId(), AssetType.SECURITIES)
                .map(asset -> asset.getId()).orElse(null);
        // AUTH_FAILED · EXPIRED — 분모에는 넣되 수집하지 않는다 (05 §4.7)
        if (target.getStatus() != ConnectedAccountStatus.ACTIVE) {
            return new CollectedAccount(target.getUserId(), assetId, AccountStatus.EXCLUDED_AUTH, null, List.of());
        }

        StockSyncService.SyncResult result;
        try {
            result = syncWithLockRetry(target.getId());
        } catch (CodefSyncLockConflictException e) {
            log.warn("[DailyBatch] 동기화 락 충돌 — 재시도 후에도 실패 connectedAccountId={}", target.getId());
            return new CollectedAccount(target.getUserId(), assetId, AccountStatus.LOCK_FAILED, null, List.of());
        } catch (RuntimeException e) {
            log.warn("[DailyBatch] 동기화 중단 connectedAccountId={} error={}", target.getId(), e.getClass().getSimpleName());
            return new CollectedAccount(target.getUserId(), assetId, AccountStatus.FAILED, null, List.of());
        }

        if (result.status() != SyncJobStatus.SUCCESS) {
            return new CollectedAccount(target.getUserId(), assetId, AccountStatus.FAILED, null, List.of());
        }
        if (result.errorType() == CodefErrorType.EMPTY_RESULT) {
            // 값이 갱신되지 않았다 — DB에 남은 예전 값을 오늘 값으로 쓰지 않는다 (임시, CODEF 샘플 확인 후 재검토)
            return new CollectedAccount(target.getUserId(), assetId, AccountStatus.EMPTY_RESULT, null, List.of());
        }
        if (assetId == null) {
            return new CollectedAccount(target.getUserId(), null, AccountStatus.FAILED, null, List.of());
        }

        // 이번 동기화가 방금 저장한 값 — SUCCESS 계좌만 읽는다
        BigDecimal deposit = assetRepository.findById(assetId).map(asset -> asset.getBalance()).orElse(null);
        List<HoldingQuantity> holdings = holdingRepository.findAllByAssetId(assetId).stream()
                .map((Holding h) -> new HoldingQuantity(h.getTicker(), h.getQuantity()))
                .toList();
        return new CollectedAccount(target.getUserId(), assetId, AccountStatus.SUCCESS, deposit, holdings);
    }

    // 락 충돌이면 짧게 기다린 뒤 1회만 재시도 (05 §11.2 — 락 TTL 300초를 통째로 기다리지 않음)
    private StockSyncService.SyncResult syncWithLockRetry(Long connectedAccountId) {
        try {
            return stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);
        } catch (CodefSyncLockConflictException first) {
            log.info("[DailyBatch] 동기화 락 충돌 — {}ms 뒤 1회 재시도 connectedAccountId={}", lockRetryWaitMs, connectedAccountId);
            sleep(lockRetryWaitMs);
            return stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);
        }
    }

    private Account toInput(CollectedAccount account, StockPriceCollectResult prices, LocalDate snapshotDate,
                            Map<String, Optional<BigDecimal>> priceCache) {
        if (account.status() != AccountStatus.SUCCESS) {
            return Account.of(account.assetId(), account.status());
        }
        List<HoldingPrice> holdings = account.holdings().stream()
                .map(h -> holdingPrice(h, prices.tickers().get(h.ticker()), snapshotDate, priceCache))
                .toList();
        return Account.success(account.assetId(), account.deposit(), holdings);
    }

    // 종목 시세 결과 → 판정 입력. COLLECTED는 그날 종가, HALTED는 기준일 이전 최신 종가(없으면 실패로 판정됨), 그 외 FAILED
    private HoldingPrice holdingPrice(HoldingQuantity holding, TickerResult result, LocalDate snapshotDate,
                                      Map<String, Optional<BigDecimal>> priceCache) {
        if (result == null || result.status() == TickerStatus.FAILED) {
            return HoldingPrice.failed(holding.ticker(), holding.quantity());
        }
        if (result.status() == TickerStatus.HALTED) {
            BigDecimal previousClose = priceCache.computeIfAbsent("H:" + holding.ticker(), key ->
                    stockPriceRepository.findFirstByTickerAndTradeDateBeforeOrderByTradeDateDesc(holding.ticker(), snapshotDate)
                            .map(StockPrice::getClosePrice)).orElse(null);
            return HoldingPrice.halted(holding.ticker(), holding.quantity(), previousClose);
        }
        BigDecimal close = priceCache.computeIfAbsent("C:" + holding.ticker(), key ->
                stockPriceRepository.findByTickerAndTradeDate(holding.ticker(), snapshotDate)
                        .map(StockPrice::getClosePrice)).orElse(null);
        return close == null
                ? HoldingPrice.failed(holding.ticker(), holding.quantity())
                : HoldingPrice.collected(holding.ticker(), holding.quantity(), close);
    }

    // batch.run — 실행 결과 (completed / holiday / aborted / deferred). 05 §12 반영 필요
    private void countRun(String result) {
        Counter.builder("batch.run").tag("batch", "daily-asset").tag("result", result).register(meterRegistry).increment();
    }

    private static BatchSummary emptySummary(TradingDayStatus tradingDay) {
        return new BatchSummary(tradingDay, 0, Map.of(), 0, 0, 0, Map.of(), 0);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
