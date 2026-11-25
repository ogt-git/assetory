package com.project.assetory.market.domain;

/**
 * docs/05 §4.10 macro_indicators.indicator_code — 지표마다 소스가 정해져 있다 (B4 확정 사항 1).
 * KOSPI · KOSDAQ = 한투 업종지수(FR-MK-02), BASE_RATE · USD_KRW = ECOS(FR-MK-03)
 */
public enum IndicatorCode {
    KOSPI(IndicatorSource.KIS),
    KOSDAQ(IndicatorSource.KIS),
    BASE_RATE(IndicatorSource.ECOS),
    USD_KRW(IndicatorSource.ECOS);

    private final IndicatorSource source;

    IndicatorCode(IndicatorSource source) {
        this.source = source;
    }

    public IndicatorSource source() {
        return source;
    }
}
