package com.project.assetory.global.crypto;

// 암·복호화 실패 — IllegalStateException(400 응답)과 섞이지 않게 별도 타입으로 두어 500으로 처리되게 한다
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
