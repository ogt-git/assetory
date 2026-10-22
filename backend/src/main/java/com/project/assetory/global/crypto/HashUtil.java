package com.project.assetory.global.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class HashUtil {

    private HashUtil() {}

    // 이메일 조회용 SHA-256 단방향 해시 (Base64) — AES는 매번 다른 암호문이 나와 WHERE 절에 쓸 수 없음
    public static String sha256Base64(String plainText) {
        return Base64.getEncoder().encodeToString(sha256(plainText));
    }

    // 토큰 해시 저장용 SHA-256 (소문자 hex 64자)
    public static String sha256Hex(String plainText) {
        return HexFormat.of().formatHex(sha256(plainText));
    }

    private static byte[] sha256(String plainText) {
        Objects.requireNonNull(plainText, "plainText");
        try {
            return MessageDigest.getInstance("SHA-256").digest(plainText.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("해시 생성 실패", e);
        }
    }
}
