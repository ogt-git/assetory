package com.project.assetory.auth.support;

import com.project.assetory.global.security.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Refresh Token HttpOnly 쿠키 설정 (로컬/소셜 로그인 공통)
@Component
public class RefreshTokenCookieProvider {

    private static final String COOKIE_NAME = "refreshToken";
    private static final String COOKIE_PATH = "/api/auth";

    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final JwtUtil jwtUtil;

    public RefreshTokenCookieProvider(@Value("${app.cookie.secure}") boolean cookieSecure,
                                      @Value("${app.cookie.same-site}") String cookieSameSite,
                                      JwtUtil jwtUtil) {
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.jwtUtil = jwtUtil;
    }

    public void addCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path(COOKIE_PATH)
                .maxAge(Duration.ofMillis(jwtUtil.getRefreshExpirationMs()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
