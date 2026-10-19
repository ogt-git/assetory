package com.project.assetory.market.service;

import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.market.config.MarketProperties;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.kis.KisStockInfo;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.StockPriceCollectResult.FailReason;
import com.project.assetory.market.service.StockPriceCollectResult.SecurityTypeResult;
import com.project.assetory.market.service.StockPriceCollectResult.TickerResult;
import com.project.assetory.market.service.StockPriceCollectResult.TickerStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 보유 종목 시세 수집 (FR-MK-01 · 05 · 07, stock_prices) + STOCK/ETF 판별 (FR-CN-03, holdings.security_type).
 * - 대상: 활성 자산 보유 종목의 합집합. 종목마다 일자별 시세 1회 → 기준 날짜 행만 upsert
 * - 장 마감 가드 전에는 오늘 행을 저장하지 않는다 — 장중 종가 칸에는 현재가가 온다 (V4)
 * - 기준 날짜 행이 없으면 저장하지 않는다 — 직전 값 복사 · 0 채움 금지 (AGENTS 5-C)
 * - 종목 단위로 실패를 격리하고, 호출 실패가 연속 max-consecutive-failures회면 남은 호출을 멈춘다
 */
@Slf4j
@Service
public class StockPriceCollectService {

    private final KisApiClient kis;
    private final HoldingRepository holdingRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public StockPriceCollectService(KisApiClient kis, HoldingRepository holdingRepository,
                                    StockPriceRepository stockPriceRepository, MarketProperties properties,
                                    Clock clock, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.kis = kis;
        this.holdingRepository = holdingRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public StockPriceCollectResult collect(LocalDate tradeDate) {
        Map<String, String> targets = targetTickers();
        Map<String, TickerResult> results = new LinkedHashMap<>();

        if (!isClosed(tradeDate)) {
            targets.keySet().forEach(ticker -> results.put(ticker, TickerResult.failed(FailReason.CLOSE_GUARD)));
            log.warn("[Market] 장 마감 가드 — 수집하지 않음 tradeDate={} 종목={}", tradeDate, targets.size());
            recordMetrics(results);
            return new StockPriceCollectResult(tradeDate, results, false, true, SecurityTypeResult.SKIPPED);
        }

        StockInfoLookup lookup = new StockInfoLookup(kis);
        int consecutiveFailures = 0;
        boolean aborted = false;
        for (Map.Entry<String, String> target : targets.entrySet()) {
            String ticker = target.getKey();
            if (aborted) {
                results.put(ticker, TickerResult.failed(FailReason.ABORTED));
                continue;
            }
            List<KisDailyPrice> prices;
            try {
                prices = kis.getDailyPrices(ticker);
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                results.put(ticker, TickerResult.failed(FailReason.API_ERROR));
                if (++consecutiveFailures >= properties.maxConsecutiveFailures()) {
                    aborted = true;
                    log.warn("[Market] 시세 호출 연속 {}회 실패 — 남은 종목 호출 중단 tradeDate={}", consecutiveFailures, tradeDate);
                }
                continue;
            }
            results.put(ticker, saveTradeDate(ticker, target.getValue(), tradeDate, prices, lookup));
        }

        // 연속 실패로 멈췄다면 한투 장애일 가능성이 커서 판별 호출도 하지 않는다
        SecurityTypeResult securityTypes = aborted ? SecurityTypeResult.SKIPPED : classifySecurityTypes(lookup);
        StockPriceCollectResult result = new StockPriceCollectResult(tradeDate, results, aborted, false, securityTypes);
        recordMetrics(results);
        log.info("[Market] 시세 수집 tradeDate={} 대상={} 성공={} 거래정지={} 실패={} 중단={} 판별={}/미분류={}/실패={}",
                tradeDate, targets.size(), result.count(TickerStatus.COLLECTED), result.count(TickerStatus.HALTED),
                result.count(TickerStatus.FAILED), aborted,
                securityTypes.classified(), securityTypes.unknownGroup(), securityTypes.failed());
        return result;
    }

    /**
     * 최초 연동 소급 (FR-MK-04) — 같은 응답의 최근 거래일 행을 전부 upsert. 종목 추이 · 과거 이벤트 용도이고 사용자 자산 기록은 만들지 않는다.
     * 직전 행이 있는 거래일만 저장하고(가장 오래된 행 제외), 오늘 행은 장 마감 가드를 적용한다. 호출하는 곳은 이후 단계에서 연결한다.
     *
     * @return 저장한 행 수
     */
    public int backfill(String ticker, String stockName) {
        List<KisDailyPrice> prices = kis.getDailyPrices(ticker);
        String name = stockName != null ? stockName : kis.getStockInfo(ticker).name();
        if (name == null) {
            log.warn("[Market] 소급 — 종목명 없음 ticker={}", ticker);
            return 0;
        }
        int saved = 0;
        for (int i = 0; i + 1 < prices.size(); i++) {
            KisDailyPrice row = prices.get(i);
            if (!isClosed(row.tradeDate())) continue;
            upsert(ticker, name, row, prices.get(i + 1));
            saved++;
        }
        return saved;
    }

    private TickerResult saveTradeDate(String ticker, String stockName, LocalDate tradeDate,
                                       List<KisDailyPrice> prices, StockInfoLookup lookup) {
        for (int i = 0; i < prices.size(); i++) {
            KisDailyPrice row = prices.get(i);
            if (!row.tradeDate().equals(tradeDate)) continue;
            // 응답은 최신 거래일부터 — 바로 다음 행이 직전 거래일
            if (i + 1 >= prices.size()) {
                return TickerResult.failed(FailReason.NO_PREVIOUS_DAY);
            }
            String name = stockName != null ? stockName : lookup.get(ticker).map(KisStockInfo::name).orElse(null);
            if (name == null) {
                return TickerResult.failed(FailReason.NO_STOCK_NAME);
            }
            try {
                upsert(ticker, name, row, prices.get(i + 1));
                return TickerResult.collected();
            } catch (RuntimeException e) {
                log.warn("[Market] 시세 저장 실패 ticker={} tradeDate={} error={}", ticker, tradeDate, e.getClass().getSimpleName());
                return TickerResult.failed(FailReason.SAVE_ERROR);
            }
        }

        // 기준 날짜 행이 없다 — 최신 행이 기준 날짜보다 이전이고 거래정지면 HALTED, 아니면 FAILED
        boolean stale = !prices.isEmpty() && prices.get(0).tradeDate().isBefore(tradeDate);
        // TODO(01-§14 #24 V3 미검증): 실제 거래정지 종목에서 tr_stop_yn=Y가 오는지 아직 확인하지 못했다
        if (stale && lookup.get(ticker).map(info -> "Y".equals(info.tradingHalted())).orElse(false)) {
            return TickerResult.halted();
        }
        return TickerResult.failed(FailReason.NO_ROW_FOR_DATE);
    }

    private void upsert(String ticker, String stockName, KisDailyPrice row, KisDailyPrice previous) {
        transactionTemplate.executeWithoutResult(status -> {
            StockPrice price = stockPriceRepository.findByTickerAndTradeDate(ticker, row.tradeDate())
                    .orElseGet(() -> StockPrice.create(ticker, row.tradeDate()));
            // change_rate는 한투 prdy_ctrt 그대로 (기준가 대비 — 01 §14 #5). 종가 ÷ 전일 종가로 계산하지 않는다
            price.update(stockName, row.closePrice(), previous.closePrice(), row.changeRate(), row.volume());
            stockPriceRepository.save(price);
        });
    }

    // EF → ETF, ST → STOCK, 그 외(ETN 등)는 NULL 유지 — 임의로 STOCK으로 넣지 않는다
    private SecurityTypeResult classifySecurityTypes(StockInfoLookup lookup) {
        int classified = 0;
        int unknownGroup = 0;
        int failed = 0;
        for (String ticker : holdingRepository.findActiveTickersWithoutSecurityType()) {
            Optional<KisStockInfo> info = lookup.get(ticker);
            if (info.isEmpty()) {
                failed++;
                continue;
            }
            String group = info.get().securityGroupCode();
            SecurityType type = "EF".equals(group) ? SecurityType.ETF : "ST".equals(group) ? SecurityType.STOCK : null;
            if (type == null) {
                unknownGroup++;
                log.warn("[Market] STOCK/ETF 판별 불가 — NULL 유지 ticker={} group={}", ticker, group);
                continue;
            }
            transactionTemplate.executeWithoutResult(status ->
                    holdingRepository.findAllByTickerAndSecurityTypeIsNull(ticker)
                            .forEach(holding -> holding.assignSecurityType(type)));
            classified++;
        }
        return new SecurityTypeResult(classified, unknownGroup, failed);
    }

    // 종목코드 → 종목명 (holdings 기준, 같은 종목의 첫 번째 비어 있지 않은 이름). 종목코드 오름차순
    private Map<String, String> targetTickers() {
        Map<String, String> targets = new LinkedHashMap<>();
        for (HoldingRepository.TickerName row : holdingRepository.findActiveTickerNames()) {
            String name = row.getStockName() == null || row.getStockName().isBlank() ? null : row.getStockName();
            targets.merge(row.getTicker(), name == null ? "" : name, (old, now) -> old.isEmpty() ? now : old);
        }
        targets.replaceAll((ticker, name) -> name.isEmpty() ? null : name);
        return targets;
    }

    // market.price.collect — 종목별 결과 건수 (result=collected|halted|failed). 종목코드 태그는 붙이지 않는다
    private void recordMetrics(Map<String, TickerResult> results) {
        results.values().forEach(r -> Counter.builder("market.price.collect")
                .tag("result", r.status().name().toLowerCase())
                .register(meterRegistry).increment());
    }

    // 장 마감 가드 — 미래 날짜는 불가, 오늘은 그날의 가드 시각 이후만(장 마감 지연일은 지연 가드)
    private boolean isClosed(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) return false;
        return !date.equals(today) || !LocalTime.now(clock).isBefore(properties.closeGuardTime(date));
    }
}
