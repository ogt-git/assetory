package com.project.assetory.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

// docs/05 §4.5 deposit_details — 예금·적금 상세. PK = asset_id. 수기 DEPOSIT·SAVINGS는 필수 (D-19)
@Entity
@Table(name = "deposit_details")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositDetail {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    // 금리(%) — DECIMAL(5,3)
    @Column(name = "interest_rate", nullable = false, precision = 5, scale = 3)
    private BigDecimal interestRate;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal principal;

    // 개월
    @Column(name = "save_term", nullable = false)
    private Integer saveTerm;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    public static DepositDetail create(Asset asset, BigDecimal interestRate, LocalDate startDate, LocalDate maturityDate,
                                       BigDecimal principal, Integer saveTerm, boolean autoRenew) {
        DepositDetail detail = new DepositDetail();
        detail.asset = asset;
        detail.update(interestRate, startDate, maturityDate, principal, saveTerm, autoRenew);
        return detail;
    }

    public void update(BigDecimal interestRate, LocalDate startDate, LocalDate maturityDate,
                       BigDecimal principal, Integer saveTerm, boolean autoRenew) {
        this.interestRate = interestRate;
        this.startDate = startDate;
        this.maturityDate = maturityDate;
        this.principal = principal;
        this.saveTerm = saveTerm;
        this.autoRenew = autoRenew;
    }
}
