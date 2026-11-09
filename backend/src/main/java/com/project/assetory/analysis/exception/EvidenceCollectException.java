package com.project.assetory.analysis.exception;

import com.project.assetory.analysis.domain.SourceType;

/**
 * 근거 수집기의 외부 오류. 사용자 응답으로 나가지 않는다(수집 흐름이 잡아 수집기 실패로 기록).
 * 메시지에는 소스 유형과 짧은 사유만 — 키 · 요청 URL · 응답 원문 금지 (AGENTS 9장)
 */
public class EvidenceCollectException extends RuntimeException {

    private final SourceType sourceType;

    public EvidenceCollectException(SourceType sourceType, String reason) {
        super(sourceType + " 근거 수집 실패: " + reason);
        this.sourceType = sourceType;
    }

    public EvidenceCollectException(SourceType sourceType, String reason, Throwable cause) {
        super(sourceType + " 근거 수집 실패: " + reason, cause);
        this.sourceType = sourceType;
    }

    public SourceType getSourceType() {
        return sourceType;
    }
}
