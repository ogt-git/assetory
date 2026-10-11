package com.project.assetory.auth.service;

import com.project.assetory.global.exception.DuplicateEmailException;
import com.project.assetory.global.exception.TooManyRequestsException;
import com.project.assetory.user.repository.UserRepository;
import com.project.assetory.global.crypto.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String OTP_PREFIX      = "signup:otp:";
    private static final String COOLDOWN_PREFIX = "signup:cooldown:";
    private static final String TOKEN_PREFIX    = "signup:verified:";
    private static final long   OTP_TTL_MIN     = 5;
    private static final long   COOLDOWN_SEC    = 15;
    private static final long   TOKEN_TTL_MIN   = 10;

    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public void sendOtp(String email) {
        String emailHash = HashUtil.sha256Base64(email);

        if (userRepository.existsByEmailHash(emailHash)) {
            throw new DuplicateEmailException("이미 사용 중인 이메일입니다.");
        }

        String cooldownKey = COOLDOWN_PREFIX + emailHash;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new TooManyRequestsException("인증코드는 15초에 한 번만 요청할 수 있습니다.");
        }

        String otp = generateOtp();
        redisTemplate.opsForValue().set(OTP_PREFIX + emailHash, otp, OTP_TTL_MIN, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_SEC, TimeUnit.SECONDS);

        sendEmail(email, otp);
        log.info("[EmailVerify] OTP 발송 - emailHash={}", emailHash);
    }

    public String verifyOtp(String email, String otp) {
        String emailHash = HashUtil.sha256Base64(email);
        String otpKey = OTP_PREFIX + emailHash;

        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            throw new IllegalStateException("인증코드가 만료되었습니다. 다시 요청해주세요.");
        }
        if (!storedOtp.equals(otp)) {
            throw new IllegalArgumentException("인증코드가 올바르지 않습니다.");
        }

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(TOKEN_PREFIX + token, emailHash, TOKEN_TTL_MIN, TimeUnit.MINUTES);
        redisTemplate.delete(otpKey);

        log.info("[EmailVerify] 인증 완료 - emailHash={}", emailHash);
        return token;
    }

    // 회원가입 시 토큰 유효성 검증 후 삭제
    public void validateAndConsume(String email, String token) {
        String expectedHash = HashUtil.sha256Base64(email);
        String storedHash = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);

        if (storedHash == null || !storedHash.equals(expectedHash)) {
            throw new IllegalStateException("이메일 인증이 완료되지 않았습니다. 다시 인증해주세요.");
        }

        redisTemplate.delete(TOKEN_PREFIX + token);
    }

    private String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    private void sendEmail(String to, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("[Assetory] 이메일 인증코드");
        message.setText(
                "안녕하세요, Assetory입니다.\n\n" +
                "이메일 인증코드: " + otp + "\n\n" +
                "이 코드는 5분간 유효합니다.\n" +
                "본인이 요청하지 않으셨다면 이 이메일을 무시해주세요."
        );
        mailSender.send(message);
    }
}
