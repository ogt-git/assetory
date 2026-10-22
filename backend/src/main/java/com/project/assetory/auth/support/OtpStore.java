package com.project.assetory.auth.support;

import com.project.assetory.global.crypto.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 이메일 인증코드(OTP) 저장소. 코드는 SHA-256 해시로 저장하고, 검증은 Lua로 원자적으로 처리한다.
 * - 일치하면 코드·시도 횟수를 함께 지운다 (1회용)
 * - 불일치가 MAX_ATTEMPTS번 쌓이면 코드를 폐기한다
 * 키 이름은 호출하는 서비스가 정한다 (auth:email-verify:*, auth:pw-reset:*).
 */
@Component
@RequiredArgsConstructor
public class OtpStore {

    public static final int MAX_ATTEMPTS = 5;

    public enum VerifyResult { VERIFIED, MISMATCH, EXPIRED, LOCKED }

    // KEYS: code, attempt / ARGV: codeHash, ttlMs
    private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    // KEYS: code, attempt / ARGV: codeHash, ttlMs, maxAttempts
    // 반환: 1 일치(삭제됨), 0 불일치, -1 코드 없음(만료·미발급·이미 사용), -2 시도 초과(코드 폐기)
    private static final RedisScript<Long> VERIFY_SCRIPT = RedisScript.of("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return -1 end
            if stored == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end
            local n = redis.call('INCR', KEYS[2])
            if n == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[2]) end
            if n >= tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1], KEYS[2])
                return -2
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    // 쿨다운 획득 (SET NX PX) — 이미 있으면 false
    public boolean tryAcquireCooldown(String cooldownKey, Duration cooldown) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", cooldown));
    }

    public void issue(String codeKey, String attemptKey, String code, Duration ttl) {
        redisTemplate.execute(ISSUE_SCRIPT, List.of(codeKey, attemptKey),
                HashUtil.sha256Hex(code), String.valueOf(ttl.toMillis()));
    }

    public VerifyResult verify(String codeKey, String attemptKey, String code, Duration ttl) {
        Long result = redisTemplate.execute(VERIFY_SCRIPT, List.of(codeKey, attemptKey),
                HashUtil.sha256Hex(code), String.valueOf(ttl.toMillis()), String.valueOf(MAX_ATTEMPTS));
        if (result == null) return VerifyResult.EXPIRED;
        return switch (result.intValue()) {
            case 1 -> VerifyResult.VERIFIED;
            case 0 -> VerifyResult.MISMATCH;
            case -2 -> VerifyResult.LOCKED;
            default -> VerifyResult.EXPIRED;
        };
    }

    // 발송 실패 시 정리 — 쿨다운·코드를 지워 바로 다시 요청할 수 있게
    public void clear(String... keys) {
        redisTemplate.delete(List.of(keys));
    }
}
