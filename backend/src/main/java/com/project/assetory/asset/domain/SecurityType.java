package com.project.assetory.asset.domain;

// docs/05 §4.6 security_type — CODEF는 단일 종목과 ETF를 구분해 주지 않으므로 CODEF 수집에서는 NULL로 저장하고,
// 시세 수집 단계에서 다른 소스(한투 종목 정보 등)로 채운다 (M-1). 소스·시점은 구현 시 확정
public enum SecurityType {
    STOCK,
    ETF
}
