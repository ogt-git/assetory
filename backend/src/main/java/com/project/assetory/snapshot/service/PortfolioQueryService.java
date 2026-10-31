package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.PortfolioCalculator;
import com.project.assetory.snapshot.domain.PortfolioCalculator.HoldingInput;
import com.project.assetory.snapshot.domain.PortfolioCalculator.Profit;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.AssetChangeResult.LatestRecord;
import com.project.assetory.snapshot.service.PortfolioResult.AssetClassItem;
import com.project.assetory.snapshot.service.PortfolioResult.HoldingItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04) — 최근 COMPLETE 기록을 읽고, 계산은 PortfolioCalculator에 맡긴다.
 * - 평가금액 · 수량 · 단가 · 당일 변동은 그날 asset_detail_snapshots(한투 종가 × 수량), 총자산 · 자산군 금액은 그날 asset_snapshots.
 *   holdings의 CODEF 평가액 · 손익은 쓰지 않는다 (01 §14 평가 기준)
 * - 현재 holdings는 평균 매입가(원금)와 종목명 보조에만 쓴다. 매칭은 (asset_id, ticker)
 * - 최근 기록이 PARTIAL · FAILED면 최근 COMPLETE 기준, latestRecord로 날짜 · 상태를 함께 준다 (01 §14 #19)
 * - 요청당 쿼리 최대 5회: 최근 기록 + (최근이 COMPLETE가 아닐 때만) 최근 COMPLETE + detail · 자산 + 현재 holdings + 종목명
 */
@Service
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private static final SnapshotStatus COMPLETE = SnapshotStatus.COMPLETE;
    // 평가금액 내림차순, 같으면 종목코드 오름차순
    private static final Comparator<HoldingItem> ORDER = Comparator.comparing(HoldingItem::evalAmount).reversed()
            .thenComparing(HoldingItem::ticker);

    private final AssetSnapshotRepository snapshotRepository;
    private final AssetDetailSnapshotRepository detailRepository;
    private final HoldingRepository holdingRepository;
    private final StockPriceRepository stockPriceRepository;

    public PortfolioQueryService(AssetSnapshotRepository snapshotRepository, AssetDetailSnapshotRepository detailRepository,
                                 HoldingRepository holdingRepository, StockPriceRepository stockPriceRepository) {
        this.snapshotRepository = snapshotRepository;
        this.detailRepository = detailRepository;
        this.holdingRepository = holdingRepository;
        this.stockPriceRepository = stockPriceRepository;
    }

    public PortfolioResult getPortfolio(Long userId) {
        Optional<AssetSnapshot> latestRecord = snapshotRepository.findFirstByUserIdOrderBySnapshotDateDesc(userId);
        if (latestRecord.isEmpty()) {
            return PortfolioResult.empty(null);
        }
        LatestRecord record = new LatestRecord(latestRecord.get().getSnapshotDate(), latestRecord.get().getStatus());
        Optional<AssetSnapshot> latestComplete = latestRecord.get().getStatus() == COMPLETE
                ? latestRecord
                : snapshotRepository.findFirstByUserIdAndStatusOrderBySnapshotDateDesc(userId, COMPLETE);
        if (latestComplete.isEmpty()) {
            return PortfolioResult.empty(record);
        }

        AssetSnapshot snapshot = latestComplete.get();
        LocalDate asOf = snapshot.getSnapshotDate();
        BigDecimal total = snapshot.getTotalAsset();

        List<AssetDetailSnapshot> stockRows = detailRepository.findAllWithAssetByUserIdAndSnapshotDate(userId, asOf).stream()
                .filter(d -> d.getTicker() != null) // 예수금 · 수기 잔액은 자산군 금액에만
                .toList();
        Map<HoldingKey, Holding> current = new HashMap<>();
        for (Holding holding : holdingRepository.findAllByAssetUserId(userId)) {
            current.put(new HoldingKey(holding.getAsset().getId(), holding.getTicker()), holding);
        }
        Map<String, String> names = stockNames(stockRows, asOf);

        List<HoldingItem> holdings = stockRows.stream()
                .map(row -> holdingItem(row, current.get(new HoldingKey(row.getAsset().getId(), row.getTicker())), names, total))
                .sorted(ORDER)
                .toList();
        List<AssetClassItem> classes = List.of(
                assetClass(AssetClass.SECURITIES, snapshot.getSecuritiesAmount(), total),
                assetClass(AssetClass.DEPOSIT, snapshot.getDepositAmount(), total),
                assetClass(AssetClass.CASH, snapshot.getCashAmount(), total));
        return new PortfolioResult(asOf, record, total, classes, holdings);
    }

    private static HoldingItem holdingItem(AssetDetailSnapshot row, Holding current, Map<String, String> names, BigDecimal total) {
        Asset asset = row.getAsset();
        Profit profit = PortfolioCalculator.profit(new HoldingInput(row.getAmount(), row.getQuantity(),
                current == null ? null : current.getAvgBuyPrice()));
        String stockName = names.get(row.getTicker());
        if (stockName == null && current != null && current.getStockName() != null && !current.getStockName().isBlank()) {
            stockName = current.getStockName();
        }
        // 증권 계좌의 이름은 연동 때 기관명으로 저장된다 (Asset.securitiesAccount) — 기관 엔티티를 따로 읽지 않는다
        return new HoldingItem(asset.getId(), asset.getName(), asset.getAccountNumberMasked(),
                row.getTicker(), stockName != null ? stockName : row.getTicker(),
                row.getQuantity(), row.getPrice(), row.getAmount(),
                profit.principal(), profit.profitLoss(), profit.profitLossRate(),
                PortfolioCalculator.ratio(row.getAmount(), total), row.getDayChangeAmount());
    }

    private static AssetClassItem assetClass(AssetClass assetClass, BigDecimal amount, BigDecimal total) {
        return new AssetClassItem(assetClass, amount, PortfolioCalculator.ratio(amount, total));
    }

    // 종목명 — 그 날짜 이하 최신 시세 이름(1회 조회, B6와 같은 메서드). 없으면 호출하는 쪽이 현재 holdings 이름 → ticker 순으로 쓴다
    private Map<String, String> stockNames(List<AssetDetailSnapshot> stockRows, LocalDate date) {
        Set<String> tickers = new LinkedHashSet<>();
        stockRows.forEach(row -> tickers.add(row.getTicker()));
        if (tickers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (StockPriceRepository.TickerName row : stockPriceRepository.findLatestNames(tickers, date)) {
            if (row.getStockName() != null && !row.getStockName().isBlank()) {
                names.put(row.getTicker(), row.getStockName());
            }
        }
        return names;
    }

    private record HoldingKey(Long assetId, String ticker) {}
}
