package com.project.assetory.snapshot.service;

import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.TrendPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 총자산 기간별 추이 조회 결과 (FR-AN-05). API 응답 변환은 컨트롤러 단계에서 한다.
 *
 * @param asOfDate   끝점 = 최근 COMPLETE 날짜. COMPLETE가 없으면 null
 * @param fullPeriod false = 1M · 1Y의 시작 기준 기록이 없어 첫 COMPLETE부터 보여줌(기간 미달). ALL은 항상 true
 * @param points     COMPLETE 기록만, 날짜 오름차순. 첫 점이 시작점
 * @param summary    끝점 vs 시작점 — 점이 2개 미만이면 UNAVAILABLE
 */
public record AssetTrendResult(TrendPeriod period, LocalDate asOfDate, boolean fullPeriod,
                               List<Point> points, Comparison summary) {

    /** 추이 점 1개 — asset_snapshots에 저장된 값 그대로 */
    public record Point(LocalDate date, BigDecimal totalAsset,
                        BigDecimal securitiesAmount, BigDecimal depositAmount, BigDecimal cashAmount,
                        BigDecimal dayChangeAmount, BigDecimal marketChangeAmount, BigDecimal otherChangeAmount) {}
}
