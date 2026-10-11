package com.project.assetory.connect.codef.stock;

import java.math.BigDecimal;

// CODEF 종합자산 응답의 보유 종목 1건 (국내 종목만). 금액·수량은 BigDecimal
public record ParsedHolding(
        String ticker,
        String stockName,
        String productType,       // resProductType 원문 — STOCK/ETF 판별 근거로만 보관 (판별 규칙 확인 필요)
        BigDecimal quantity,
        BigDecimal avgBuyPrice,
        BigDecimal currentPrice,
        BigDecimal evalAmount,
        BigDecimal purchaseAmount,
        BigDecimal profitLoss,
        BigDecimal profitLossRate
) {}
