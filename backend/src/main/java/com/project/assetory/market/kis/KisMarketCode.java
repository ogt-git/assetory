package com.project.assetory.market.kis;

import com.project.assetory.market.domain.IndicatorCode;

import java.util.Optional;

/**
 * 한투 종목정보(search-stock-info) mket_id_cd → 근거 상태 판정의 비교 지수 (Q-2, 01 §14 #12).
 * 실호출 V2-b(2026-11-16, docs/ops/kis-verification.md): STK = 유가증권 → KOSPI, KSQ = 코스닥 → KOSDAQ. ETF도 상장 시장을 따른다.
 * 코넥스 · 빈 값 · 모르는 값은 비교 지수 없음 — KOSPI로 추측하지 않는다 (AGENTS 5-C). 스프링 의존 없는 순수 변환.
 */
public final class KisMarketCode {

    private static final String KOSPI_MARKET = "STK";
    private static final String KOSDAQ_MARKET = "KSQ";

    private KisMarketCode() {
    }

    public static Optional<IndicatorCode> toIndexCode(String marketIdCode) {
        if (KOSPI_MARKET.equals(marketIdCode)) return Optional.of(IndicatorCode.KOSPI);
        if (KOSDAQ_MARKET.equals(marketIdCode)) return Optional.of(IndicatorCode.KOSDAQ);
        return Optional.empty();
    }
}
