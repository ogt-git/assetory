package com.project.assetory.connect.exception;

// CODEF API 자체 오류 코드 반환 시 사용 (네트워크 오류와 구분)
public class CodefApiException extends RuntimeException {

    private final String codefCode;

    public CodefApiException(String codefCode, String message) {
        super(message);
        this.codefCode = codefCode;
    }

    public String getCodefCode() {
        return codefCode;
    }
}
