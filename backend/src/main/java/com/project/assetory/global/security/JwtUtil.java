package com.project.assetory.global.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

// sub = userId, typ = access|refresh. RT에는 jti를 넣어 같은 시각에 발급돼도 토큰(=저장 해시)이 겹치지 않게 한다
@Slf4j
@Component
public class JwtUtil {

    private static final String TYPE_CLAIM = "typ";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtUtil(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-ms}") long accessExpirationMs,
            @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        // 키가 없거나 짧으면 기동 실패 (메시지에 키 값은 넣지 않음)
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET이 없거나 32바이트보다 짧습니다.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirationMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }

    public Long getUserId(String token) {
        return Long.valueOf(getClaims(token).getSubject());
    }

    public boolean isAccessToken(String token) {
        return hasType(token, ACCESS);
    }

    public boolean isRefreshToken(String token) {
        return hasType(token, REFRESH);
    }

    private boolean hasType(String token, String type) {
        if (!isValid(token)) return false;
        return type.equals(getClaims(token).get(TYPE_CLAIM, String.class));
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] 토큰 만료됨 — 클라이언트가 refresh를 요청해야 합니다.");
            return false;
        } catch (JwtException e) {
            // 예외 메시지에 토큰 헤더·클레임 일부가 들어갈 수 있어 종류만 남긴다
            log.warn("[JWT] 토큰 검증 실패: {}", e.getClass().getSimpleName());
            return false;
        } catch (Exception e) {
            log.warn("[JWT] 예상치 못한 오류: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
