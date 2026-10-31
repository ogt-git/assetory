package com.project.assetory.snapshot.service;

import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.SnapshotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 자산 기록 월별 목록 결과 (FR-AS-06, 03 S-02). API 응답 변환은 컨트롤러 단계에서 한다.
 *
 * @param month             조회한 달. 달을 생략했는데 기록이 하나도 없으면 null
 * @param firstRecordMonth  기록(상태 무관)이 있는 첫 달 — 월 이동 범위. 기록이 없으면 null
 * @param latestRecordMonth 기록(상태 무관)이 있는 최근 달
 * @param summary           그 달 첫 COMPLETE → 마지막 COMPLETE (FR-MR-01). COMPLETE 2개 미만이면 UNAVAILABLE
 * @param records           날짜 내림차순
 */
public record SnapshotMonthResult(YearMonth month, YearMonth firstRecordMonth, YearMonth latestRecordMonth,
                                  Comparison summary, int risingDays, int fallingDays, List<Record> records) {

    /**
     * 기록 1행. missing=true(PARTIAL · FAILED)면 totalAsset · referenceDate는 그 날짜 이전 마지막 COMPLETE 값(없으면 null)이고
     * 증감 4개는 null — 그날의 부분 금액은 담지 않는다 (01 §14 #19).
     */
    public record Record(LocalDate date, SnapshotStatus status, boolean missing,
                         BigDecimal totalAsset, LocalDate referenceDate,
                         BigDecimal dayChangeAmount, BigDecimal dayChangeRate,
                         BigDecimal marketChangeAmount, BigDecimal otherChangeAmount,
                         boolean hasEvent) {}
}
