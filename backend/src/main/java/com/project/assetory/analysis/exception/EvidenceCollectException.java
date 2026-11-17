package com.project.assetory.analysis.exception;

import com.project.assetory.analysis.domain.SourceType;

/**
 * 근거 수집기의 외부 오류. 사용자 응답으로 나가지 않는다(수집 흐름이 잡아 수집기 실패로 기록).
 * 메시지에는 소스 유형과 짧은 사유만 — 키 · 요청 URL · 응답 원문 금지 (AGENTS 9장)
 *
 * retryable: 다시 시도하면 될 수 있는 실패인지 (Q-6 D2 — 공시 수집기가 retryable로 실패하면 분석을 PENDING으로 두고 재시도한다).
 * 키 오류 · 키 없음 · 잘못된 요청처럼 다시 해도 같은 실패는 false. 원인을 모르면 true(일시 오류일 수 있음)
 */
public class EvidenceCollectException extends RuntimeException {

    private final SourceType sourceType;
    private final boolean retryable;

    public EvidenceCollectException(SourceType sourceType, String reason) {
        this(sourceType, reason, null, true);
    }

    public EvidenceCollectException(SourceType sourceType, String reason, Throwable cause) {
        this(sourceType, reason, cause, true);
    }

    public EvidenceCollectException(SourceType sourceType, String reason, Throwable cause, boolean retryable) {
        super(sourceType + " 근거 수집 실패: " + reason, cause);
        this.sourceType = sourceType;
        this.retryable = retryable;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
