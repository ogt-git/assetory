package com.project.assetory.global.exception;

// 429 Too Many Requests 처리를 위한 커스텀 예외 추가
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException() {
        super(ErrorCode.TOO_MANY_REQUESTS.getMessage());
    }

    public TooManyRequestsException(String message) {
        super(message);
    }
}
