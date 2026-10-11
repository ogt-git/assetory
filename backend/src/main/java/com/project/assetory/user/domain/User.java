package com.project.assetory.user.domain;

import com.project.assetory.global.crypto.AesEncryptConverter;
import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// docs/05 §3.1 users. 이메일은 AES-GCM 암호문(email_enc)으로 저장하고, 조회·중복 검사는 SHA-256 해시(email_hash)로 한다
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = AesEncryptConverter.class)
    @Column(name = "email_enc", nullable = false, length = 512)
    private String email;

    @Column(name = "email_hash", nullable = false, unique = true, length = 64)
    private String emailHash;

    // Bcrypt 60자. 소셜 전용 계정은 NULL (카카오 로그인 이식 시 사용)
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "terms_version", nullable = false, length = 20)
    private String termsVersion;

    public static User create(String email, String emailHash, String passwordHash,
                              String nickname, String termsVersion) {
        User user = new User();
        user.email = email;
        user.emailHash = emailHash;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        user.termsVersion = termsVersion;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
