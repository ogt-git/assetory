package com.project.assetory.snapshot.dto;

import com.project.assetory.snapshot.domain.AssetChangeCalculator;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.service.AssetChangeResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 전일 · 전월 · 전년 · 누적 자산 증감 (FR-AN-01 · 02 · 03 · 06) — COMPLETE 기록끼리 비교한 숫자와 플래그만 담는다.
 * 표시 문구("데이터 축적 중" 등)는 화면에서 정한다.
 */
@Schema(description = "자산 증감 — 모든 비교는 COMPLETE 기록끼리 한다. 기록이 하나도 없으면 asOfDate · totalAsset · latestRecord는 null, 비교는 전부 available=false")
public record AssetChangeResponse(
        @Schema(description = "비교 기준 = 가장 최근 COMPLETE 기록 날짜", example = "2026-10-01")
        LocalDate asOfDate,
        @Schema(description = "기준 날짜의 총자산", example = "3100000.00")
        BigDecimal totalAsset,
        @Schema(description = "가장 최근 기록(상태 무관). PARTIAL · FAILED면 누락 표시용 — 비교에는 쓰지 않는다")
        LatestRecord latestRecord,
        @Schema(description = "전일 대비 — 직전 COMPLETE 기록 대비, 배치가 저장한 값")
        Comparison day,
        @Schema(description = "전월 대비 — 기준 날짜 1개월 전(말일 보정) 이하의 마지막 COMPLETE 기록 대비")
        Comparison month,
        @Schema(description = "전년 대비 — 기준 날짜 1년 전 이하의 마지막 COMPLETE 기록 대비")
        Comparison year,
        @Schema(description = "연동 이후 누적 — 첫 COMPLETE 기록 대비. 전월 · 전년이 available=false일 때 대신 보여준다")
        Comparison sinceFirstRecord
) {

    @Schema(name = "AssetChangeLatestRecord")
    public record LatestRecord(
            @Schema(example = "2026-10-02") LocalDate date,
            @Schema(description = "COMPLETE / PARTIAL / FAILED", example = "PARTIAL") SnapshotStatus status) {}

    @Schema(name = "AssetChangeComparison", description = "비교 1건. available=false(기간 미달 또는 기준 COMPLETE 기록 없음)면 나머지 필드는 모두 null")
    public record Comparison(
            @Schema(description = "false = 기간 미달 또는 기준이 될 COMPLETE 기록 없음", example = "true")
            boolean available,
            @Schema(description = "실제 비교한 기준 기록 날짜 — 목표 날짜보다 앞설 수 있다", example = "2026-09-30")
            LocalDate baseDate,
            @Schema(example = "3000000.00") BigDecimal baseTotal,
            @Schema(description = "총자산 증감액", example = "100000.00") BigDecimal changeAmount,
            @Schema(description = "증감률(퍼센트, 소수 4자리). 기준 총자산이 0이면 null", example = "3.3333") BigDecimal changeRate,
            @Schema(description = "시장가격 영향 — 계산할 수 없는 기록이 기간에 있으면 null", example = "40000.00")
            BigDecimal marketChangeAmount,
            @Schema(description = "기타 자산 변화 = 증감액 − 시장가격 영향(원인을 뜻하지 않음). 시장가격 영향이 null이면 null", example = "60000.00")
            BigDecimal otherChangeAmount,
            AssetClassChanges assetClassChanges) {

        static Comparison from(AssetChangeCalculator.Comparison c) {
            if (!c.available()) {
                return new Comparison(false, null, null, null, null, null, null, null);
            }
            AssetChangeCalculator.AssetClassChanges classes = c.assetClassChanges();
            return new Comparison(true, c.baseDate(), c.baseTotal(), c.changeAmount(), c.changeRate(),
                    c.marketChangeAmount(), c.otherChangeAmount(),
                    new AssetClassChanges(classes.securities(), classes.deposit(), classes.cash()));
        }
    }

    @Schema(name = "AssetChangeAssetClassChanges", description = "자산군별 증감 — 어느 한쪽 기록의 자산군 금액이 없으면 그 자산군만 null")
    public record AssetClassChanges(
            @Schema(example = "100000.00") BigDecimal securities,
            @Schema(example = "0.00") BigDecimal deposit,
            @Schema(example = "0.00") BigDecimal cash) {}

    public static AssetChangeResponse from(AssetChangeResult result) {
        LatestRecord latest = result.latestRecord() == null ? null
                : new LatestRecord(result.latestRecord().date(), result.latestRecord().status());
        return new AssetChangeResponse(result.asOfDate(), result.totalAsset(), latest,
                Comparison.from(result.day()), Comparison.from(result.month()),
                Comparison.from(result.year()), Comparison.from(result.sinceFirstRecord()));
    }
}
