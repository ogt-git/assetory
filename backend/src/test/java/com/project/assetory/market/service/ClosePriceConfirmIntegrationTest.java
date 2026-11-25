package com.project.assetory.market.service;

import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.ClosePriceConfirmResult.ClosePriceChange;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 잠정 종가 확정 재조회 (01 §14 #23, kis-verification V4) — 16:00에 저장한 거래일 행을 다음 거래일 아침에 한투 값으로 다시 맞춘다
class ClosePriceConfirmIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate THU = LocalDate.of(2026, 10, 29);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 30);
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @MockitoBean KisApiClient kis;
    @MockitoBean Clock clock;

    @Autowired StockPriceCollectService collectService;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        stockPriceRepository.deleteAll();
        now(MON.atTime(7, 30));
    }

    private void now(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // 16:00 배치가 저장한 잠정 행
    private void provisional(String ticker, LocalDate date, String close, String prevClose, String rate, long volume) {
        transactionTemplate.executeWithoutResult(status -> {
            StockPrice price = StockPrice.create(ticker, date);
            price.update("테스트종목", d(close), d(prevClose), d(rate), volume);
            stockPriceRepository.save(price);
        });
    }

    private static KisDailyPrice row(LocalDate date, String close, String rate, long volume) {
        return new KisDailyPrice(date, d(close), null, null, d(rate), volume, null, null);
    }

    private StockPrice stored(String ticker, LocalDate date) {
        return stockPriceRepository.findByTickerAndTradeDate(ticker, date).orElseThrow();
    }

    @Test
    @DisplayName("잠정 종가가 확정 종가와 다르면 행을 확정값(종가 · 변동률 · 거래량 · 직전 종가)으로 바꾸고 (잠정, 확정)을 돌려준다")
    void updatesChangedClose() {
        provisional("005930", FRI, "223000", "232000", "-3.88", 19_037_785L);
        doReturn(List.of(
                row(MON, "229000", "1.78", 10L),                 // 오늘 행(장 시작 전) — 건드리지 않는다
                row(FRI, "225000", "-3.02", 19_668_075L),
                row(THU, "232000", "-4.13", 24_975_596L)))
                .when(kis).getDailyPrices("005930");

        ClosePriceConfirmResult result = collectService.confirmClose(FRI, List.of("005930"));

        assertThat(result.changes()).containsEntry("005930", new ClosePriceChange(d("223000.00"), d("225000")));
        StockPrice price = stored("005930", FRI);
        assertThat(price.getClosePrice()).isEqualByComparingTo("225000");
        assertThat(price.getChangeRate()).isEqualByComparingTo("-3.02");
        assertThat(price.getVolume()).isEqualTo(19_668_075L);
        assertThat(price.getPrevClosePrice()).isEqualByComparingTo("232000");
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", MON)).isEmpty();
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("종가가 같으면 보정 대상이 아니다 — 거래량 · 변동률만 다르면 행은 확정값으로 맞추되 changes에는 넣지 않는다")
    void sameCloseIsNotAChange() {
        provisional("005930", FRI, "229000", "232000", "-1.29", 14_010_761L);
        doReturn(List.of(row(FRI, "229000", "-1.29", 14_537_981L), row(THU, "232000", "-4.13", 1L)))
                .when(kis).getDailyPrices("005930");

        ClosePriceConfirmResult result = collectService.confirmClose(FRI, List.of("005930"));

        assertThat(result.changes()).isEmpty();
        assertThat(stored("005930", FRI).getVolume()).isEqualTo(14_537_981L);
        assertThat(result.checked()).isEqualTo(1);
    }

    @Test
    @DisplayName("그날 저장된 잠정 행이 없는 종목은 대상이 아니다 — 호출하지 않고 새 행도 만들지 않는다")
    void skipsTickerWithoutProvisionalRow() {
        ClosePriceConfirmResult result = collectService.confirmClose(FRI, List.of("111111"));

        assertThat(result.changes()).isEmpty();
        assertThat(result.checked()).isZero();
        assertThat(stockPriceRepository.count()).isZero();
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("응답에 그날 행 또는 직전 거래일 행이 없으면 바꾸지 않고 실패로 센다 — 0으로 채우지 않는다")
    void missingRowIsFailure() {
        provisional("005930", FRI, "223000", "232000", "-3.88", 1L);
        provisional("000660", FRI, "500000", "490000", "2.04", 1L);
        doReturn(List.of(row(MON, "229000", "1.78", 10L), row(THU, "232000", "-4.13", 1L)))
                .when(kis).getDailyPrices("005930");
        doReturn(List.of(row(FRI, "510000", "4.08", 2L)))
                .when(kis).getDailyPrices("000660");

        ClosePriceConfirmResult result = collectService.confirmClose(FRI, List.of("005930", "000660"));

        assertThat(result.changes()).isEmpty();
        assertThat(result.failed()).isEqualTo(2);
        assertThat(stored("005930", FRI).getClosePrice()).isEqualByComparingTo("223000");
        assertThat(stored("000660", FRI).getClosePrice()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("오늘 · 미래 날짜는 확정하지 않는다 — 호출 없이 빈 결과")
    void rejectsTodayAndFuture() {
        provisional("005930", MON, "229000", "225000", "1.78", 1L);

        ClosePriceConfirmResult result = collectService.confirmClose(MON, List.of("005930"));

        assertThat(result.changes()).isEmpty();
        assertThat(result.checked()).isZero();
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("한 종목 호출 실패는 그 종목만 실패 — 연속 실패가 한도(5회)에 닿으면 남은 종목은 호출하지 않고 중단")
    void isolatesFailuresAndAborts() {
        List<String> tickers = List.of("000001", "000002", "000003", "000004", "000005", "000006");
        tickers.forEach(t -> provisional(t, FRI, "1000", "1000", "0", 1L));
        doThrow(new RuntimeException("테스트 전용 장애")).when(kis).getDailyPrices(anyString());

        ClosePriceConfirmResult result = collectService.confirmClose(FRI, tickers);

        assertThat(result.aborted()).isTrue();
        assertThat(result.failed()).isEqualTo(6);
        verify(kis, times(5)).getDailyPrices(anyString());
    }
}
