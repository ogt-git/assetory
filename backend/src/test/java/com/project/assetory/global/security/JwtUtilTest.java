package com.project.assetory.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-real-use-0123456789";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 30 * 60 * 1000L, 14L * 24 * 60 * 60 * 1000);

    @Test
    @DisplayName("AT와 RT는 typ으로 구분된다")
    void typeSeparation() {
        String at = jwtUtil.generateAccessToken(1L);
        String rt = jwtUtil.generateRefreshToken(1L);

        assertThat(jwtUtil.isAccessToken(at)).isTrue();
        assertThat(jwtUtil.isRefreshToken(at)).isFalse();
        assertThat(jwtUtil.isRefreshToken(rt)).isTrue();
        assertThat(jwtUtil.isAccessToken(rt)).isFalse();
        assertThat(jwtUtil.getUserId(at)).isEqualTo(1L);
        assertThat(jwtUtil.getUserId(rt)).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 사용자·같은 시각에 발급해도 RT는 매번 다르다 (jti)")
    void refreshTokensAreUnique() {
        assertThat(jwtUtil.generateRefreshToken(1L)).isNotEqualTo(jwtUtil.generateRefreshToken(1L));
    }

    @Test
    @DisplayName("만료된 토큰·다른 키로 서명한 토큰은 유효하지 않다")
    void expiredOrForeignTokenInvalid() {
        JwtUtil expiring = new JwtUtil(SECRET, -1000L, -1000L);
        JwtUtil foreign = new JwtUtil("another-test-secret-that-is-long-enough-0123456789", 60_000L, 60_000L);

        assertThat(jwtUtil.isValid(expiring.generateAccessToken(1L))).isFalse();
        assertThat(jwtUtil.isValid(foreign.generateAccessToken(1L))).isFalse();
        assertThat(jwtUtil.isValid("not-a-jwt")).isFalse();
    }

    @Test
    @DisplayName("서명 키가 없거나 32바이트보다 짧으면 생성 실패 (기동 실패)")
    void weakSecretFailsFast() {
        assertThatThrownBy(() -> new JwtUtil("", 1L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtUtil("short-secret", 1L, 1L)).isInstanceOf(IllegalStateException.class);
    }
}
