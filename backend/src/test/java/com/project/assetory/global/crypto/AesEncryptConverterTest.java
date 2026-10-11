package com.project.assetory.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesEncryptConverterTest {

    private final AesEncryptConverter converter =
            new AesEncryptConverter(new AesEncryptionUtil(AesEncryptionUtilTest.testKey(1)));

    @Test
    @DisplayName("DB 저장 값은 평문과 다르고, 읽으면 원본으로 복원")
    void roundtrip() {
        String plain = "test-connected-id-12345";

        String column = converter.convertToDatabaseColumn(plain);

        assertThat(column).isNotEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(plain);
    }

    @Test
    @DisplayName("null 입력 → null 반환 (NPE 없음)")
    void nullInput_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
