package com.project.assetory.auth.controller;

import com.project.assetory.auth.dto.LoginRequest;
import com.project.assetory.auth.dto.LoginResponse;
import com.project.assetory.auth.dto.SignupRequest;
import com.project.assetory.auth.dto.TokenResponse;
import com.project.assetory.auth.service.AuthService;
import com.project.assetory.auth.support.RefreshTokenCookieProvider;
import com.project.assetory.global.exception.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieProvider refreshTokenCookieProvider;

    @PostMapping("/signup")
    public ResponseEntity<String> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.ok("회원가입 성공");
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request);
        refreshTokenCookieProvider.addCookie(response, result.refreshToken());
        return ResponseEntity.ok(result.loginResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            throw new AuthException("리프레시 토큰이 없습니다. 다시 로그인해주세요.");
        }
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        TokenResponse tokenResponse = new TokenResponse(result.newAccessToken(), result.newRefreshToken());
        refreshTokenCookieProvider.addCookie(response, tokenResponse.getRefreshToken());
        return ResponseEntity.ok(tokenResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletResponse response) {
        authService.logout((Long) authentication.getPrincipal());
        refreshTokenCookieProvider.clearCookie(response);
        return ResponseEntity.ok().build();
    }
}
