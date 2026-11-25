package com.project.assetory.snapshot.service;

import com.project.assetory.market.service.ClosePriceConfirmResult;
import com.project.assetory.market.service.StockPriceCollectService;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotJudge;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;
import com.project.assetory.snapshot.domain.SnapshotRevaluator;
import com.project.assetory.snapshot.domain.SnapshotRevaluator.PriceCorrection;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 잠정 종가 보정 (01 §14 #23, kis-verification V4) — 16:00 배치가 읽은 한투 종가는 잠정값이고 다음 거래일 아침에 확정된다.
 * 1. 그날 기록에 있는 종목의 종가를 한투에서 다시 받아 stock_prices를 확정값으로 맞춘다(StockPriceCollectService.confirmClose)
 * 2. 종가가 바뀐 종목이 있는 그날 기록을 확정 종가로 다시 평가한다(SnapshotRevaluator) — 수량 · 수집 상태 · 수집 시각은 그대로
 * 3. 전일 대비 · 시장가격 영향 · 기타 · 종목 전일 대비는 저장 때와 같은 공식(SnapshotJudge)으로 다시 계산한다
 * 4. 보정이 늦어 그 기록을 비교 기준으로 쓴 뒤 기록이 이미 있으면, 그 기록의 비교값도 다시 계산한다(그날 값은 그대로)
 * 같은 날을 다시 돌려도 종가가 이미 확정값이면 아무것도 바꾸지 않는다(멱등).
 */
@Slf4j
@Service
public class SnapshotRevaluationService {

    private final StockPriceCollectService priceService;
    private final AssetSnapshotRepository snapshotRepository;
    private final AssetDetailSnapshotRepository detailRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public SnapshotRevaluationService(StockPriceCollectService priceService, AssetSnapshotRepository snapshotRepository,
                                      AssetDetailSnapshotRepository detailRepository,
                                      TransactionTemplate transactionTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.priceService = priceService;
        this.snapshotRepository = snapshotRepository;
        this.detailRepository = detailRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * 스케줄 실행 — 오늘보다 이전의 가장 최근 기록 날짜를 보정한다(다음 거래일 아침 = 직전 거래일 기록).
     * 휴장일에 돌아도 같은 날짜를 다시 확인할 뿐이라 바뀌는 것이 없다(멱등).
     */
    public Optional<RevaluationSummary> revalueLatest() {
        LocalDate target = snapshotRepository.findLatestSnapshotDateBefore(LocalDate.now(clock));
        if (target == null) {
            log.info("[Snapshot] 종가 보정 — 보정할 기록 없음");
            return Optional.empty();
        }
        return Optional.of(revalue(target));
    }

    public RevaluationSummary revalue(LocalDate tradeDate) {
        List<String> tickers = detailRepository.findDistinctTickersBySnapshotDate(tradeDate);
        ClosePriceConfirmResult confirm = priceService.confirmClose(tradeDate, tickers);
        if (confirm.changes().isEmpty()) {
            log.info("[Snapshot] 종가 보정 — 바뀐 종가 없음 tradeDate={} 확인={}", tradeDate, confirm.checked());
            return new RevaluationSummary(tradeDate, confirm, 0, 0);
        }
        Map<String, PriceCorrection> corrections = new HashMap<>();
        confirm.changes().forEach((ticker, change) ->
                corrections.put(ticker, new PriceCorrection(change.provisional(), change.confirmed())));

        List<Long> userIds = transactionTemplate.execute(status -> snapshotRepository.findAllBySnapshotDate(tradeDate)
                .stream().map(snapshot -> snapshot.getUser().getId()).toList());
        int revalued = 0;
        int following = 0;
        for (Long userId : userIds) {
            // 사용자 단위 트랜잭션 — 한 사용자의 실패가 다른 사용자 보정을 막지 않는다
            try {
                Integer recomputed = transactionTemplate.execute(status -> revalueUser(userId, tradeDate, corrections));
                if (recomputed != null) {
                    revalued++;
                    following += recomputed;
                }
            } catch (RuntimeException e) {
                log.warn("[Snapshot] 종가 보정 실패 userId={} tradeDate={} error={}", userId, tradeDate, e.getClass().getSimpleName());
            }
        }

        Counter.builder("snapshot.revaluation").register(meterRegistry).increment(revalued);
        log.info("[Snapshot] 종가 보정 tradeDate={} 종가변경={} 기록보정={} 뒤기록재계산={}",
                tradeDate, confirm.changes().size(), revalued, following);
        return new RevaluationSummary(tradeDate, confirm, revalued, following);
    }

    /** @return 다시 평가하지 않았으면 null, 했으면 비교값을 다시 계산한 뒤 기록 수 */
    private Integer revalueUser(Long userId, LocalDate date, Map<String, PriceCorrection> corrections) {
        AssetSnapshot snapshot = snapshotRepository.findByUserIdAndSnapshotDate(userId, date).orElseThrow();
        List<AssetDetailSnapshot> rows = detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, date);
        Optional<SnapshotJudgement> revalued = SnapshotRevaluator.revalue(stored(snapshot, rows), corrections);
        if (revalued.isEmpty()) {
            return null;
        }
        SnapshotJudgement judgement = revalued.get();

        // 그날 기록 — 저장 때와 같은 기준(직전 COMPLETE)으로 비교값을 다시 계산
        Optional<AssetSnapshot> previous = snapshotRepository
                .findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(userId, SnapshotStatus.COMPLETE, date);
        Map<DetailKey, Detail> base = previous
                .map(p -> index(detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, p.getSnapshotDate()).stream()
                        .map(SnapshotRevaluationService::detail).toList()))
                .orElseGet(Map::of);
        DayChange dayChange = SnapshotJudge.dayChange(judgement,
                previous.map(AssetSnapshot::getSnapshotDate).orElse(null),
                previous.map(AssetSnapshot::getTotalAsset).orElse(null));
        MarketChange marketChange = SnapshotJudge.marketChange(List.copyOf(base.values()), judgement.details(), dayChange);
        snapshot.apply(judgement, snapshot.getCollectedAt(), dayChange, marketChange);
        applyDetails(rows, index(judgement.details()), base);

        // 보정이 늦은 경우 — 이 기록을 비교 기준으로 쓴 뒤 기록의 비교값만 다시 계산(그날 값은 그대로)
        int following = 0;
        if (snapshot.getStatus() == SnapshotStatus.COMPLETE) {
            Map<DetailKey, Detail> newBase = index(judgement.details());
            for (AssetSnapshot next : snapshotRepository.findAllByUserIdAndBaseSnapshotDate(userId, date)) {
                List<AssetDetailSnapshot> nextRows = detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, next.getSnapshotDate());
                SnapshotJudgement nextJudgement = stored(next, nextRows);
                DayChange nextDay = SnapshotJudge.dayChange(nextJudgement, date, judgement.totalAsset());
                MarketChange nextMarket = SnapshotJudge.marketChange(judgement.details(), nextJudgement.details(), nextDay);
                next.apply(nextJudgement, next.getCollectedAt(), nextDay, nextMarket);
                applyDetails(nextRows, index(nextJudgement.details()), newBase);
                following++;
            }
        }
        return following;
    }

    // 저장된 기록을 판정 형태로 — 재평가 · 공식 재사용의 입력
    private static SnapshotJudgement stored(AssetSnapshot snapshot, List<AssetDetailSnapshot> rows) {
        return new SnapshotJudgement(snapshot.getStatus(), snapshot.getTotalAsset(), snapshot.getSecuritiesAmount(),
                snapshot.getDepositAmount(), snapshot.getCashAmount(),
                rows.stream().map(SnapshotRevaluationService::detail).toList());
    }

    private static Detail detail(AssetDetailSnapshot row) {
        return new Detail(row.getAsset().getId(), row.getTicker(), row.getAmount(), row.getQuantity(), row.getPrice());
    }

    // 행 값과 기준일 대비 변화액 — 저장 때와 같은 규칙(기준일 같은 (asset_id, ticker) 행이 없으면 NULL)
    private static void applyDetails(List<AssetDetailSnapshot> rows, Map<DetailKey, Detail> values, Map<DetailKey, Detail> base) {
        for (AssetDetailSnapshot row : rows) {
            DetailKey key = new DetailKey(row.getAsset().getId(), row.getTicker());
            Detail value = values.get(key);
            Detail before = base.get(key);
            row.apply(value.amount(), value.quantity(), value.price(),
                    before == null ? null : value.amount().subtract(before.amount()));
        }
    }

    private static Map<DetailKey, Detail> index(List<Detail> details) {
        Map<DetailKey, Detail> map = new HashMap<>();
        details.forEach(detail -> map.put(new DetailKey(detail.assetId(), detail.ticker()), detail));
        return map;
    }

    private record DetailKey(Long assetId, String ticker) {}
}
