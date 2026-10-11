package com.project.assetory.global.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

// AES-256-GCM. 저장 형식: Base64(IV 12바이트 + 암호문 + 인증 태그 16바이트)
@Component
public class AesEncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptionUtil(@Value("${assetory.crypto.aes-key:}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    // 키가 없거나 형식이 틀리면 빈 생성 단계에서 실패시켜 애플리케이션이 뜨지 않게 한다 (메시지에 키 값은 넣지 않음)
    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("ASSETORY_AES_KEY가 설정되지 않았습니다.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ASSETORY_AES_KEY는 Base64 형식이어야 합니다.");
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "ASSETORY_AES_KEY는 32바이트(AES-256)여야 합니다. 현재 " + raw.length + "바이트");
        }
        return raw;
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV + 암호문(태그 포함)을 합쳐서 Base64 저장
            byte[] combined = new byte[IV_LENGTH_BYTES + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH_BYTES, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new CryptoException("암호화 실패", e);
        }
    }

    // 암호문이 변조됐거나 다른 키로 암호화된 경우 태그 검증에서 실패한다
    public String decrypt(String cipherText) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
                throw new CryptoException("복호화 실패: 암호문 길이가 올바르지 않습니다.");
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH_BYTES));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);

            return new String(plain, StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("복호화 실패", e);
        }
    }
}
