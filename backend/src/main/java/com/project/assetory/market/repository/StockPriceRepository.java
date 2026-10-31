package com.project.assetory.market.repository;

import com.project.assetory.market.domain.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    Optional<StockPrice> findByTickerAndTradeDate(String ticker, LocalDate tradeDate);

    // 거래정지 종목 평가용 — 기준일 이전 최신 종가
    Optional<StockPrice> findFirstByTickerAndTradeDateBeforeOrderByTradeDateDesc(String ticker, LocalDate tradeDate);

    // 종목명 — ticker별 그 날짜 이하 최신 행의 이름을 한 번에 (거래정지 · 매도 종목도 이전 시세 이름으로 찾는다)
    @Query("""
            SELECT p.ticker AS ticker, p.stockName AS stockName FROM StockPrice p
            WHERE p.ticker IN :tickers
              AND p.tradeDate = (SELECT MAX(q.tradeDate) FROM StockPrice q WHERE q.ticker = p.ticker AND q.tradeDate <= :date)
            """)
    List<TickerName> findLatestNames(@Param("tickers") Collection<String> tickers, @Param("date") LocalDate date);

    interface TickerName {
        String getTicker();

        String getStockName();
    }
}
