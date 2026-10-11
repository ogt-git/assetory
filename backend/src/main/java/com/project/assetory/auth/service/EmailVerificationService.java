package com.project.assetory.auth.service;

import com.project.assetory.auth.mail.AuthMailSender;
import com.project.assetory.auth.support.OtpStore;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.exception.DuplicateEmailException;
import com.project.assetory.global.exception.TooManyRequestsException;
import com.project.assetory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * 가입 전 이메일 인증: 인증코드 요청 → 코드 확인 → verificationToken 발급 → 회원가입 시 1회 소비.
 *
 * <pre>
 * auth:email-verify:code:{emailHash}      → sha256(인증코드)   TTL 5분
 * auth:email-verify:attempt:{emailHash}   → 검증 실패 횟수      TTL 5분 (5회면 코드 폐기)
 * auth:email-verify:cooldown:{emailHash}  → 재발송 쿨다운       TTL 60초
 * auth:email-verify:token:{sha256(token)} → emailHash          TTL 10분 (가입 시 GETDEL)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String PREFIX          = "auth:email-verify:";
    private static final String CODE_PREFIX     = PREFIX + "code:";
    private static final String ATTEMPT_PREFIX  = PREFIX + "attempt:";
    private static final String COOLDOWN_PREFIX = PREFIX + "cooldown:";
    private static final String TOKEN_PREFIX    = PREFIX + "token:";
    private static final Duration OTP_TTL       = Duration.ofMinutes(5);
    private static final Duration COOLDOWN      = Duration.ofSeconds(60);
    private static final Duration TOKEN_TTL     = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final OtpStore otpStore;
    private final AuthMailSender authMailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public void sendOtp(String rawEmail) {
        String email = AuthService.normalizeEmail(rawEmail);
        String emailHash = HashUtil.sha256Base64(email);

        if (userRepository.existsByEmailHash(emailHash)) {
            throw new DuplicateEmailException("이미 사용 중인 이메일입니다.");
        }

        String cooldownKey = COOLDOWN_PREFIX + emailHash;
        if (!otpStore.tryAcquireCooldown(cooldownKey, COOLDOWN)) {
            throw new TooManyRequestsException("인증코드는 60초에 한 번만 요청할 수 있습니다.");
        }

        String otp = generateOtp();
        String codeKey = CODE_PREFIX + emailHash;
        otpStore.issue(codeKey, ATTEMPT_PREFIX + emailHash, otp, OTP_TTL);

        try {
            authMailSender.sendVerificationCode(email, otp);
        } catch (RuntimeException e) {
            otpStore.clear(cooldownKey, codeKey); // 발송 실패면 바로 다시 요청할 수 있게
            throw e;
        }
        log.info("[EmailVerify] OTP 발송 - emailHash={}", emailHash);
    }

    public String verifyOtp(String rawEmail, String otp) {
        String emailHash = HashUtil.sha256Base64(AuthService.normalizeEmail(rawEmail));

        return switch (otpStore.verify(CODE_PREFIX + emailHash, ATTEMPT_PREFIX + emailHash, otp, OTP_TTL)) {
            case VERIFIED -> {
                String token = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set(TOKEN_PREFIX + HashUtil.sha256Hex(token), emailHash, TOKEN_TTL);
                log.info("[EmailVerify] 인증 완료 - emailHash={}", emailHash);
                yield token;
            }
            case MISMATCH -> throw new IllegalArgumentException("인증코드가 올바르지 않습니다.");
            case LOCKED -> throw new TooManyRequestsException("인증 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요.");
            case EXPIRED -> throw new IllegalStateException("인증코드가 만료되었습니다. 다시 요청해주세요.");
        };
    }

    // 회원가입 시 토큰 유효성 검증 후 삭제 (GETDEL — 같은 토큰으로 두 번 가입 불가)
    public void validateAndConsume(String rawEmail, String token) {
        String expectedHash = HashUtil.sha256Base64(AuthService.normalizeEmail(rawEmail));
        String storedHash = redisTemplate.opsForValue().getAndDelete(TOKEN_PREFIX + HashUtil.sha256Hex(token));

        if (storedHash == null || !storedHash.equals(expectedHash)) {
            throw new IllegalStateException("이메일 인증이 완료되지 않았습니다. 다시 인증해주세요.");
        }
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
