package com.project.assetory.auth.service;

import com.project.assetory.auth.dto.LoginRequest;
import com.project.assetory.auth.dto.LoginResponse;
import com.project.assetory.auth.dto.SignupRequest;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.crypto.MaskingUtil;
import com.project.assetory.global.exception.AuthException;
import com.project.assetory.global.exception.DuplicateEmailException;
import com.project.assetory.global.security.JwtUtil;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public record LoginResult(LoginResponse loginResponse, String refreshToken) {}
    public record RefreshResult(String newAccessToken, String newRefreshToken) {}

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisTokenService redisTokenService;
    private final EmailVerificationService emailVerificationService;

    public void signup(SignupRequest request) {
        emailVerificationService.validateAndConsume(request.getEmail(), request.getVerificationToken());

        String email = normalizeEmail(request.getEmail());
        String emailHash = HashUtil.sha256Base64(email);
        if (userRepository.existsByEmailHash(emailHash)) {
            throw new DuplicateEmailException("이미 사용 중인 이메일입니다. 다른 이메일을 사용해주세요.");
        }

        User user = User.create(
                email,
                emailHash,
                passwordEncoder.encode(request.getPassword()),
                request.getNickname(),
                request.getTermsVersion()
        );

        userRepository.save(user);
    }

    public LoginResult login(LoginRequest request) {
        String emailHash = HashUtil.sha256Base64(normalizeEmail(request.getEmail()));
        User user = userRepository.findByEmailHash(emailHash)
                .orElseThrow(() -> new AuthException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        // 비밀번호 확인 뒤에 판정 — 상태 메시지로 가입 여부가 드러나지 않게
        if (!user.isActive()) {
            throw new AuthException("이용할 수 없는 계정입니다.");
        }

        return issueLoginTokens(user);
    }

    // JWT AT/RT 발급 + Redis RTR 저장
    public LoginResult issueLoginTokens(User user) {
        String accessToken  = jwtUtil.generateAccessToken(user.getId());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        redisTokenService.saveRefreshToken(user.getId(), refreshToken);

        LoginResponse loginResponse = new LoginResponse(user.getId(), accessToken, user.getNickname(),
                MaskingUtil.maskEmail(user.getEmail()));
        return new LoginResult(loginResponse, refreshToken);
    }

    // RTR: 리프레시 토큰 검증 → 새 토큰 쌍 발급, 기존 토큰은 회전 표식으로 바꿈
    public RefreshResult refresh(String refreshToken) {
        // Access Token을 refresh에 넣는 경우도 여기서 차단
        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw new AuthException("유효하지 않은 리프레시 토큰입니다.");
        }

        User user = userRepository.findById(jwtUtil.getUserId(refreshToken))
                .orElseThrow(() -> new AuthException("존재하지 않는 사용자입니다."));

        if (!user.isActive()) {
            redisTokenService.revokeAll(user.getId());
            throw new AuthException("이용할 수 없는 계정입니다.");
        }

        String newAccessToken  = jwtUtil.generateAccessToken(user.getId());
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());

        switch (redisTokenService.rotate(user.getId(), refreshToken, newRefreshToken)) {
            case ROTATED -> {
                return new RefreshResult(newAccessToken, newRefreshToken);
            }
            case REUSED -> {
                // 이미 회전된 RT가 다시 들어옴 → 탈취 의심, 해당 사용자 전체 세션 무효화
                redisTokenService.revokeAll(user.getId());
                log.warn("[Auth] 회전된 RT 재사용 감지 — 전체 RT 무효화 userId={}", user.getId());
                throw new AuthException("리프레시 토큰이 재사용되었습니다. 다시 로그인해주세요.");
            }
            default -> throw new AuthException("유효하지 않은 리프레시 토큰입니다. 다시 로그인해주세요.");
        }
    }

    // 현재 RT만 무효화 (다른 기기 세션은 유지)
    public void logout(Long userId, String refreshToken) {
        if (refreshToken != null) {
            redisTokenService.revoke(userId, refreshToken);
        }
    }

    // 사용자 단위 전체 무효화 — 회원 탈퇴·비밀번호 재설정에서 호출
    public void revokeAllSessions(Long userId) {
        redisTokenService.revokeAll(userId);
    }

    // 대소문자·공백만 다른 이메일이 별개 계정으로 가입되지 않게 한다
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
