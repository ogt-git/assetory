package com.project.assetory.asset.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 통합 자산 조회 (FR-AS-01) — 증권 계좌 + 수기 자산 + 자산군별 합계.
 * 계좌번호는 마스킹 값만 담는다 (원문 미포함).
 */
public record AssetOverviewResponse(
        List<SecuritiesAccount> securitiesAccounts,
        List<ManualAssetResponse> manualAssets,
        Totals totals
) {

    // depositReceived(예수금)는 첫 수집 전이면 null
    public record SecuritiesAccount(Long assetId, Long connectionId, String institutionName, String accountNumberMasked,
                                    String connectionStatus, BigDecimal depositReceived, List<HoldingItem> holdings,
                                    LocalDateTime lastSyncedAt) {}

    public record HoldingItem(String ticker, String stockName, BigDecimal quantity, BigDecimal evalAmount,
                              BigDecimal profitLoss, BigDecimal profitLossRate) {}

    // SECURITIES = 보유 종목 평가금액 합, CASH = 증권 예수금 + 수기 현금, DEPOSIT = 수기 예적금
    // incomplete: 아직 수집되지 않은 값(예수금·평가금액 null)이 있어 합계에서 빠진 경우 true
    public record Totals(BigDecimal securities, BigDecimal deposit, BigDecimal cash, BigDecimal total,
                         boolean incomplete) {}
}
