package com.project.assetory.connect.codef.stock;

import java.math.BigDecimal;

// CODEF 종합자산 응답의 보유 종목 1건 (국내 종목만). 금액·수량은 BigDecimal
public record ParsedHolding(
        String ticker,
        String stockName,
        String productType,       // resProductType 원문 — 해외 종목 판별에 사용. CODEF는 STOCK/ETF를 구분하지 않음 (M-1)
        BigDecimal quantity,
        BigDecimal avgBuyPrice,
        BigDecimal currentPrice,
        BigDecimal evalAmount,
        BigDecimal purchaseAmount,
        BigDecimal profitLoss,
        BigDecimal profitLossRate
) {}
