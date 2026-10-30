package com.project.assetory.snapshot.dto;

import com.project.assetory.snapshot.service.AssetTrendResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 총자산 기간별 추이 (FR-AN-05, 03 S-01 ③) — COMPLETE 기록 점 목록과 기간 요약. 숫자와 플래그만 담는다.
 */
@Schema(description = "총자산 추이 — COMPLETE 기록만. PARTIAL · FAILED 날은 점에 없다(누락 표시는 자산 기록 조회에서)")
public record AssetTrendResponse(
        @Schema(description = "조회 기간 1M / 1Y / ALL", example = "1M")
        String period,
        @Schema(description = "끝점 = 가장 최근 COMPLETE 기록 날짜. 기록이 없으면 null", example = "2026-10-01")
        LocalDate asOfDate,
        @Schema(description = "false = 기간 미달로 연동 이후 첫 기록부터 표시. ALL은 항상 true", example = "true")
        boolean fullPeriod,
        @Schema(description = "날짜 오름차순. 첫 점이 시작점(1M · 1Y = 1개월 · 1년 전 같은 날 이하의 마지막 COMPLETE, ALL · 기간 미달 = 첫 COMPLETE)")
        List<Point> points,
        @Schema(description = "끝점 vs 시작점. 시장가격 영향은 시작점을 뺀 (시작, 끝] 기록의 합 — 전월 · 전년 · 누적 대비(/changes)와 같은 값. 점이 2개 미만이면 available=false")
        AssetChangeResponse.Comparison summary
) {

    @Schema(name = "AssetTrendPoint", description = "추이 점 1개 — 그날 저장된 값 그대로(계산할 수 없던 값은 null)")
    public record Point(
            @Schema(example = "2026-10-01") LocalDate date,
            @Schema(example = "3100000.00") BigDecimal totalAsset,
            @Schema(example = "2100000.00") BigDecimal securitiesAmount,
            @Schema(example = "0.00") BigDecimal depositAmount,
            @Schema(example = "1000000.00") BigDecimal cashAmount,
            @Schema(description = "직전 COMPLETE 대비 증감액. 첫 기록이면 null", example = "100000.00") BigDecimal dayChangeAmount,
            @Schema(description = "시장가격 영향. 계산할 수 없으면 null", example = "40000.00") BigDecimal marketChangeAmount,
            @Schema(description = "기타 자산 변화(원인을 뜻하지 않음). 시장가격 영향이 null이면 null", example = "60000.00")
            BigDecimal otherChangeAmount) {}

    public static AssetTrendResponse from(AssetTrendResult result) {
        List<Point> points = result.points().stream()
                .map(p -> new Point(p.date(), p.totalAsset(), p.securitiesAmount(), p.depositAmount(), p.cashAmount(),
                        p.dayChangeAmount(), p.marketChangeAmount(), p.otherChangeAmount()))
                .toList();
        return new AssetTrendResponse(result.period().code(), result.asOfDate(), result.fullPeriod(), points,
                AssetChangeResponse.Comparison.from(result.summary()));
    }
}
