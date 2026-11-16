package com.project.assetory.analysis.naver;

/**
 * 네이버 API HUB 뉴스 검색 실패 유형 — 응답 원문 메시지 대신 이 유형과 HTTP 상태 · errorCode만 들고 다닌다 (DartErrorType과 같은 방식).
 * retryable = 다음 실행 때 다시 수집할 만한지. 같은 실행 안에서 즉시 재시도하지는 않는다.
 * 오류 본문 형식은 공식 문서로 확인하지 못해 HTTP 상태로 분류한다(B3 확정 사항 2. B0 실호출에서 본 오류는 start 1001 요청의 HTTP 400 · errorCode SE03뿐 — 401 · 429 본문은 미대조)
 */
public enum NaverErrorType {
    /** 401 · 403 — 인증 실패는 재시도 금지 (AGENTS 9장) */
    AUTH(false),
    /** 400 — 잘못된 요청(쿼리 · 파라미터). 같은 요청은 다시 해도 같다 */
    BAD_REQUEST(false),
    /** 429 — 키당 초당 호출 한도 초과 */
    RATE_LIMIT(true),
    /** 5xx */
    SERVER_ERROR(true),
    /** 그 밖의 HTTP 상태 */
    UNKNOWN_STATUS(true),
    /** 본문 형식 오류 · 필수 값 누락 · pubDate 해석 불가 (시각을 추측해 채우지 않음) */
    INVALID_RESPONSE(true),
    /** 연결 실패 · 타임아웃 */
    NETWORK_ERROR(true),
    /** Client ID · Secret 미설정 */
    CONFIG_MISSING(false);

    private final boolean retryable;

    NaverErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 실패 HTTP 상태 → 유형. 2xx는 실패가 아니므로 호출 전에 걸러야 한다 */
    public static NaverErrorType fromHttpStatus(int status) {
        if (status == 401 || status == 403) return AUTH;
        if (status == 400) return BAD_REQUEST;
        if (status == 429) return RATE_LIMIT;
        if (status >= 500 && status < 600) return SERVER_ERROR;
        return UNKNOWN_STATUS;
    }
}
