package com.project.assetory.snapshot.service;

import com.project.assetory.snapshot.domain.AssetChangeCalculator;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.MarketSum;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.SnapshotValues;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.StoredDayChange;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository.MarketAggregate;
import com.project.assetory.snapshot.service.AssetChangeResult.LatestRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 전일 · 전월 · 전년 · 누적 증감 조회 (FR-AN-01 · 02 · 03 · 06) — 저장된 asset_snapshots만 읽고, 계산은 AssetChangeCalculator에 맡긴다.
 * - 현재 기준 = 최근 COMPLETE. 전월 · 전년 기준 = asOfDate에서 계산한 목표 날짜 이하 마지막 COMPLETE (PARTIAL · FAILED 제외)
 * - 기간 시장가격 영향은 세 구간을 한 번에 집계한다 (요청당 쿼리 최대 7회)
 * - COMPLETE가 없으면 예외 없이 전부 available=false
 */
@Service
@Transactional(readOnly = true)
public class AssetChangeQueryService {

    private static final SnapshotStatus COMPLETE = SnapshotStatus.COMPLETE;

    private final AssetSnapshotRepository snapshotRepository;

    public AssetChangeQueryService(AssetSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public AssetChangeResult getChanges(Long userId) {
        Optional<AssetSnapshot> latestRecord = snapshotRepository.findFirstByUserIdOrderBySnapshotDateDesc(userId);
        if (latestRecord.isEmpty()) {
            return AssetChangeResult.noComplete(null);
        }
        LatestRecord record = new LatestRecord(latestRecord.get().getSnapshotDate(), latestRecord.get().getStatus());
        Optional<AssetSnapshot> latestComplete = latestRecord.get().getStatus() == COMPLETE
                ? latestRecord
                : snapshotRepository.findFirstByUserIdAndStatusOrderBySnapshotDateDesc(userId, COMPLETE);
        if (latestComplete.isEmpty()) {
            return AssetChangeResult.noComplete(record);
        }

        AssetSnapshot latest = latestComplete.get();
        LocalDate asOf = latest.getSnapshotDate();
        SnapshotValues latestValues = values(latest);

        // 전일 — 저장된 기준일 행은 baseTotal · 자산군 증감에만 쓴다 (COMPLETE만)
        SnapshotValues dayBase = latest.getBaseSnapshotDate() == null ? null
                : snapshotRepository.findByUserIdAndSnapshotDate(userId, latest.getBaseSnapshotDate())
                        .filter(snapshot -> snapshot.getStatus() == COMPLETE)
                        .map(AssetChangeQueryService::values).orElse(null);
        SnapshotValues monthBase = baseOnOrBefore(userId, AssetChangeCalculator.monthTarget(asOf));
        SnapshotValues yearBase = baseOnOrBefore(userId, AssetChangeCalculator.yearTarget(asOf));
        SnapshotValues first = snapshotRepository.findFirstByUserIdAndStatusOrderBySnapshotDateAsc(userId, COMPLETE)
                .map(AssetChangeQueryService::values)
                .filter(firstValues -> firstValues.date().isBefore(asOf))
                .orElse(null);

        MarketSums sums = marketSums(userId, asOf, monthBase, yearBase, first);
        StoredDayChange storedDay = new StoredDayChange(latest.getBaseSnapshotDate(), latest.getDayChangeAmount(),
                latest.getDayChangeRate(), latest.getMarketChangeAmount(), latest.getOtherChangeAmount());

        return new AssetChangeResult(record, asOf, latest.getTotalAsset(),
                AssetChangeCalculator.day(latestValues, storedDay, dayBase),
                AssetChangeCalculator.compare(latestValues, monthBase, sums.month()),
                AssetChangeCalculator.compare(latestValues, yearBase, sums.year()),
                AssetChangeCalculator.sinceFirstRecord(latestValues, first, sums.sinceFirst()));
    }

    private SnapshotValues baseOnOrBefore(Long userId, LocalDate target) {
        return snapshotRepository.findFirstByUserIdAndStatusAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(userId, COMPLETE, target)
                .map(AssetChangeQueryService::values).orElse(null);
    }

    private record MarketSums(MarketSum month, MarketSum year, MarketSum sinceFirst) {}

    // 세 구간 (기준, asOf] — 기준이 없는 구간은 from = asOf(빈 구간)으로 두고 결과는 쓰지 않는다. 기준이 하나도 없으면 조회하지 않는다
    private MarketSums marketSums(Long userId, LocalDate asOf, SnapshotValues monthBase, SnapshotValues yearBase,
                                  SnapshotValues first) {
        MarketSum none = new MarketSum(null, true);
        if (monthBase == null && yearBase == null && first == null) {
            return new MarketSums(none, none, none);
        }
        LocalDate monthFrom = from(monthBase, asOf);
        LocalDate yearFrom = from(yearBase, asOf);
        LocalDate firstFrom = from(first, asOf);
        LocalDate lowestFrom = Stream.of(monthFrom, yearFrom, firstFrom).min(LocalDate::compareTo).orElseThrow();

        MarketAggregate aggregate = snapshotRepository.aggregateMarket(userId, monthFrom, yearFrom, firstFrom, lowestFrom, asOf);
        return new MarketSums(
                marketSum(aggregate.getMonthSum(), aggregate.getMonthRows(), aggregate.getMonthMarketRows()),
                marketSum(aggregate.getYearSum(), aggregate.getYearRows(), aggregate.getYearMarketRows()),
                marketSum(aggregate.getFirstSum(), aggregate.getFirstRows(), aggregate.getFirstMarketRows()));
    }

    private static LocalDate from(SnapshotValues base, LocalDate asOf) {
        return base == null ? asOf : base.date();
    }

    // 행 수와 market 값이 있는 행 수가 다르면 구간 안에 NULL이 있다 → market · other NULL (05 §4.7)
    private static MarketSum marketSum(BigDecimal sum, Long rows, Long marketRows) {
        long total = rows == null ? 0 : rows;
        long withMarket = marketRows == null ? 0 : marketRows;
        return new MarketSum(sum, total != withMarket);
    }

    private static SnapshotValues values(AssetSnapshot snapshot) {
        return new SnapshotValues(snapshot.getSnapshotDate(), snapshot.getTotalAsset(),
                snapshot.getSecuritiesAmount(), snapshot.getDepositAmount(), snapshot.getCashAmount());
    }
}
