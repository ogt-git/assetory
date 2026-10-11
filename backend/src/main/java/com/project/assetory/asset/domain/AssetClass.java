package com.project.assetory.asset.domain;

// docs/05 §4.4 asset_class — 결산·구성 집계 단위.
// 규칙: 보유 종목 평가액 → SECURITIES(holdings 합산), 증권 계좌 예수금·입출금 → CASH, 예금·적금 → DEPOSIT
public enum AssetClass {
    SECURITIES,
    DEPOSIT,
    CASH;

    // 자산 행의 asset_class — 증권 계좌 행은 예수금이므로 CASH (종목 평가액은 holdings로 SECURITIES 집계)
    public static AssetClass of(AssetType assetType) {
        return switch (assetType) {
            case DEPOSIT, SAVINGS -> DEPOSIT;
            case CASH, CHECKING, SECURITIES -> CASH;
        };
    }
}
