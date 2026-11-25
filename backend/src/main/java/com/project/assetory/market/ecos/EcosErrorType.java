package com.project.assetory.market.ecos;

/**
 * ECOS 호출 실패 유형 — 응답 원문 메시지 대신 이 유형과 결과 코드만 들고 다닌다 (DartErrorType과 같은 방식).
 * retryable = 다음 실행 때 다시 수집할 만한지. 같은 실행 안에서 즉시 재시도하지는 않는다.
 * 코드표: ECOS 개발 명세서 "에러 및 정보배치" (B0 Phase 1 확인, Phase 3 실호출로 대조 완료 2026-11-16)
 */
public enum EcosErrorType {
    /** INFO-100 인증키 무효 — 인증 실패는 재시도 금지 (AGENTS 9장) */
    KEY(false),
    /** ERROR-100 필수 값 누락 · 101 주기와 다른 날짜 형식 · 200 파일타입 · 300 · 301 조회건수 — 같은 요청은 다시 해도 같다 */
    BAD_REQUEST(false),
    /** ERROR-602 과도한 호출로 이용 제한 */
    RATE_LIMIT(true),
    /** ERROR-400 검색범위 초과 60초 TIMEOUT · 500 서버 · 600 DB 연결 · 601 SQL, HTTP 5xx */
    SERVER_ERROR(true),
    /** 코드표에 없는 결과 코드 · 그 밖의 HTTP 상태 */
    UNKNOWN_CODE(true),
    /** 본문 형식 오류 · TIME · DATA_VALUE 해석 불가 · 다른 항목코드 (0 · 직전 값으로 채우지 않음) */
    INVALID_RESPONSE(true),
    /** 연결 실패 · 타임아웃 */
    NETWORK_ERROR(true),
    /** 키 미설정 */
    CONFIG_MISSING(false);

    private final boolean retryable;

    EcosErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 실패 결과 코드 → 유형. INFO-200(데이터 없음)은 실패가 아니므로 호출 전에 걸러야 한다 */
    public static EcosErrorType fromCode(String code) {
        if (code == null) return INVALID_RESPONSE;
        return switch (code) {
            case "INFO-100" -> KEY;
            case "ERROR-100", "ERROR-101", "ERROR-200", "ERROR-300", "ERROR-301" -> BAD_REQUEST;
            case "ERROR-602" -> RATE_LIMIT;
            case "ERROR-400", "ERROR-500", "ERROR-600", "ERROR-601" -> SERVER_ERROR;
            default -> UNKNOWN_CODE;
        };
    }
}
