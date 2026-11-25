package com.project.assetory.market.service;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.kis.KisStockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 종목 → 비교 지수 (Q-2) — 조회 실패는 수집 실패가 아니라 "비교 지수 없음"이다
class StockMarketResolverTest {

    private final KisApiClient kis = mock(KisApiClient.class);
    private final StockMarketResolver resolver = new StockMarketResolver(kis);

    @Test
    @DisplayName("종목정보 mket_id_cd STK → KOSPI, KSQ → KOSDAQ")
    void resolves() {
        when(kis.getStockInfo("000001")).thenReturn(info("000001", "STK"));
        when(kis.getStockInfo("000002")).thenReturn(info("000002", "KSQ"));

        assertThat(resolver.resolve("000001")).contains(IndicatorCode.KOSPI);
        assertThat(resolver.resolve("000002")).contains(IndicatorCode.KOSDAQ);
    }

    @Test
    @DisplayName("한투 오류 · 그 밖의 예외 → 빈 값 (예외를 밖으로 던지지 않는다)")
    void clientFailure() {
        when(kis.getStockInfo("000001")).thenThrow(new KisApiException(KisErrorType.API_ERROR, null));
        when(kis.getStockInfo("000002")).thenThrow(new IllegalStateException("테스트 — 예상 못 한 예외"));

        assertThat(resolver.resolve("000001")).isEmpty();
        assertThat(resolver.resolve("000002")).isEmpty();
    }

    @Test
    @DisplayName("모르는 시장 값 · 시장 필드 없음 · 응답 없음 → 빈 값")
    void unknownOrMissing() {
        when(kis.getStockInfo("000001")).thenReturn(info("000001", "KNX"));
        when(kis.getStockInfo("000002")).thenReturn(info("000002", null));
        when(kis.getStockInfo("000003")).thenReturn(null);

        assertThat(resolver.resolve("000001")).isEmpty();
        assertThat(resolver.resolve("000002")).isEmpty();
        assertThat(resolver.resolve("000003")).isEmpty();
    }

    // 테스트 전용 더미 종목정보
    private static KisStockInfo info(String ticker, String marketIdCode) {
        return new KisStockInfo(ticker, "테스트종목", "ST", "0", null, "N", "N", null, marketIdCode);
    }
}
