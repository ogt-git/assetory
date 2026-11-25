package com.project.assetory.market.repository;

import com.project.assetory.market.domain.StockPrice;
import org.springframework.data.domain.Limit;
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

    // 급등락 감지 — 여러 종목의 그날 시세를 한 번에 (UNIQUE (ticker, trade_date) 사용). 거래정지 등 그날 행이 없는 종목은 결과에 없다
    List<StockPrice> findAllByTickerInAndTradeDate(Collection<String> tickers, LocalDate tradeDate);

    // 과거 이벤트 소급 — 그 종목의 기준일 이하 최신 N행 (최신부터). 행이 N개보다 적으면 있는 만큼
    List<StockPrice> findByTickerAndTradeDateLessThanEqualOrderByTradeDateDesc(String ticker, LocalDate tradeDate, Limit limit);

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
