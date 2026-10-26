package com.project.assetory.market.exception;

import com.project.assetory.market.kis.KisErrorType;

// 한투 호출 실패. 응답 원문 메시지 · 토큰 · 앱키는 담지 않는다 (NFR-SE-07) — 유형과 한투 코드만
public class KisApiException extends RuntimeException {

    private final KisErrorType errorType;
    private final String kisCode;

    public KisApiException(KisErrorType errorType, String kisCode) {
        super("KIS " + errorType + (kisCode == null ? "" : " code=" + kisCode));
        this.errorType = errorType;
        this.kisCode = kisCode;
    }

    public KisApiException(KisErrorType errorType, String kisCode, Throwable cause) {
        this(errorType, kisCode);
        initCause(cause);
    }

    public KisErrorType getErrorType() {
        return errorType;
    }

    public String getKisCode() {
        return kisCode;
    }
}
