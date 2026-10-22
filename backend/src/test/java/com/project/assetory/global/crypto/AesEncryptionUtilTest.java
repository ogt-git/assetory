package com.project.assetory.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesEncryptionUtilTest {

    static String testKey(int seed) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + seed);
        return Base64.getEncoder().encodeToString(key);
    }

    private final AesEncryptionUtil util = new AesEncryptionUtil(testKey(1));

    @Test
    @DisplayName("암호화 후 복호화 → 원본 문자열 복원")
    void encrypt_decrypt_roundtrip() {
        for (String plain : new String[]{"test-connected-id-12345", "계좌번호 123-4567-8901", ""}) {
            assertThat(util.decrypt(util.encrypt(plain))).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("동일 평문도 매 호출마다 다른 암호문 생성 (랜덤 IV)")
    void sameInput_producesDifferentCiphertext() {
        String plain = "same-plain-text";

        String first = util.encrypt(plain);
        String second = util.encrypt(plain);

        assertThat(first).isNotEqualTo(second).isNotEqualTo(plain);
        assertThat(util.decrypt(first)).isEqualTo(util.decrypt(second));
    }

    @Test
    @DisplayName("암호문 앞 12바이트는 IV, 전체 길이는 IV + 평문 + 태그 16바이트")
    void storedFormat_ivPrefixed() {
        byte[] combined = Base64.getDecoder().decode(util.encrypt("abc"));

        assertThat(combined).hasSize(12 + 3 + 16);
    }

    @Test
    @DisplayName("암호문 본문을 변조하면 복호화 실패")
    void tamperedCiphertext_fails() {
        byte[] combined = Base64.getDecoder().decode(util.encrypt("account-number-001"));
        combined[14] ^= 0x01;

        String tampered = Base64.getEncoder().encodeToString(combined);

        assertThatThrownBy(() -> util.decrypt(tampered)).isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("IV를 변조해도 복호화 실패")
    void tamperedIv_fails() {
        byte[] combined = Base64.getDecoder().decode(util.encrypt("account-number-001"));
        combined[0] ^= 0x01;

        String tampered = Base64.getEncoder().encodeToString(combined);

        assertThatThrownBy(() -> util.decrypt(tampered)).isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("다른 키로 암호화한 값은 복호화 실패")
    void differentKey_fails() {
        String encrypted = new AesEncryptionUtil(testKey(2)).encrypt("plain");

        assertThatThrownBy(() -> util.decrypt(encrypted)).isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("길이가 모자라거나 Base64가 아닌 값은 복호화 실패")
    void malformedCiphertext_fails() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[20]);

        assertThatThrownBy(() -> util.decrypt(tooShort)).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> util.decrypt("not-base64!!")).isInstanceOf(CryptoException.class);
    }
}
