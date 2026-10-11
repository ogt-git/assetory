package com.project.assetory.auth.service;

import com.project.assetory.user.domain.User;
import com.project.assetory.global.exception.TooManyRequestsException;
import com.project.assetory.user.repository.UserRepository;
import com.project.assetory.global.crypto.HashUtil;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String OTP_PREFIX       = "otp:code:";
    private static final String COOLDOWN_PREFIX  = "otp:cooldown:";
    private static final long   OTP_TTL_MIN      = 5;
    private static final long   COOLDOWN_TTL_SEC = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, String> redisTemplate;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public void sendOtp(String email) {
        String emailHash = HashUtil.sha256Base64(email);

        // 이메일 존재 여부 확인
        User user = userRepository.findByEmailHash(emailHash)
                .orElseThrow(() -> new EntityNotFoundException("등록되지 않은 이메일입니다."));

        // 1분 쿨다운
        String cooldownKey = COOLDOWN_PREFIX + emailHash;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new TooManyRequestsException("인증코드는 15초에 한 번만 요청할 수 있습니다.");
        }

        String otp = generateOtp();
        String otpKey = OTP_PREFIX + emailHash;

        redisTemplate.opsForValue().set(otpKey, otp, OTP_TTL_MIN, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_TTL_SEC, TimeUnit.SECONDS);

        sendEmail(email, otp);
        log.info("[PasswordReset] OTP 발송 완료 - emailHash={}", emailHash);
    }

    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        String emailHash = HashUtil.sha256Base64(email);
        String otpKey = OTP_PREFIX + emailHash;

        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            throw new IllegalStateException("인증코드가 만료되었습니다. 다시 요청해주세요.");
        }
        if (!storedOtp.equals(otp)) {
            throw new IllegalArgumentException("인증코드가 올바르지 않습니다.");
        }

        User user = userRepository.findByEmailHash(emailHash)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        redisTemplate.delete(otpKey);
        log.info("[PasswordReset] 비밀번호 재설정 완료 - userId={}", user.getId());
    }

    private String generateOtp() {
        int code = new SecureRandom().nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private void sendEmail(String to, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("[Assetory] 비밀번호 재설정 인증코드");
        message.setText(
                "안녕하세요, Assetory입니다.\n\n" +
                "비밀번호 재설정 인증코드: " + otp + "\n\n" +
                "이 코드는 5분간 유효합니다.\n" +
                "본인이 요청하지 않으셨다면 이 이메일을 무시해주세요."
        );
        mailSender.send(message);
    }
}
