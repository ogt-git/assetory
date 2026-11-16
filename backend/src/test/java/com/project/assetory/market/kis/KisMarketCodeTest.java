package com.project.assetory.market.kis;

import com.project.assetory.market.domain.IndicatorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 종목정보 mket_id_cd → 비교 지수 (Q-2, kis-verification V2-b: STK = 유가증권, KSQ = 코스닥)
class KisMarketCodeTest {

    @Test
    @DisplayName("STK(유가증권) → KOSPI, KSQ(코스닥) → KOSDAQ")
    void knownMarkets() {
        assertThat(KisMarketCode.toIndexCode("STK")).contains(IndicatorCode.KOSPI);
        assertThat(KisMarketCode.toIndexCode("KSQ")).contains(IndicatorCode.KOSDAQ);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → 빈 값")
    @DisplayName("코넥스 · 모르는 값 · 소문자 · 빈 값은 비교 지수 없음 (KOSPI로 추측하지 않는다)")
    @NullAndEmptySource
    @ValueSource(strings = {"KNX", "XYZ", "stk", "   "})
    void unknownMarkets(String code) {
        assertThat(KisMarketCode.toIndexCode(code)).isEmpty();
    }
}
