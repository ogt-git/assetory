package com.project.assetory.analysis.exception;

import com.project.assetory.analysis.dart.DartErrorType;

/**
 * DART 호출 실패. 응답 원문 메시지 · 키 · 요청 URL(키가 쿼리에 들어간다)은 담지 않는다 — 유형과 DART status만 (KisApiException과 같은 규칙)
 */
public class DartApiException extends RuntimeException {

    private final DartErrorType errorType;
    private final String dartCode;

    public DartApiException(DartErrorType errorType, String dartCode) {
        super("DART " + errorType + (dartCode == null ? "" : " status=" + dartCode));
        this.errorType = errorType;
        this.dartCode = dartCode;
    }

    public DartErrorType getErrorType() {
        return errorType;
    }

    public String getDartCode() {
        return dartCode;
    }
}
