package com.project.assetory.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// _enc 컬럼용 — 엔티티 필드에 @Convert(converter = AesEncryptConverter.class)로 지정한다
@Component
@Converter
@RequiredArgsConstructor
public class AesEncryptConverter implements AttributeConverter<String, String> {

    private final AesEncryptionUtil aesEncryptionUtil;

    @Override
    public String convertToDatabaseColumn(String plainText) {
        if (plainText == null) return null;
        return aesEncryptionUtil.encrypt(plainText);
    }

    @Override
    public String convertToEntityAttribute(String cipherText) {
        if (cipherText == null) return null;
        return aesEncryptionUtil.decrypt(cipherText);
    }
}
