package com.project.assetory.snapshot.service;

import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.SnapshotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 전일 · 전월 · 전년 · 누적 증감 조회 결과 (FR-AN-01 · 02 · 03 · 06). API 응답 변환은 컨트롤러 단계에서 한다.
 *
 * @param latestRecord 최근 기록(상태 무관) — 누락 표시용. 기록이 없으면 null
 * @param asOfDate     비교 기준 = 최근 COMPLETE 날짜. COMPLETE가 없으면 null
 * @param totalAsset   최근 COMPLETE의 총자산. COMPLETE가 없으면 null
 */
public record AssetChangeResult(LatestRecord latestRecord, LocalDate asOfDate, BigDecimal totalAsset,
                                Comparison day, Comparison month, Comparison year, Comparison sinceFirstRecord) {

    public record LatestRecord(LocalDate date, SnapshotStatus status) {}

    // COMPLETE가 하나도 없음 — 오류가 아닌 정상 상태 (기록 축적 전)
    static AssetChangeResult noComplete(LatestRecord latestRecord) {
        return new AssetChangeResult(latestRecord, null, null,
                Comparison.UNAVAILABLE, Comparison.UNAVAILABLE, Comparison.UNAVAILABLE, Comparison.UNAVAILABLE);
    }
}
