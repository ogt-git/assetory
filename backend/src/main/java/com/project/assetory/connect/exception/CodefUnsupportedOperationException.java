package com.project.assetory.connect.exception;

// 상품/로그인 방식 조합 미지원 (CF-12030, CF-12040, CF-12050)
// connect 단계 사전 차단 또는 sync 중 발생 — 재시도 없이 사용자에게 재연동 안내
public class CodefUnsupportedOperationException extends RuntimeException {

    private final String codefCode;

    public CodefUnsupportedOperationException(String message, String codefCode) {
        super(message);
        this.codefCode = codefCode;
    }

    public String getCodefCode() {
        return codefCode;
    }
}
