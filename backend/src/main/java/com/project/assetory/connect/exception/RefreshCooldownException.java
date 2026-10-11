package com.project.assetory.connect.exception;

import com.project.assetory.global.exception.TooManyRequestsException;
import lombok.Getter;

// 수동 새로고침 쿨다운 중 — 429와 함께 다시 요청할 수 있을 때까지 남은 초를 돌려준다
@Getter
public class RefreshCooldownException extends TooManyRequestsException {

    private final long retryAfterSeconds;

    public RefreshCooldownException(long retryAfterSeconds) {
        super("새로고침은 5분에 한 번만 가능합니다. " + retryAfterSeconds + "초 후 다시 시도해주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
