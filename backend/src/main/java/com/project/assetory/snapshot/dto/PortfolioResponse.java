package com.project.assetory.snapshot.dto;

import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.snapshot.service.PortfolioResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04, 03 S-01 ⑤ · ⑥) — 최근 COMPLETE 기록 기준. 숫자와 플래그만, 계좌번호는 마스킹 값만.
 */
@Schema(description = "보유 종목 손익 · 자산 비중 — 최근 COMPLETE 기록(한투 종가 × 수량) 기준. /api/assets(CODEF 평가액 기준)와 값이 다를 수 있다. 기록이 없으면 asOfDate · totalAsset · latestRecord는 null, 목록은 비어 있다")
public record PortfolioResponse(
        @Schema(description = "기준 = 가장 최근 COMPLETE 기록 날짜", example = "2026-09-03")
        LocalDate asOfDate,
        @Schema(description = "가장 최근 기록(상태 무관). PARTIAL · FAILED면 누락 표시용 — 값은 asOfDate 기록 기준")
        AssetChangeResponse.LatestRecord latestRecord,
        @Schema(description = "그날 총자산 — 모든 비중의 분모", example = "621000.00")
        BigDecimal totalAsset,
        @Schema(description = "SECURITIES · DEPOSIT · CASH 순서. 비중은 반올림 합계를 100으로 맞추지 않는다")
        List<AssetClassItem> assetClasses,
        @Schema(description = "종목 행 — 그날 기록의 (계좌, 종목) 1개당 1행(같은 종목이 두 계좌에 있으면 2행). 평가금액 내림차순, 같으면 종목코드 오름차순. 예수금 · 수기 자산은 넣지 않으므로 종목 비중 합은 증권 비중과 다를 수 있다")
        List<HoldingItem> holdings
) {

    @Schema(name = "PortfolioAssetClassItem", description = "자산군 금액과 총자산 대비 비중")
    public record AssetClassItem(
            @Schema(example = "SECURITIES") AssetClass assetClass,
            @Schema(description = "자산군 금액. 그 자산군 수집이 전부 실패한 기록이면 null", example = "11000.00") BigDecimal amount,
            @Schema(description = "금액 ÷ 총자산 × 100 (퍼센트, 소수 2자리). 금액이 없거나 총자산 0이면 null", example = "1.77") BigDecimal ratio) {}

    @Schema(name = "PortfolioHoldingItem", description = "종목 1행 — 평가금액 · 수량 · 단가 · 당일 변동은 그날 기록값")
    public record HoldingItem(
            @Schema(example = "3") Long assetId,
            @Schema(example = "삼성증권") String institutionName,
            @Schema(description = "마스킹된 계좌번호", example = "123-****-901") String accountNumberMasked,
            @Schema(example = "005930") String ticker,
            @Schema(description = "그 날짜 이하 최신 시세의 종목명 → 없으면 현재 보유 목록의 이름 → 없으면 종목코드", example = "삼성전자") String stockName,
            @Schema(example = "10.0000") BigDecimal quantity,
            @Schema(description = "평가에 쓴 단가(한투 종가, 거래정지면 직전 종가)", example = "1100.00") BigDecimal price,
            @Schema(description = "평가금액 = 단가 × 수량", example = "11000.00") BigDecimal evalAmount,
            @Schema(description = "원금 = 기록 수량 × 현재 평균 매입가. 평균 매입가가 없거나, 그 뒤 전량 매도해 현재 보유 목록에 없거나, 기록 수량이 없으면 null", example = "9000.00") BigDecimal principal,
            @Schema(description = "평가금액 − 원금. 원금이 null이면 null", example = "2000.00") BigDecimal profitLoss,
            @Schema(description = "손익 ÷ 원금 × 100 (퍼센트, 소수 4자리). 원금이 null이거나 0이면 null", example = "22.2222") BigDecimal profitLossRate,
            @Schema(description = "평가금액 ÷ 총자산 × 100 (퍼센트, 소수 2자리)", example = "1.77") BigDecimal ratio,
            @Schema(description = "직전 COMPLETE 같은 계좌 · 종목 대비. 새로 산 종목이면 null", example = "1000.00") BigDecimal dayChangeAmount) {}

    public static PortfolioResponse from(PortfolioResult r) {
        AssetChangeResponse.LatestRecord latest = r.latestRecord() == null ? null
                : new AssetChangeResponse.LatestRecord(r.latestRecord().date(), r.latestRecord().status());
        List<AssetClassItem> classes = r.assetClasses().stream()
                .map(c -> new AssetClassItem(c.assetClass(), c.amount(), c.ratio()))
                .toList();
        List<HoldingItem> holdings = r.holdings().stream()
                .map(h -> new HoldingItem(h.assetId(), h.institutionName(), h.accountNumberMasked(), h.ticker(), h.stockName(),
                        h.quantity(), h.price(), h.evalAmount(), h.principal(), h.profitLoss(), h.profitLossRate(),
                        h.ratio(), h.dayChangeAmount()))
                .toList();
        return new PortfolioResponse(r.asOfDate(), latest, r.totalAsset(), classes, holdings);
    }
}
