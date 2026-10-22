package com.project.assetory.connect.domain;

import com.project.assetory.global.crypto.AesEncryptConverter;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// docs/05 §4.2 connected_accounts — 사용자·기관·업무구분당 1행 (같은 기관 재연결 시 이 행을 갱신)
@Entity
@Table(name = "connected_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_connected_accounts_user_institution_business",
                columnNames = {"user_id", "institution_id", "business_type"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_id", nullable = false)
    private Institution institution;

    // AES-GCM 암호문. 연동 해지 시 NULL로 파기 (D-21)
    @Convert(converter = AesEncryptConverter.class)
    @Column(name = "connected_id_enc", length = 512)
    private String connectedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 2)
    private BusinessType businessType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectedAccountStatus status;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    // 증권 계좌 비밀번호 (N-5) — 증권 자산 조회 단계에서 사용. 해지 시 NULL로 파기
    @Convert(converter = AesEncryptConverter.class)
    @Column(name = "account_password_enc", length = 512)
    private String accountPassword;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ConnectedAccount create(User user, Institution institution, String connectedId,
                                          BusinessType businessType) {
        ConnectedAccount account = new ConnectedAccount();
        account.user = user;
        account.institution = institution;
        account.connectedId = connectedId;
        account.businessType = businessType;
        account.status = ConnectedAccountStatus.ACTIVE;
        return account;
    }

    // 같은 기관 재연결·재인증 — 새 connectedId로 교체하고 ACTIVE로
    public void reconnect(String newConnectedId) {
        this.connectedId = newConnectedId;
        this.status = ConnectedAccountStatus.ACTIVE;
    }

    // 연동 해지 — 행은 남기고 기관 인증 정보는 즉시 파기 (D-21)
    public void disconnect() {
        this.connectedId = null;
        this.accountPassword = null;
        this.status = ConnectedAccountStatus.DISCONNECTED;
    }

    // 증권 계좌 비밀번호 — 입력이 없으면 기존 값 유지
    public void changeAccountPassword(String accountPassword) {
        if (accountPassword != null && !accountPassword.isBlank()) {
            this.accountPassword = accountPassword;
        }
    }

    public void changeStatus(ConnectedAccountStatus status) {
        this.status = status;
    }

    public void markSynced(LocalDateTime syncedAt) {
        this.lastSyncedAt = syncedAt;
    }

    public boolean isActive() {
        return status == ConnectedAccountStatus.ACTIVE;
    }
}
