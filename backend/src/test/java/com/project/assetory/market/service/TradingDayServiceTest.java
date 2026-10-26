package com.project.assetory.market.service;

import com.project.assetory.market.domain.TradingDayStatus;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.kis.KisFixtures;
import com.project.assetory.market.config.MarketProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 거래일 판정 (FR-MK-06, 01 §14 #23 (c)안) — 장애를 휴장일로 바꾸지 않는다
class TradingDayServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // daily-price-success.json의 최신 거래일은 2026-10-12(월), 그 앞은 2026-10-08(목)
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    private final KisApiClient kis = mock(KisApiClient.class);
    private final MarketProperties properties = new MarketProperties("005930", LocalTime.of(15, 40), 5, List.of(), LocalTime.of(16, 40));

    private TradingDayService serviceAt(LocalDateTime now) {
        return new TradingDayService(kis, properties, Clock.fixed(now.atZone(KST).toInstant(), KST));
    }

    @Test
    @DisplayName("장 마감 지연일(수능일): 지연 가드(16:40) 전 오늘은 UNKNOWN, 이후엔 TRADING_DAY")
    void lateCloseDate_usesLateGuard() {
        doReturn(KisFixtures.dailyPrices("daily-price-success.json")).when(kis).getDailyPrices("005930");
        MarketProperties lateClose = new MarketProperties("005930", LocalTime.of(15, 40), 5, List.of(MON), LocalTime.of(16, 40));
        Clock at1600 = Clock.fixed(MON.atTime(16, 0).atZone(KST).toInstant(), KST);
        Clock at1650 = Clock.fixed(MON.atTime(16, 50).atZone(KST).toInstant(), KST);

        assertThat(new TradingDayService(kis, lateClose, at1600).check(MON)).isEqualTo(TradingDayStatus.UNKNOWN);
        assertThat(new TradingDayService(kis, lateClose, at1650).check(MON)).isEqualTo(TradingDayStatus.TRADING_DAY);
    }

    @Test
    @DisplayName("토 · 일은 API를 부르지 않고 HOLIDAY")
    void weekend_noCall() {
        TradingDayService service = serviceAt(LocalDateTime.of(2026, 10, 17, 16, 0));

        assertThat(service.check(LocalDate.of(2026, 10, 17))).isEqualTo(TradingDayStatus.HOLIDAY);
        assertThat(service.check(LocalDate.of(2026, 10, 18))).isEqualTo(TradingDayStatus.HOLIDAY);
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("기준 종목 최신 거래일 == 기준 날짜 → TRADING_DAY (기준 종목으로 조회)")
    void latestIsToday_tradingDay() {
        doReturn(KisFixtures.dailyPrices("daily-price-success.json")).when(kis).getDailyPrices("005930");

        assertThat(serviceAt(MON.atTime(16, 0)).check(MON)).isEqualTo(TradingDayStatus.TRADING_DAY);
    }

    @Test
    @DisplayName("평일인데 최신 거래일 < 기준 날짜 → HOLIDAY (예: 2026-10-09 한글날)")
    void latestBefore_holiday() {
        LocalDate hangulDay = LocalDate.of(2026, 10, 9);
        doReturn(KisFixtures.dailyPrices("daily-price-success.json").subList(1, 2)).when(kis).getDailyPrices("005930");

        assertThat(serviceAt(hangulDay.atTime(16, 0)).check(hangulDay)).isEqualTo(TradingDayStatus.HOLIDAY);
    }

    @Test
    @DisplayName("지난 날짜도 응답 범위 안이면 판정한다 — 있으면 TRADING_DAY, 범위 안에서 빠져 있으면 HOLIDAY")
    void pastDateWithinRange() {
        doReturn(KisFixtures.dailyPrices("daily-price-success.json")).when(kis).getDailyPrices("005930");
        TradingDayService service = serviceAt(LocalDateTime.of(2026, 10, 13, 16, 0));

        assertThat(service.check(LocalDate.of(2026, 10, 8))).isEqualTo(TradingDayStatus.TRADING_DAY);
        assertThat(service.check(LocalDate.of(2026, 10, 9))).isEqualTo(TradingDayStatus.HOLIDAY);
    }

    @Test
    @DisplayName("API 실패 → UNKNOWN (HOLIDAY로 처리하지 않는다)")
    void apiFailure_unknown() {
        when(kis.getDailyPrices("005930")).thenThrow(new KisApiException(KisErrorType.NETWORK_ERROR, null));

        assertThat(serviceAt(MON.atTime(16, 0)).check(MON)).isEqualTo(TradingDayStatus.UNKNOWN);
    }

    @Test
    @DisplayName("응답이 비어 있거나 기준 날짜가 응답 범위보다 오래되면 UNKNOWN")
    void emptyOrOutOfRange_unknown() {
        when(kis.getDailyPrices("005930")).thenReturn(List.of());
        assertThat(serviceAt(MON.atTime(16, 0)).check(MON)).isEqualTo(TradingDayStatus.UNKNOWN);

        doReturn(KisFixtures.dailyPrices("daily-price-success.json")).when(kis).getDailyPrices("005930");
        assertThat(serviceAt(MON.atTime(16, 0)).check(LocalDate.of(2026, 9, 1))).isEqualTo(TradingDayStatus.UNKNOWN);
    }

    @Test
    @DisplayName("오늘을 장 마감 가드(15:40) 전에 판정하면 UNKNOWN — 개장 전에는 오늘 행이 없어 휴장으로 오판할 수 있다")
    void todayBeforeCloseGuard_unknown() {
        assertThat(serviceAt(MON.atTime(8, 30)).check(MON)).isEqualTo(TradingDayStatus.UNKNOWN);
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("미래 날짜는 UNKNOWN")
    void future_unknown() {
        assertThat(serviceAt(MON.atTime(16, 0)).check(MON.plusDays(1))).isEqualTo(TradingDayStatus.UNKNOWN);
    }
}
