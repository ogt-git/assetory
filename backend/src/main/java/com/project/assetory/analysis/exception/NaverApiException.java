package com.project.assetory.analysis.exception;

import com.project.assetory.analysis.naver.NaverErrorType;

/**
 * 네이버 API HUB 호출 실패. 응답 원문 메시지 · Client ID · Secret은 담지 않는다 — 유형 · HTTP 상태 · errorCode만 (DartApiException과 같은 규칙)
 */
public class NaverApiException extends RuntimeException {

    private final NaverErrorType errorType;
    private final Integer httpStatus;
    private final String errorCode;

    public NaverApiException(NaverErrorType errorType, Integer httpStatus, String errorCode) {
        super("NAVER " + errorType + (httpStatus == null ? "" : " http=" + httpStatus)
                + (errorCode == null ? "" : " code=" + errorCode));
        this.errorType = errorType;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public NaverErrorType getErrorType() {
        return errorType;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
