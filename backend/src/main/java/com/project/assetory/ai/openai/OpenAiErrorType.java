package com.project.assetory.ai.openai;

/**
 * OpenAI 호출 실패 유형 (Q-5). retryable = 상위(요약 실패 → FAILED → 재시도 스케줄러)가 다시 시도해도 되는지 —
 * 호출 안에서는 어떤 유형도 재시도하지 않는다(DART · 네이버와 같은 정책). 로그 레벨: retryable이면 WARN, 아니면 ERROR
 */
public enum OpenAiErrorType {
    /** 401 · 403 — 인증 실패는 재시도 금지 (AGENTS 9장) */
    AUTH(false),
    /** 400 — 잘못된 요청(모델 이름 · 파라미터). 같은 요청은 다시 해도 같다 */
    BAD_REQUEST(false),
    /** 429 — 요청 속도 초과 */
    RATE_LIMIT(true),
    /** 429 + error.code=insufficient_quota — 크레딧 · 쿼터 소진. 다시 해도 같아 재시도 금지(재시도 루프 방지) */
    QUOTA_EXCEEDED(false),
    /** 5xx */
    SERVER_ERROR(true),
    /** 그 밖의 HTTP 상태 */
    UNKNOWN_STATUS(true),
    /** 연결 실패 · 타임아웃 */
    NETWORK_ERROR(true),
    /** 본문 형식 오류 · 내용 없음 · 거절(refusal) · finish_reason이 stop이 아님 */
    INVALID_RESPONSE(true),
    /** finish_reason=length — 출력 상한에 걸려 잘림. 잘린 문장은 쓰지 않는다 */
    TRUNCATED(true),
    /** 키 · 모델 미설정 — 설정 전에는 다시 해도 같다 */
    CONFIG_MISSING(false);

    /** 실호출 확인 전 가정값 — OpenAI 오류 본문 error.code (AI-7에서 대조) */
    static final String CODE_INSUFFICIENT_QUOTA = "insufficient_quota";

    private final boolean retryable;

    OpenAiErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 실패 HTTP 상태(+ 본문 error.code) → 유형. 2xx는 실패가 아니므로 호출 전에 걸러야 한다 */
    public static OpenAiErrorType fromHttpStatus(int status, String errorCode) {
        if (status == 401 || status == 403) return AUTH;
        if (status == 400) return BAD_REQUEST;
        if (status == 429) return CODE_INSUFFICIENT_QUOTA.equals(errorCode) ? QUOTA_EXCEEDED : RATE_LIMIT;
        if (status >= 500 && status < 600) return SERVER_ERROR;
        return UNKNOWN_STATUS;
    }
}
