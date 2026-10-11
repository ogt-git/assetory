package com.project.assetory.asset.domain;

import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.global.crypto.AesEncryptConverter;
import com.project.assetory.global.crypto.MaskingUtil;
import com.project.assetory.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// docs/05 §4.4 assets. 증권 계좌는 연동 계정당 1행 (D-14), balance = 예수금
@Entity
@Table(name = "assets")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 수기 자산은 NULL
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connected_account_id")
    private ConnectedAccount connectedAccount;

    // 수기 자산은 NULL 허용 (D-23)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "institution_id")
    private Institution institution;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private AssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 20)
    private AssetClass assetClass;

    @Column(nullable = false, length = 100)
    private String name;

    @Convert(converter = AesEncryptConverter.class)
    @Column(name = "account_number_enc", length = 512)
    private String accountNumber;

    @Column(name = "account_number_masked", length = 50)
    private String accountNumberMasked;

    // 증권 계좌는 예수금. 첫 수집 전에는 NULL (0으로 채우지 않음)
    @Column(precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // 증권 계좌 — 행의 balance가 예수금이므로 asset_class = CASH (종목 평가액은 holdings로 SECURITIES 집계)
    public static Asset securitiesAccount(User user, ConnectedAccount connectedAccount, Institution institution,
                                          String accountNumber) {
        Asset asset = new Asset();
        asset.user = user;
        asset.connectedAccount = connectedAccount;
        asset.institution = institution;
        asset.assetType = AssetType.SECURITIES;
        asset.assetClass = AssetClass.CASH;
        asset.name = institution.getName();
        asset.changeAccountNumber(accountNumber);
        asset.active = true;
        return asset;
    }

    // 수기 자산 — 연동 계정·기관 없음 (D-23)
    public static Asset manual(User user, AssetType assetType, String name, BigDecimal balance) {
        Asset asset = new Asset();
        asset.user = user;
        asset.assetType = assetType;
        asset.assetClass = AssetClass.of(assetType);
        asset.name = name;
        asset.balance = balance;
        asset.active = true;
        return asset;
    }

    public void updateManual(AssetType assetType, String name, BigDecimal balance) {
        this.assetType = assetType;
        this.assetClass = AssetClass.of(assetType);
        this.name = name;
        this.balance = balance;
    }

    public boolean isManual() {
        return connectedAccount == null;
    }

    // 재연결 — 계좌번호를 바꿀 수 있고 다시 활성화
    public void relink(String accountNumber) {
        changeAccountNumber(accountNumber);
        this.active = true;
    }

    public void updateBalance(BigDecimal balance) {
        this.balance = balance;
    }

    // 연동 해지 (D-21) — 행은 남겨 자산 기록 참조를 유지
    public void deactivate() {
        this.active = false;
    }

    private void changeAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
        this.accountNumberMasked = MaskingUtil.maskAccountNumber(accountNumber);
    }
}
