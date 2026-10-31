package com.project.assetory.snapshot.dto;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.service.SnapshotDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 자산 기록 날짜별 상세 (FR-AS-06, FR-AN-01 종목 단위) — 숫자와 플래그만 담는다. 계좌번호는 마스킹 값만.
 */
@Schema(description = "자산 기록 날짜별 상세. missing=true(PARTIAL · FAILED)면 totalAsset · referenceDate만 직전 COMPLETE 값이고 증감 · assetClasses는 null, assets는 빈 목록")
public record SnapshotDetailResponse(
        @Schema(example = "2026-09-03") LocalDate date,
        @Schema(description = "COMPLETE / PARTIAL / FAILED", example = "COMPLETE") SnapshotStatus status,
        @Schema(description = "true = 일부 또는 전체 수집 실패로 비교에서 제외된 날", example = "false") boolean missing,
        @Schema(description = "그날 이벤트가 있는지", example = "false") boolean hasEvent,
        @Schema(description = "비교 기준 기록 날짜(직전 COMPLETE). 첫 기록 · 누락 날은 null", example = "2026-09-01") LocalDate baseDate,
        @Schema(description = "총자산 — 누락 날은 직전 COMPLETE 총자산", example = "621000.00") BigDecimal totalAsset,
        @Schema(description = "totalAsset의 기록 날짜 — COMPLETE면 그날, 누락이면 직전 COMPLETE 날짜", example = "2026-09-03") LocalDate referenceDate,
        @Schema(example = "11000.00") BigDecimal dayChangeAmount,
        @Schema(description = "퍼센트, 소수 4자리", example = "1.8033") BigDecimal dayChangeRate,
        @Schema(description = "시장가격 영향. 계산할 수 없으면 null", example = "1000.00") BigDecimal marketChangeAmount,
        @Schema(description = "기타 자산 변화(원인을 뜻하지 않음)", example = "10000.00") BigDecimal otherChangeAmount,
        AssetClasses assetClasses,
        List<AssetItem> assets
) {

    @Schema(name = "SnapshotDetailAssetClasses", description = "자산군별 금액과 기준일 대비 증감")
    public record AssetClasses(AssetClassItem securities, AssetClassItem deposit, AssetClassItem cash) {}

    @Schema(name = "SnapshotDetailAssetClassItem", description = "자산군 1개 — 기준일이 없거나 어느 한쪽 금액이 없으면 changeAmount는 null")
    public record AssetClassItem(
            @Schema(example = "11000.00") BigDecimal amount,
            @Schema(example = "1000.00") BigDecimal changeAmount) {}

    @Schema(name = "SnapshotDetailAssetItem", description = "자산 1개의 그날 기록. amount · dayChangeAmount는 증권 계좌 예수금 또는 수기 자산 잔액")
    public record AssetItem(
            @Schema(example = "3") Long assetId,
            @Schema(example = "SECURITIES") AssetType assetType,
            @Schema(description = "수기 자산 이름 또는 증권 계좌의 기관명", example = "삼성증권") String name,
            @Schema(description = "증권 계좌만 — 마스킹된 계좌번호. 수기 자산은 null", example = "123-****-901") String accountNumberMasked,
            @Schema(example = "110000.00") BigDecimal amount,
            @Schema(description = "직전 COMPLETE 대비. 기준일 행이 없으면 null", example = "10000.00") BigDecimal dayChangeAmount,
            List<HoldingItem> holdings) {}

    // 내부 record의 스키마 이름 = 바깥 응답 이름 + 내부 이름 (AGENTS 6장) — 같은 단순 이름의 AssetOverviewResponse.HoldingItem 스키마를 덮어쓰지 않도록
    @Schema(name = "SnapshotDetailHoldingItem", description = "종목 1개의 그날 기록 — 평가에 쓴 수량 · 단가 · 금액과 전일 대비")
    public record HoldingItem(
            @Schema(example = "005930") String ticker,
            @Schema(description = "그 날짜 이하 최신 시세의 종목명. 없으면 종목코드", example = "삼성전자") String stockName,
            @Schema(example = "10.0000") BigDecimal quantity,
            @Schema(example = "1100.00") BigDecimal price,
            @Schema(example = "11000.00") BigDecimal amount,
            @Schema(description = "직전 COMPLETE 같은 계좌 · 종목 대비. 새로 산 종목이면 null", example = "1000.00") BigDecimal dayChangeAmount) {}

    public static SnapshotDetailResponse from(SnapshotDetailResult r) {
        AssetClasses classes = r.assetClasses() == null ? null : new AssetClasses(
                item(r.assetClasses().securities()), item(r.assetClasses().deposit()), item(r.assetClasses().cash()));
        List<AssetItem> assets = r.assets().stream()
                .map(a -> new AssetItem(a.assetId(), a.assetType(), a.name(), a.accountNumberMasked(), a.amount(),
                        a.dayChangeAmount(), a.holdings().stream()
                        .map(h -> new HoldingItem(h.ticker(), h.stockName(), h.quantity(), h.price(), h.amount(), h.dayChangeAmount()))
                        .toList()))
                .toList();
        return new SnapshotDetailResponse(r.date(), r.status(), r.missing(), r.hasEvent(), r.baseDate(), r.totalAsset(),
                r.referenceDate(), r.dayChangeAmount(), r.dayChangeRate(), r.marketChangeAmount(), r.otherChangeAmount(),
                classes, assets);
    }

    private static AssetClassItem item(SnapshotDetailResult.ClassAmount c) {
        return new AssetClassItem(c.amount(), c.changeAmount());
    }
}
