package com.project.assetory.snapshot.dto;

import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.service.SnapshotMonthResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 자산 기록 월별 목록 (FR-AS-06, 03 S-02) — 숫자와 플래그만 담는다.
 */
@Schema(description = "자산 기록 월별 목록 — 그 달 기록 전체(상태 무관), 날짜 내림차순")
public record SnapshotMonthResponse(
        @Schema(description = "조회한 달. 달을 생략했는데 기록이 하나도 없으면 null", type = "string", example = "2026-09")
        YearMonth month,
        @Schema(description = "기록이 있는 첫 달 — 월 이동(◀) 한계. 기록이 없으면 null", type = "string", example = "2026-07")
        YearMonth firstRecordMonth,
        @Schema(description = "기록이 있는 최근 달 — 월 이동(▶) 한계. 기록이 없으면 null", type = "string", example = "2026-10")
        YearMonth latestRecordMonth,
        @Schema(description = "이번 달 요약 = 그 달 첫 COMPLETE → 마지막 COMPLETE (월간 결산과 같은 기준). 첫 거래일의 전월 말 대비 변화는 포함하지 않는다. COMPLETE 2개 미만이면 available=false")
        AssetChangeResponse.Comparison summary,
        @Schema(description = "그 달 COMPLETE 기록 중 전일 대비 증가한 날 수", example = "12")
        int risingDays,
        @Schema(description = "그 달 COMPLETE 기록 중 전일 대비 감소한 날 수", example = "7")
        int fallingDays,
        List<Record> records
) {

    @Schema(name = "SnapshotMonthRecord", description = "기록 1행. missing=true(PARTIAL · FAILED)면 totalAsset · referenceDate는 그 날짜 이전 마지막 COMPLETE 값(없으면 null)이고 증감은 null — 그날의 부분 금액은 내리지 않는다")
    public record Record(
            @Schema(example = "2026-09-02") LocalDate date,
            @Schema(description = "COMPLETE / PARTIAL / FAILED", example = "PARTIAL") SnapshotStatus status,
            @Schema(description = "true = 일부 또는 전체 수집 실패로 비교에서 제외된 날", example = "true") boolean missing,
            @Schema(description = "총자산 — 누락 날은 직전 COMPLETE 총자산", example = "41380000.00") BigDecimal totalAsset,
            @Schema(description = "totalAsset의 기록 날짜 — COMPLETE면 그날, 누락이면 직전 COMPLETE 날짜", example = "2026-09-01") LocalDate referenceDate,
            @Schema(description = "직전 COMPLETE 대비 증감액. 누락 날 · 첫 기록은 null", example = "820000.00") BigDecimal dayChangeAmount,
            @Schema(description = "증감률(퍼센트, 소수 4자리)", example = "2.0216") BigDecimal dayChangeRate,
            @Schema(description = "시장가격 영향. 계산할 수 없으면 null", example = "600000.00") BigDecimal marketChangeAmount,
            @Schema(description = "기타 자산 변화(원인을 뜻하지 않음)", example = "220000.00") BigDecimal otherChangeAmount,
            @Schema(description = "그날 이벤트가 있는지", example = "false") boolean hasEvent) {}

    public static SnapshotMonthResponse from(SnapshotMonthResult result) {
        List<Record> records = result.records().stream()
                .map(r -> new Record(r.date(), r.status(), r.missing(), r.totalAsset(), r.referenceDate(),
                        r.dayChangeAmount(), r.dayChangeRate(), r.marketChangeAmount(), r.otherChangeAmount(), r.hasEvent()))
                .toList();
        return new SnapshotMonthResponse(result.month(), result.firstRecordMonth(), result.latestRecordMonth(),
                AssetChangeResponse.Comparison.from(result.summary()), result.risingDays(), result.fallingDays(), records);
    }
}
