package com.project.assetory.auth.service;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Refresh Token 저장소 (Redis). 토큰 원문은 저장하지 않고 SHA-256 해시만 저장한다.
 *
 * <pre>
 * auth:rt:token:{sha256(RT)} → "{userId}"          사용 가능한 RT
 *                            → "rotated:{userId}"  이미 회전된 RT — 다시 들어오면 재사용으로 판단
 *                            TTL = RT 만료 (회전 표식도 원래 만료까지 유지)
 * auth:rt:user:{userId}      → Set{sha256(RT)}     사용자 단위 일괄 무효화용 인덱스, TTL = RT 만료 (추가할 때마다 갱신)
 * </pre>
 *
 * 검사와 변경은 Lua 스크립트로 원자적으로 처리한다. 스크립트 안에서 인덱스 멤버로 키를 조합하므로 Redis Cluster는 고려하지 않는다(단일 노드).
 */
@Service
@RequiredArgsConstructor
public class RedisTokenService {

    static final String TOKEN_PREFIX = "auth:rt:token:";
    static final String USER_INDEX_PREFIX = "auth:rt:user:";

    public enum RotationResult { ROTATED, REUSED, INVALID }

    // KEYS: token, index / ARGV: userId, ttlMs, hash
    private static final RedisScript<Long> SAVE_SCRIPT = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    // KEYS: oldToken, newToken, index / ARGV: userId, ttlMs, newHash
    // 반환: 1 회전 성공, 2 회전된 토큰 재사용, 0 없는 토큰·다른 사용자 토큰
    private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of("""
            local v = redis.call('GET', KEYS[1])
            if not v then return 0 end
            if v == 'rotated:' .. ARGV[1] then return 2 end
            if v ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], 'rotated:' .. ARGV[1], 'KEEPTTL')
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[3], ARGV[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[2])
            return 1
            """, Long.class);

    // KEYS: token, index / ARGV: userId, hash — 본인 소유의 사용 가능한 토큰만 삭제
    private static final RedisScript<Long> REVOKE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1])
                redis.call('SREM', KEYS[2], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    // KEYS: index / ARGV: tokenPrefix — 인덱스에 있는 토큰 키를 모두 지운 뒤 인덱스 삭제
    private static final RedisScript<Long> REVOKE_ALL_SCRIPT = RedisScript.of("""
            local members = redis.call('SMEMBERS', KEYS[1])
            for _, h in ipairs(members) do
                redis.call('DEL', ARGV[1] .. h)
            end
            redis.call('DEL', KEYS[1])
            return #members
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;

    public void saveRefreshToken(Long userId, String token) {
        String hash = HashUtil.sha256Hex(token);
        redisTemplate.execute(SAVE_SCRIPT,
                List.of(tokenKey(hash), indexKey(userId)),
                String.valueOf(userId), ttlMs(), hash);
    }

    public RotationResult rotate(Long userId, String oldToken, String newToken) {
        String newHash = HashUtil.sha256Hex(newToken);
        Long result = redisTemplate.execute(ROTATE_SCRIPT,
                List.of(tokenKey(HashUtil.sha256Hex(oldToken)), tokenKey(newHash), indexKey(userId)),
                String.valueOf(userId), ttlMs(), newHash);
        if (result == null) return RotationResult.INVALID;
        return switch (result.intValue()) {
            case 1 -> RotationResult.ROTATED;
            case 2 -> RotationResult.REUSED;
            default -> RotationResult.INVALID;
        };
    }

    // 로그아웃 — 현재 RT 하나만 무효화
    public void revoke(Long userId, String token) {
        String hash = HashUtil.sha256Hex(token);
        redisTemplate.execute(REVOKE_SCRIPT,
                List.of(tokenKey(hash), indexKey(userId)),
                String.valueOf(userId), hash);
    }

    // 사용자 단위 일괄 무효화 — 재사용 탐지, 회원 탈퇴(FR-AU-06), 비밀번호 재설정에서 사용
    public void revokeAll(Long userId) {
        redisTemplate.execute(REVOKE_ALL_SCRIPT, List.of(indexKey(userId)), TOKEN_PREFIX);
    }

    private String ttlMs() {
        return String.valueOf(jwtUtil.getRefreshExpirationMs());
    }

    private static String tokenKey(String hash) {
        return TOKEN_PREFIX + hash;
    }

    private static String indexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }
}
