package com.project.assetory.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @Test
    @DisplayName("계좌번호 정상 길이 → 앞 3 + ******* + 뒤 4")
    void accountNumber_normal() {
        assertThat(MaskingUtil.maskAccountNumber("12345678901")).isEqualTo("123*******8901");
        assertThat(MaskingUtil.maskAccountNumber("1234567")).isEqualTo("123*******4567");
    }

    @Test
    @DisplayName("계좌번호 하이픈·공백 포함 → 제거 후 마스킹")
    void accountNumber_withHyphen() {
        assertThat(MaskingUtil.maskAccountNumber("123-4567-8901")).isEqualTo("123*******8901");
        assertThat(MaskingUtil.maskAccountNumber("123 45 678901")).isEqualTo("123*******8901");
    }

    @Test
    @DisplayName("계좌번호 7자리 미만 → 전체 마스킹 (원문 노출 없음)")
    void accountNumber_short() {
        assertThat(MaskingUtil.maskAccountNumber("123456")).isEqualTo("******");
        assertThat(MaskingUtil.maskAccountNumber("12-34")).isEqualTo("****");
        assertThat(MaskingUtil.maskAccountNumber("")).isEmpty();
    }

    @Test
    @DisplayName("계좌번호 null → null")
    void accountNumber_null() {
        assertThat(MaskingUtil.maskAccountNumber(null)).isNull();
    }

    @Test
    @DisplayName("이메일 → 로컬파트 앞 2자리만 노출")
    void email() {
        assertThat(MaskingUtil.maskEmail("user1234@example.com")).isEqualTo("us***@example.com");
        assertThat(MaskingUtil.maskEmail("ab@example.com")).isEqualTo("ab***@example.com");
        assertThat(MaskingUtil.maskEmail(null)).isNull();
        assertThat(MaskingUtil.maskEmail("no-at-sign")).isEqualTo("no-at-sign");
    }
}
