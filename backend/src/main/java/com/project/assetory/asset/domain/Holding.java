package com.project.assetory.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// docs/05 §4.6 holdings — UNIQUE(asset_id, ticker) 기준 upsert
@Entity
@Table(name = "holdings",
        uniqueConstraints = @UniqueConstraint(name = "uk_holdings_asset_ticker", columnNames = {"asset_id", "ticker"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    // CODEF 응답으로 확실히 구분될 때만 채운다 — 판별 규칙 확인 전까지 NULL
    @Enumerated(EnumType.STRING)
    @Column(name = "security_type", length = 10)
    private SecurityType securityType;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "avg_buy_price", precision = 18, scale = 2)
    private BigDecimal avgBuyPrice;

    @Column(name = "current_price", precision = 18, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "eval_amount", precision = 18, scale = 2)
    private BigDecimal evalAmount;

    @Column(name = "profit_loss", precision = 18, scale = 2)
    private BigDecimal profitLoss;

    @Column(name = "profit_loss_rate", precision = 8, scale = 4)
    private BigDecimal profitLossRate;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static Holding create(Asset asset, String ticker) {
        Holding holding = new Holding();
        holding.asset = asset;
        holding.ticker = ticker;
        return holding;
    }

    public void update(String stockName, SecurityType securityType, BigDecimal quantity, BigDecimal avgBuyPrice,
                       BigDecimal currentPrice, BigDecimal evalAmount, BigDecimal profitLoss, BigDecimal profitLossRate) {
        this.stockName = stockName;
        this.securityType = securityType;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.currentPrice = currentPrice;
        this.evalAmount = evalAmount;
        this.profitLoss = profitLoss;
        this.profitLossRate = profitLossRate;
    }
}
