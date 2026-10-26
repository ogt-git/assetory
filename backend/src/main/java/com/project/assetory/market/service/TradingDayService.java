package com.project.assetory.market.service;

import com.project.assetory.market.config.MarketProperties;
import com.project.assetory.market.domain.TradingDayStatus;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 거래일 판정 (FR-MK-06, 01 §14 #23 (c)안) — 주말은 코드로, 평일은 기준 종목의 일자별 시세에 그 날짜 행이 있는지로 본다.
 * 호출 실패 · 해석 불가 · 판정 불가는 UNKNOWN이다. 장애 난 날을 HOLIDAY("기록 없는 날")로 바꾸지 않는다.
 */
@Slf4j
@Service
public class TradingDayService {

    private final KisApiClient kis;
    private final MarketProperties properties;
    private final Clock clock;

    public TradingDayService(KisApiClient kis, MarketProperties properties, Clock clock) {
        this.kis = kis;
        this.properties = properties;
        this.clock = clock;
    }

    public TradingDayStatus check(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return TradingDayStatus.HOLIDAY;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (date.isAfter(now.toLocalDate())) {
            return TradingDayStatus.UNKNOWN;
        }
        // 오늘을 장 마감 가드 전에 보면 개장 전이라 오늘 행이 아직 없을 수 있다 → 휴장으로 오판하지 않게 UNKNOWN
        if (date.equals(now.toLocalDate()) && now.toLocalTime().isBefore(properties.closeGuardTime(date))) {
            return TradingDayStatus.UNKNOWN;
        }

        List<KisDailyPrice> prices;
        try {
            prices = kis.getDailyPrices(properties.referenceTicker());
        } catch (RuntimeException e) {
            log.warn("[Market] 거래일 판정 조회 실패 date={} error={}", date, e.getClass().getSimpleName());
            return TradingDayStatus.UNKNOWN;
        }
        if (prices.isEmpty()) {
            return TradingDayStatus.UNKNOWN;
        }
        if (prices.stream().anyMatch(p -> p.tradeDate().equals(date))) {
            return TradingDayStatus.TRADING_DAY;
        }
        LocalDate oldest = prices.stream().map(KisDailyPrice::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        // 응답 범위 안(가장 오래된 행 이후)인데 그 날짜 행이 없으면 휴장. 그보다 오래된 날짜는 판정할 수 없다
        return date.isAfter(oldest) ? TradingDayStatus.HOLIDAY : TradingDayStatus.UNKNOWN;
    }
}
