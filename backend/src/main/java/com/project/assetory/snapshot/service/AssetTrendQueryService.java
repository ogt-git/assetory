package com.project.assetory.snapshot.service;

import com.project.assetory.snapshot.domain.AssetChangeCalculator;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.SnapshotValues;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.domain.TrendPeriod;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.AssetTrendResult.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 총자산 기간별 추이 (FR-AN-05 · 06) — 저장된 COMPLETE 기록만 읽고, 비교 계산은 AssetChangeCalculator에 맡긴다.
 * - 끝점 = 최근 COMPLETE. 시작점: 1M · 1Y = 목표 날짜 이하 마지막 COMPLETE(B4 전월 · 전년 기준과 같음), ALL · 기간 미달 = 첫 COMPLETE
 * - 점 = 시작점 + (시작점, 끝점] COMPLETE 행. PARTIAL · FAILED는 넣지 않는다 (01 §14 #19 — 부분 금액 미노출)
 * - 요약 market 합은 점 목록에서 시작점을 뺀 행들로 메모리 계산 — B4 DB 집계와 같은 (기준, 최근] 구간 (05 §4.7)
 * - 요청당 쿼리 최대 3회: 최근 COMPLETE + 시작 기준(1M · 1Y만) + 점 목록. 시작 기준이 없으면 점 목록을 하한 없이 조회해 첫 COMPLETE를 따로 찾지 않는다
 */
@Service
@Transactional(readOnly = true)
public class AssetTrendQueryService {

    private static final SnapshotStatus COMPLETE = SnapshotStatus.COMPLETE;

    private final AssetSnapshotRepository snapshotRepository;

    public AssetTrendQueryService(AssetSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public AssetTrendResult getTrend(Long userId, TrendPeriod period) {
        Optional<AssetSnapshot> latest = snapshotRepository.findFirstByUserIdAndStatusOrderBySnapshotDateDesc(userId, COMPLETE);
        if (latest.isEmpty()) {
            return new AssetTrendResult(period, null, period == TrendPeriod.ALL, List.of(), Comparison.UNAVAILABLE);
        }
        LocalDate asOf = latest.get().getSnapshotDate();

        Optional<LocalDate> start = period.targetDate(asOf)
                .flatMap(target -> snapshotRepository
                        .findFirstByUserIdAndStatusAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(userId, COMPLETE, target))
                .map(AssetSnapshot::getSnapshotDate);
        boolean fullPeriod = period == TrendPeriod.ALL || start.isPresent();

        List<AssetSnapshot> rows = snapshotRepository.findCompleteRange(userId, start.orElse(null), asOf);
        List<Point> points = rows.stream().map(AssetTrendQueryService::point).toList();
        return new AssetTrendResult(period, asOf, fullPeriod, points, summary(rows));
    }

    // 끝점 vs 시작점. 점이 2개 미만(시작점 = 끝점)이면 비교하지 않는다
    private static Comparison summary(List<AssetSnapshot> rows) {
        if (rows.size() < 2) {
            return Comparison.UNAVAILABLE;
        }
        AssetSnapshot first = rows.get(0);
        AssetSnapshot last = rows.get(rows.size() - 1);
        List<BigDecimal> markets = rows.subList(1, rows.size()).stream()
                .map(AssetSnapshot::getMarketChangeAmount)
                .toList();
        return AssetChangeCalculator.compare(values(last), values(first), AssetChangeCalculator.marketSumOf(markets));
    }

    private static Point point(AssetSnapshot s) {
        return new Point(s.getSnapshotDate(), s.getTotalAsset(),
                s.getSecuritiesAmount(), s.getDepositAmount(), s.getCashAmount(),
                s.getDayChangeAmount(), s.getMarketChangeAmount(), s.getOtherChangeAmount());
    }

    private static SnapshotValues values(AssetSnapshot s) {
        return new SnapshotValues(s.getSnapshotDate(), s.getTotalAsset(),
                s.getSecuritiesAmount(), s.getDepositAmount(), s.getCashAmount());
    }
}
