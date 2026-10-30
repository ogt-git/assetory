package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.snapshot.service.AssetChangeResult.LatestRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 보유 종목 손익 · 자산 비중 결과 (FR-AS-03 · 04) — 최근 COMPLETE 기록 기준. API 응답 변환은 컨트롤러 단계에서 한다.
 *
 * @param latestRecord 최근 기록(상태 무관) — 누락 표시용. 기록이 없으면 null
 * @param asOfDate     기준 = 최근 COMPLETE 날짜. COMPLETE가 없으면 null
 * @param totalAsset   그날 총자산 — 비중의 분모
 */
public record PortfolioResult(LocalDate asOfDate, LatestRecord latestRecord, BigDecimal totalAsset,
                              List<AssetClassItem> assetClasses, List<HoldingItem> holdings) {

    /** 자산군 금액 · 비중 (SECURITIES · DEPOSIT · CASH 순) */
    public record AssetClassItem(AssetClass assetClass, BigDecimal amount, BigDecimal ratio) {}

    /**
     * 종목 1행 = 그날 detail의 (asset_id, ticker) 행 1개. 평가금액 · 수량 · 단가 · 당일 변동은 detail 저장값.
     * 원금 = 기록 수량 × 현재 평균 매입가 — 알 수 없으면 원금 · 손익 · 손익률 null.
     *
     * @param accountNumberMasked 저장된 마스킹 값 그대로 (원문 계좌번호는 담지 않는다)
     */
    public record HoldingItem(Long assetId, String institutionName, String accountNumberMasked,
                              String ticker, String stockName, BigDecimal quantity, BigDecimal price,
                              BigDecimal evalAmount, BigDecimal principal, BigDecimal profitLoss,
                              BigDecimal profitLossRate, BigDecimal ratio, BigDecimal dayChangeAmount) {}

    static PortfolioResult empty(LatestRecord latestRecord) {
        return new PortfolioResult(null, latestRecord, null, List.of(), List.of());
    }
}
