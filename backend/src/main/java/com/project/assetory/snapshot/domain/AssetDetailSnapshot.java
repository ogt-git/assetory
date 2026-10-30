package com.project.assetory.snapshot.domain;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * docs/05 §4.8 asset_detail_snapshots — 자산별 일별 기록. 수집에 실패한 자산 · 종목은 행을 만들지 않는다(직전 값 복사 금지).
 * ticker NULL = 자산 단위(증권 계좌 예수금 · 수기 자산). MySQL UNIQUE는 NULL끼리의 중복을 막지 못하므로
 * ticker NULL 행의 멱등은 저장 서비스의 조회 후 갱신으로 보장한다.
 */
@Entity
@Table(name = "asset_detail_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_detail_snapshots_asset_ticker_date",
                columnNames = {"asset_id", "ticker", "snapshot_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetDetailSnapshot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(length = 20)
    private String ticker;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    // 종목 행만 — 평가에 쓴 보유수량 · 단가 (amount = HALF_UP2(quantity × price)). ticker NULL 행은 NULL (05 §4.8)
    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 2)
    private BigDecimal price;

    // 같은 (asset_id, ticker)의 직전 detail 행 대비. 직전 행이 없으면 NULL (0은 "변화 없음"으로 읽히므로 쓰지 않음)
    @Column(name = "day_change_amount", precision = 18, scale = 2)
    private BigDecimal dayChangeAmount;

    public static AssetDetailSnapshot create(Asset asset, String ticker, LocalDate snapshotDate) {
        AssetDetailSnapshot detail = new AssetDetailSnapshot();
        detail.asset = asset;
        detail.ticker = ticker;
        detail.snapshotDate = snapshotDate;
        return detail;
    }

    public void apply(BigDecimal amount, BigDecimal quantity, BigDecimal price, BigDecimal dayChangeAmount) {
        this.amount = amount;
        this.quantity = quantity;
        this.price = price;
        this.dayChangeAmount = dayChangeAmount;
    }
}
