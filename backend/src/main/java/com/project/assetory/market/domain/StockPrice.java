package com.project.assetory.market.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * docs/05 §4.9 stock_prices — 종목 일별 시세. 사용자와 무관한 공유 데이터, UNIQUE (ticker, trade_date) 기준 upsert.
 * - close_price: 원주가(수정주가 미반영) 종가
 * - change_rate: 한투 prdy_ctrt 그대로 (기준가 대비, 기업행사 조정됨 — 01 §14 #5, V1). 직접 계산하지 않는다
 * - prev_close_price: 직전 거래일 행의 종가(참고용). 분할일에는 change_rate ≠ 종가 ÷ 전일 종가 − 1일 수 있다
 */
@Entity
@Table(name = "stock_prices",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_prices_ticker_date", columnNames = {"ticker", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockPrice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal closePrice;

    @Column(name = "prev_close_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal prevClosePrice;

    @Column(name = "change_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal changeRate;

    @Column(nullable = false)
    private Long volume;

    public static StockPrice create(String ticker, LocalDate tradeDate) {
        StockPrice price = new StockPrice();
        price.ticker = ticker;
        price.tradeDate = tradeDate;
        return price;
    }

    public void update(String stockName, BigDecimal closePrice, BigDecimal prevClosePrice,
                       BigDecimal changeRate, long volume) {
        this.stockName = stockName;
        this.closePrice = closePrice;
        this.prevClosePrice = prevClosePrice;
        this.changeRate = changeRate;
        this.volume = volume;
    }
}
