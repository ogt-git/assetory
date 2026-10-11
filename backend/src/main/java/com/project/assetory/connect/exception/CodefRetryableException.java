package com.project.assetory.connect.exception;

// 배치 재시도 전용 — TRANSIENT_ERROR 계열 오류 발생 시 throw
public class CodefRetryableException extends RuntimeException {

    private final String errorCode;

    public CodefRetryableException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
