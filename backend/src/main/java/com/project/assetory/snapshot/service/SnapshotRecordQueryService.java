package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.AssetChangeCalculator;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.SnapshotValues;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.StoredDayChange;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.DayRecord;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.Reference;
import com.project.assetory.snapshot.domain.SnapshotRecordCalculator.RiseFall;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.exception.SnapshotNotFoundException;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository.RecordRange;
import com.project.assetory.snapshot.service.SnapshotDetailResult.AssetClasses;
import com.project.assetory.snapshot.service.SnapshotDetailResult.AssetItem;
import com.project.assetory.snapshot.service.SnapshotDetailResult.ClassAmount;
import com.project.assetory.snapshot.service.SnapshotDetailResult.HoldingItem;
import com.project.assetory.snapshot.service.SnapshotMonthResult.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 자산 기록 조회 (FR-AS-06, 03 S-02) — 저장된 asset_snapshots만 읽는다.
 * - 월별 목록: 그 달 행 전체(상태 무관). PARTIAL · FAILED 날은 직전 COMPLETE 총자산 · 날짜를 보여주고 부분 금액은 담지 않는다 (01 §14 #19)
 * - 월 요약: 그 달 첫 COMPLETE → 마지막 COMPLETE (FR-MR-01, 12주차 결산과 같은 기준). market은 (첫, 마지막] 일별 합 (05 §4.11)
 * - 요청당 쿼리 최대 3회: 기록 범위 1 + 그 달 행 1 + (그 달 첫 COMPLETE 전에 누락 행이 있을 때만) 그 달 이전 마지막 COMPLETE 1
 * - 달 생략 시 기본 달은 DB의 최근 기록 날짜 기준 (오늘 날짜를 쓰지 않음)
 */
@Service
@Transactional(readOnly = true)
public class SnapshotRecordQueryService {

    private static final SnapshotStatus COMPLETE = SnapshotStatus.COMPLETE;

    private final AssetSnapshotRepository snapshotRepository;
    private final AssetDetailSnapshotRepository detailRepository;
    private final StockPriceRepository stockPriceRepository;

    public SnapshotRecordQueryService(AssetSnapshotRepository snapshotRepository, AssetDetailSnapshotRepository detailRepository,
                                      StockPriceRepository stockPriceRepository) {
        this.snapshotRepository = snapshotRepository;
        this.detailRepository = detailRepository;
        this.stockPriceRepository = stockPriceRepository;
    }

    /**
     * 날짜별 상세 — 요청당 쿼리 최대 4회(그날 기록 · detail + 자산 · 기준일 기록 · 종목명). 누락 날은 2회(그날 기록 · 직전 COMPLETE).
     *
     * @throws SnapshotNotFoundException 그 날짜에 사용자의 기록이 없을 때(다른 사용자의 날짜 포함)
     */
    public SnapshotDetailResult getDetail(Long userId, LocalDate date) {
        AssetSnapshot snapshot = snapshotRepository.findByUserIdAndSnapshotDate(userId, date)
                .orElseThrow(SnapshotNotFoundException::new);

        if (snapshot.getStatus() != COMPLETE) {
            // 부분 금액 · 자산 상세는 내리지 않는다 — 직전 COMPLETE 총자산과 그 날짜만 (01 §14 #19)
            Reference previous = snapshotRepository
                    .findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(userId, COMPLETE, date)
                    .map(s -> new Reference(s.getSnapshotDate(), s.getTotalAsset()))
                    .orElse(Reference.NONE);
            return new SnapshotDetailResult(date, snapshot.getStatus(), true, snapshot.isHasEvent(),
                    null, previous.totalAsset(), previous.date(), null, null, null, null, null, List.of());
        }

        List<AssetDetailSnapshot> details = detailRepository.findAllWithAssetByUserIdAndSnapshotDate(userId, date);
        SnapshotValues base = snapshot.getBaseSnapshotDate() == null ? null
                : snapshotRepository.findByUserIdAndSnapshotDate(userId, snapshot.getBaseSnapshotDate())
                        .filter(s -> s.getStatus() == COMPLETE)
                        .map(SnapshotRecordQueryService::values).orElse(null);

        return new SnapshotDetailResult(date, COMPLETE, false, snapshot.isHasEvent(),
                snapshot.getBaseSnapshotDate(), snapshot.getTotalAsset(), date,
                snapshot.getDayChangeAmount(), snapshot.getDayChangeRate(),
                snapshot.getMarketChangeAmount(), snapshot.getOtherChangeAmount(),
                assetClasses(snapshot, base), assetItems(details, stockNames(details, date)));
    }

    // 자산군 금액 + 기준일 행 대비 증감 — B4 전일 대비(AssetChangeCalculator.day)의 자산군 차이를 그대로 쓴다(한쪽 NULL이면 NULL)
    private static AssetClasses assetClasses(AssetSnapshot snapshot, SnapshotValues base) {
        StoredDayChange stored = new StoredDayChange(snapshot.getBaseSnapshotDate(), snapshot.getDayChangeAmount(),
                snapshot.getDayChangeRate(), snapshot.getMarketChangeAmount(), snapshot.getOtherChangeAmount());
        AssetChangeCalculator.AssetClassChanges changes =
                AssetChangeCalculator.day(values(snapshot), stored, base).assetClassChanges();
        boolean hasBase = changes != null;
        return new AssetClasses(
                new ClassAmount(snapshot.getSecuritiesAmount(), hasBase ? changes.securities() : null),
                new ClassAmount(snapshot.getDepositAmount(), hasBase ? changes.deposit() : null),
                new ClassAmount(snapshot.getCashAmount(), hasBase ? changes.cash() : null));
    }

    // detail 행을 자산별로 묶는다 — ticker NULL 행은 자산 금액(예수금 · 수기 잔액), 나머지는 종목. 쿼리가 자산 id · ticker 순으로 정렬
    private static List<AssetItem> assetItems(List<AssetDetailSnapshot> details, Map<String, String> names) {
        Map<Long, List<AssetDetailSnapshot>> byAsset = new LinkedHashMap<>();
        for (AssetDetailSnapshot detail : details) {
            byAsset.computeIfAbsent(detail.getAsset().getId(), id -> new ArrayList<>()).add(detail);
        }
        List<AssetItem> items = new ArrayList<>(byAsset.size());
        for (List<AssetDetailSnapshot> rows : byAsset.values()) {
            Asset asset = rows.get(0).getAsset();
            AssetDetailSnapshot assetRow = rows.stream().filter(r -> r.getTicker() == null).findFirst().orElse(null);
            List<HoldingItem> holdings = rows.stream().filter(r -> r.getTicker() != null)
                    .map(r -> new HoldingItem(r.getTicker(), names.getOrDefault(r.getTicker(), r.getTicker()),
                            r.getQuantity(), r.getPrice(), r.getAmount(), r.getDayChangeAmount()))
                    .toList();
            String masked = asset.getAssetType() == AssetType.SECURITIES ? asset.getAccountNumberMasked() : null;
            items.add(new AssetItem(asset.getId(), asset.getAssetType(), asset.getName(), masked,
                    assetRow == null ? null : assetRow.getAmount(),
                    assetRow == null ? null : assetRow.getDayChangeAmount(),
                    holdings));
        }
        return items;
    }

    // 종목명 — 그 날짜 이하 최신 시세 행의 이름(1회 조회). 없으면 호출하는 쪽이 ticker를 그대로 쓴다
    private Map<String, String> stockNames(List<AssetDetailSnapshot> details, LocalDate date) {
        Set<String> tickers = new LinkedHashSet<>();
        details.stream().map(AssetDetailSnapshot::getTicker).filter(Objects::nonNull).forEach(tickers::add);
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

    /**
     * @param month 조회할 달. null이면 상태 무관 최근 기록이 있는 달
     */
    public SnapshotMonthResult getMonth(Long userId, YearMonth month) {
        RecordRange range = snapshotRepository.findRecordRange(userId);
        YearMonth firstMonth = range.getFirstDate() == null ? null : YearMonth.from(range.getFirstDate());
        YearMonth latestMonth = range.getLatestDate() == null ? null : YearMonth.from(range.getLatestDate());
        YearMonth target = month != null ? month : latestMonth;
        if (target == null) {
            return new SnapshotMonthResult(null, null, null, Comparison.UNAVAILABLE, 0, 0, List.of());
        }

        List<AssetSnapshot> rows = snapshotRepository.findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                userId, target.atDay(1), target.atEndOfMonth());
        List<DayRecord> days = rows.stream().map(SnapshotRecordQueryService::dayRecord).toList();
        List<Reference> references = SnapshotRecordCalculator.references(previousComplete(userId, target, rows), days);
        RiseFall riseFall = SnapshotRecordCalculator.riseFall(days);

        List<Record> records = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            records.add(record(rows.get(i), references.get(i)));
        }
        Collections.reverse(records); // 화면 순서 — 최근 날짜가 위
        return new SnapshotMonthResult(target, firstMonth, latestMonth, summary(rows),
                riseFall.risingDays(), riseFall.fallingDays(), records);
    }

    // 그 달 첫 COMPLETE 전에 누락 행이 있을 때만 전달 이전 마지막 COMPLETE를 조회한다 (그 외에는 쓰이지 않는다)
    private Reference previousComplete(Long userId, YearMonth month, List<AssetSnapshot> rows) {
        boolean missingBeforeFirstComplete = !rows.isEmpty() && rows.get(0).getStatus() != COMPLETE;
        if (!missingBeforeFirstComplete) {
            return null;
        }
        return snapshotRepository
                .findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(userId, COMPLETE, month.atDay(1))
                .map(s -> new Reference(s.getSnapshotDate(), s.getTotalAsset()))
                .orElse(null);
    }

    // 그 달 첫 · 마지막 COMPLETE 비교. market은 첫 COMPLETE를 뺀 COMPLETE 행의 합 — 첫 거래일의 전월 말 대비 변화는 넣지 않는다
    private static Comparison summary(List<AssetSnapshot> rows) {
        List<AssetSnapshot> completes = rows.stream().filter(s -> s.getStatus() == COMPLETE).toList();
        if (completes.size() < 2) {
            return Comparison.UNAVAILABLE;
        }
        List<BigDecimal> markets = completes.subList(1, completes.size()).stream()
                .map(AssetSnapshot::getMarketChangeAmount)
                .toList();
        return AssetChangeCalculator.compare(values(completes.get(completes.size() - 1)), values(completes.get(0)),
                AssetChangeCalculator.marketSumOf(markets));
    }

    private static Record record(AssetSnapshot s, Reference reference) {
        if (s.getStatus() != COMPLETE) {
            // 부분 금액 · 증감은 담지 않는다 — 직전 COMPLETE 값과 그 날짜만
            return new Record(s.getSnapshotDate(), s.getStatus(), true, reference.totalAsset(), reference.date(),
                    null, null, null, null, s.isHasEvent());
        }
        return new Record(s.getSnapshotDate(), s.getStatus(), false, s.getTotalAsset(), s.getSnapshotDate(),
                s.getDayChangeAmount(), s.getDayChangeRate(), s.getMarketChangeAmount(), s.getOtherChangeAmount(),
                s.isHasEvent());
    }

    private static DayRecord dayRecord(AssetSnapshot s) {
        return new DayRecord(s.getSnapshotDate(), s.getStatus(), s.getTotalAsset(), s.getDayChangeAmount());
    }

    private static SnapshotValues values(AssetSnapshot s) {
        return new SnapshotValues(s.getSnapshotDate(), s.getTotalAsset(),
                s.getSecuritiesAmount(), s.getDepositAmount(), s.getCashAmount());
    }
}
