package com.project.assetory.market.repository;

import com.project.assetory.market.domain.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    Optional<StockPrice> findByTickerAndTradeDate(String ticker, LocalDate tradeDate);

    // 거래정지 종목 평가용 — 기준일 이전 최신 종가
    Optional<StockPrice> findFirstByTickerAndTradeDateBeforeOrderByTradeDateDesc(String ticker, LocalDate tradeDate);
}
