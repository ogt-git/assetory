package com.project.assetory.market.exception;

import com.project.assetory.market.ecos.EcosErrorType;

// ECOS 호출 실패. 응답 원문 메시지 · 키 · 요청 URL(키가 경로에 들어간다)은 담지 않는다 — 유형과 결과 코드만 (KisApiException과 같은 규칙)
public class EcosApiException extends RuntimeException {

    private final EcosErrorType errorType;
    private final String ecosCode;

    public EcosApiException(EcosErrorType errorType, String ecosCode) {
        super("ECOS " + errorType + (ecosCode == null ? "" : " code=" + ecosCode));
        this.errorType = errorType;
        this.ecosCode = ecosCode;
    }

    public EcosErrorType getErrorType() {
        return errorType;
    }

    public String getEcosCode() {
        return ecosCode;
    }
}
