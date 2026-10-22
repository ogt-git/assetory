package com.project.assetory.auth.service;

import com.project.assetory.auth.service.RedisTokenService.RotationResult;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.security.JwtUtil;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTokenServiceTest extends IntegrationTestSupport {

    private static final AtomicLong USER_SEQ = new AtomicLong(900_000);

    @Autowired RedisTokenService tokenService;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtUtil jwtUtil;

    @Test
    @DisplayName("원문이 아니라 SHA-256 해시를 키로 저장, TTL = RT 만료")
    void storesHashWithRefreshTtl() {
        long userId = USER_SEQ.incrementAndGet();
        String rt = jwtUtil.generateRefreshToken(userId);

        tokenService.saveRefreshToken(userId, rt);

        String key = "auth:rt:token:" + HashUtil.sha256Hex(rt);
        assertThat(redis.opsForValue().get(key)).isEqualTo(String.valueOf(userId));
        assertThat(redis.hasKey("auth:rt:token:" + rt)).isFalse();
        assertThat(redis.opsForSet().isMember("auth:rt:user:" + userId, HashUtil.sha256Hex(rt))).isTrue();

        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(jwtUtil.getRefreshExpirationMs() - 5_000, jwtUtil.getRefreshExpirationMs());
        assertThat(redis.getExpire("auth:rt:user:" + userId, TimeUnit.MILLISECONDS))
                .isBetween(jwtUtil.getRefreshExpirationMs() - 5_000, jwtUtil.getRefreshExpirationMs());
    }

    @Test
    @DisplayName("회전: 새 RT 저장, 이전 RT는 회전 표식으로 남아 TTL 유지 → 다시 쓰면 REUSED")
    void rotateMarksOldToken() {
        long userId = USER_SEQ.incrementAndGet();
        String rt1 = jwtUtil.generateRefreshToken(userId);
        String rt2 = jwtUtil.generateRefreshToken(userId);
        tokenService.saveRefreshToken(userId, rt1);

        assertThat(tokenService.rotate(userId, rt1, rt2)).isEqualTo(RotationResult.ROTATED);

        String oldKey = "auth:rt:token:" + HashUtil.sha256Hex(rt1);
        assertThat(redis.opsForValue().get(oldKey)).isEqualTo("rotated:" + userId);
        assertThat(redis.getExpire(oldKey, TimeUnit.MILLISECONDS)).isPositive();

        assertThat(tokenService.rotate(userId, rt1, jwtUtil.generateRefreshToken(userId)))
                .isEqualTo(RotationResult.REUSED);
    }

    @Test
    @DisplayName("다른 사용자의 RT·없는 RT로 회전 시도 → INVALID")
    void rotateRejectsForeignOrUnknown() {
        long owner = USER_SEQ.incrementAndGet();
        long attacker = USER_SEQ.incrementAndGet();
        String rt = jwtUtil.generateRefreshToken(owner);
        tokenService.saveRefreshToken(owner, rt);

        assertThat(tokenService.rotate(attacker, rt, jwtUtil.generateRefreshToken(attacker)))
                .isEqualTo(RotationResult.INVALID);
        assertThat(tokenService.rotate(owner, "never-issued", jwtUtil.generateRefreshToken(owner)))
                .isEqualTo(RotationResult.INVALID);
    }

    @Test
    @DisplayName("revokeAll → 사용자의 모든 토큰 키와 인덱스 삭제, 다른 사용자는 영향 없음")
    void revokeAllRemovesEverything() {
        long userId = USER_SEQ.incrementAndGet();
        long otherUser = USER_SEQ.incrementAndGet();
        String a = jwtUtil.generateRefreshToken(userId);
        String b = jwtUtil.generateRefreshToken(userId);
        String other = jwtUtil.generateRefreshToken(otherUser);
        tokenService.saveRefreshToken(userId, a);
        tokenService.saveRefreshToken(userId, b);
        tokenService.saveRefreshToken(otherUser, other);

        tokenService.revokeAll(userId);

        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(a))).isFalse();
        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(b))).isFalse();
        assertThat(redis.hasKey("auth:rt:user:" + userId)).isFalse();
        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(other))).isTrue();
    }

    @Test
    @DisplayName("revoke(로그아웃) → 본인 RT 하나만 삭제, 다른 사용자 명의로는 삭제되지 않음")
    void revokeOnlyOwnToken() {
        long userId = USER_SEQ.incrementAndGet();
        String rt = jwtUtil.generateRefreshToken(userId);
        String keep = jwtUtil.generateRefreshToken(userId);
        tokenService.saveRefreshToken(userId, rt);
        tokenService.saveRefreshToken(userId, keep);

        tokenService.revoke(userId + 1, rt);
        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(rt))).isTrue();

        tokenService.revoke(userId, rt);
        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(rt))).isFalse();
        assertThat(redis.hasKey("auth:rt:token:" + HashUtil.sha256Hex(keep))).isTrue();
    }
}
