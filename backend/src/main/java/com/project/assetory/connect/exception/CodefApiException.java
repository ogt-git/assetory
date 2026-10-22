package com.project.assetory.connect.exception;

import com.project.assetory.connect.codef.CodefErrorClassifier;
import com.project.assetory.connect.codef.CodefErrorType;

// CODEF 응답이 실패 코드일 때. 코드로 오류 유형을 분류해 함께 들고 다닌다 (CODEF 원문 메시지는 담지 않는다 — 계좌번호 등이 섞일 수 있음)
public class CodefApiException extends RuntimeException {

    private final String codefCode;
    private final CodefErrorType errorType;

    public CodefApiException(String codefCode, String message) {
        super(message);
        this.codefCode = codefCode;
        this.errorType = CodefErrorClassifier.classify(codefCode);
    }

    public String getCodefCode() {
        return codefCode;
    }

    public CodefErrorType getErrorType() {
        return errorType;
    }
}
