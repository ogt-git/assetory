package com.project.assetory.asset.domain;

// docs/05 §4.6 security_type — CODEF 응답만으로 확실히 구분되는 경우에만 채운다 (판별 방식 확인 필요, 그전까지 NULL)
public enum SecurityType {
    STOCK,
    ETF
}
