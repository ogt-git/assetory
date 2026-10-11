package com.project.assetory.connect.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// docs/05 §4.2 business_type — CODEF 업무 구분 코드와 같은 값
@Getter
@RequiredArgsConstructor
public enum BusinessType {

    BK("P", InstitutionType.BANK),       // 은행 — clientType P(개인)
    ST("A", InstitutionType.SECURITIES); // 증권 — clientType A(통합)

    private final String clientType;
    private final InstitutionType institutionType;
}
