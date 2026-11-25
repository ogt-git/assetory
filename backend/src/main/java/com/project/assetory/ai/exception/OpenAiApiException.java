package com.project.assetory.ai.exception;

import com.project.assetory.ai.openai.OpenAiErrorType;

/**
 * OpenAI 호출 실패. 응답 원문 · 프롬프트 · 키는 담지 않는다 — 유형 · HTTP 상태 · error.code만 (NaverApiException과 같은 규칙)
 */
public class OpenAiApiException extends RuntimeException {

    private final OpenAiErrorType errorType;
    private final Integer httpStatus;
    private final String errorCode;

    public OpenAiApiException(OpenAiErrorType errorType, Integer httpStatus, String errorCode) {
        super("OPENAI " + errorType + (httpStatus == null ? "" : " http=" + httpStatus)
                + (errorCode == null ? "" : " code=" + errorCode));
        this.errorType = errorType;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public OpenAiErrorType getErrorType() {
        return errorType;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
