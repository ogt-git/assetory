package com.project.assetory.auth.service;

import com.project.assetory.auth.mail.AuthMailSender;
import com.project.assetory.auth.support.OtpStore;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.exception.TooManyRequestsException;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

/**
 * 비밀번호 재설정: 인증코드 요청 → 코드 + 새 비밀번호로 재설정 → 해당 사용자 RT 전체 무효화.
 * 요청 단계는 가입 여부와 관계없이 같은 응답을 준다 (쿨다운도 동일하게 적용, 메일은 비동기 발송).
 *
 * <pre>
 * auth:pw-reset:code:{emailHash}      → sha256(인증코드)  TTL 5분, 1회용
 * auth:pw-reset:attempt:{emailHash}   → 검증 실패 횟수     TTL 5분 (5회면 코드 폐기)
 * auth:pw-reset:cooldown:{emailHash}  → 재발송 쿨다운      TTL 60초
 * </pre>
 */
@Slf4j
@Service
public class PasswordResetService {

    private static final String PREFIX          = "auth:pw-reset:";
    private static final String CODE_PREFIX     = PREFIX + "code:";
    private static final String ATTEMPT_PREFIX  = PREFIX + "attempt:";
    private static final String COOLDOWN_PREFIX = PREFIX + "cooldown:";
    private static final Duration OTP_TTL       = Duration.ofMinutes(5);
    private static final Duration COOLDOWN      = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpStore otpStore;
    private final AuthMailSender authMailSender;
    private final AuthService authService;
    private final TaskExecutor taskExecutor;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                OtpStore otpStore, AuthMailSender authMailSender, AuthService authService,
                                @Qualifier("authMailExecutor") TaskExecutor taskExecutor) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpStore = otpStore;
        this.authMailSender = authMailSender;
        this.authService = authService;
        this.taskExecutor = taskExecutor;
    }

    public void sendOtp(String rawEmail) {
        String email = AuthService.normalizeEmail(rawEmail);
        String emailHash = HashUtil.sha256Base64(email);

        // 쿨다운은 계정 존재 여부와 관계없이 적용 — 429 응답으로 가입 여부가 드러나지 않게
        if (!otpStore.tryAcquireCooldown(COOLDOWN_PREFIX + emailHash, COOLDOWN)) {
            throw new TooManyRequestsException("인증코드는 60초에 한 번만 요청할 수 있습니다.");
        }

        // 비밀번호로 로그인하는 활성 계정에만 실제로 코드를 발급한다. 그 외에는 아무것도 하지 않고 같은 응답
        Optional<User> target = userRepository.findByEmailHash(emailHash)
                .filter(User::isActive)
                .filter(user -> user.getPasswordHash() != null);
        if (target.isEmpty()) {
            return;
        }

        String otp = generateOtp();
        otpStore.issue(CODE_PREFIX + emailHash, ATTEMPT_PREFIX + emailHash, otp, OTP_TTL);

        // 발송은 비동기 — SMTP 소요 시간으로 계정 존재 여부가 드러나지 않게
        taskExecutor.execute(() -> {
            try {
                authMailSender.sendPasswordResetCode(email, otp);
                log.info("[PasswordReset] OTP 발송 완료 - emailHash={}", emailHash);
            } catch (RuntimeException e) {
                // 메일 예외에는 수신 주소가 들어갈 수 있어 예외 종류만 남긴다
                log.error("[PasswordReset] OTP 발송 실패 - emailHash={} error={}", emailHash, e.getClass().getSimpleName());
            }
        });
    }

    @Transactional
    public void resetPassword(String rawEmail, String otp, String newPassword) {
        String emailHash = HashUtil.sha256Base64(AuthService.normalizeEmail(rawEmail));

        switch (otpStore.verify(CODE_PREFIX + emailHash, ATTEMPT_PREFIX + emailHash, otp, OTP_TTL)) {
            case VERIFIED -> { }
            case MISMATCH -> throw new IllegalArgumentException("인증코드가 올바르지 않습니다.");
            case LOCKED -> throw new TooManyRequestsException("인증 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요.");
            case EXPIRED -> throw new IllegalStateException("인증코드가 만료되었습니다. 다시 요청해주세요.");
        }

        User user = userRepository.findByEmailHash(emailHash)
                .orElseThrow(() -> new IllegalStateException("인증코드가 만료되었습니다. 다시 요청해주세요."));

        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        authService.revokeAllSessions(user.getId()); // 기존 기기 세션 모두 로그아웃
        log.info("[PasswordReset] 비밀번호 재설정 완료 - userId={}", user.getId());
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
