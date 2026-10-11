package com.project.assetory.asset.domain;

// docs/05 §4.4 asset_class — 결산·구성 집계 단위.
// 규칙: 보유 종목 평가액 → SECURITIES(holdings 합산), 증권 계좌 예수금·입출금 → CASH, 예금·적금 → DEPOSIT
public enum AssetClass {
    SECURITIES,
    DEPOSIT,
    CASH
}
