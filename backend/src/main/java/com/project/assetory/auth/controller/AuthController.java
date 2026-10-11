package com.project.assetory.auth.controller;

import com.project.assetory.auth.dto.LoginRequest;
import com.project.assetory.auth.dto.LoginResponse;
import com.project.assetory.auth.dto.SignupRequest;
import com.project.assetory.auth.dto.TokenResponse;
import com.project.assetory.auth.service.AuthService;
import com.project.assetory.auth.support.RefreshTokenCookieProvider;
import com.project.assetory.global.exception.AuthException;
import com.project.assetory.global.response.ApiResponse;
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
    public ResponseEntity<ApiResponse<Void>> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.ok(ApiResponse.success(null, "회원가입이 완료되었습니다."));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request);
        refreshTokenCookieProvider.addCookie(response, result.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(result.loginResponse(), "로그인되었습니다."));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            throw new AuthException("리프레시 토큰이 없습니다. 다시 로그인해주세요.");
        }
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        TokenResponse tokenResponse = new TokenResponse(result.newAccessToken(), result.newRefreshToken());
        refreshTokenCookieProvider.addCookie(response, tokenResponse.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(tokenResponse, "토큰이 갱신되었습니다."));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(Authentication authentication,
                                                    @CookieValue(name = "refreshToken", required = false) String refreshToken,
                                                    HttpServletResponse response) {
        authService.logout((Long) authentication.getPrincipal(), refreshToken);
        refreshTokenCookieProvider.clearCookie(response);
        return ResponseEntity.ok(ApiResponse.success(null, "로그아웃되었습니다."));
    }
}
