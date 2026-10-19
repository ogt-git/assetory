package com.project.assetory.snapshot.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import com.project.assetory.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * docs/05 §4.7 asset_snapshots — 일별 자산 기록. UNIQUE (user_id, snapshot_date) 기준 upsert.
 * 비교 · 감지 · 결산 · 목표 · 추이는 COMPLETE만 사용한다. 정기 배치에서만 만든다(수동 새로고침은 만들지 않음).
 */
@Entity
@Table(name = "asset_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_snapshots_user_date", columnNames = {"user_id", "snapshot_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetSnapshot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SnapshotStatus status;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Column(name = "total_asset", precision = 18, scale = 2)
    private BigDecimal totalAsset;

    @Column(name = "securities_amount", precision = 18, scale = 2)
    private BigDecimal securitiesAmount;

    @Column(name = "deposit_amount", precision = 18, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "cash_amount", precision = 18, scale = 2)
    private BigDecimal cashAmount;

    @Column(name = "base_snapshot_date")
    private LocalDate baseSnapshotDate;

    @Column(name = "day_change_amount", precision = 18, scale = 2)
    private BigDecimal dayChangeAmount;

    // 퍼센트 (1.2345% → 1.2345)
    @Column(name = "day_change_rate", precision = 8, scale = 4)
    private BigDecimal dayChangeRate;

    // TODO(01-§14 #1 #2 7주차): 시장가격 영향 · 기타 자산 변화 — 공식 확정 전까지 NULL
    @Column(name = "market_change_amount", precision = 18, scale = 2)
    private BigDecimal marketChangeAmount;

    @Column(name = "other_change_amount", precision = 18, scale = 2)
    private BigDecimal otherChangeAmount;

    @Column(name = "has_event", nullable = false)
    private boolean hasEvent;

    public static AssetSnapshot create(User user, LocalDate snapshotDate) {
        AssetSnapshot snapshot = new AssetSnapshot();
        snapshot.user = user;
        snapshot.snapshotDate = snapshotDate;
        return snapshot;
    }

    // 같은 날 다시 저장하면 판정 결과로 덮어쓴다 (재실행 정책은 배치 조립 단계에서 결정)
    public void apply(SnapshotJudgement judgement, LocalDateTime collectedAt, DayChange dayChange) {
        this.status = judgement.status();
        this.collectedAt = collectedAt;
        this.totalAsset = judgement.totalAsset();
        this.securitiesAmount = judgement.securitiesAmount();
        this.depositAmount = judgement.depositAmount();
        this.cashAmount = judgement.cashAmount();
        this.baseSnapshotDate = dayChange.baseSnapshotDate();
        this.dayChangeAmount = dayChange.amount();
        this.dayChangeRate = dayChange.rate();
        this.marketChangeAmount = null;
        this.otherChangeAmount = null;
    }
}
