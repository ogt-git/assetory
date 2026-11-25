package com.project.assetory.analysis.dart;

/**
 * DART 호출 실패 유형 — 응답 원문 메시지 대신 이 유형과 DART status만 들고 다닌다 (KisErrorType과 같은 방식).
 * retryable = 다음 실행 때 다시 수집할 만한지. 같은 실행 안에서 즉시 재시도하지는 않는다.
 * 코드표: OpenDART 개발가이드 메시지 설명 (B0 Phase 1 확인, Phase 3 실호출로 대조 완료 2026-11-16)
 */
public enum DartErrorType {
    /** 010 등록되지 않은 키 · 011 사용할 수 없는 키 · 012 접근할 수 없는 IP · 901 계정 보유기간 만료 — 인증 실패는 재시도 금지 (AGENTS 9장) */
    KEY_OR_ACCESS(false),
    /** 100 부적절한 필드 값 · 101 부적절한 접근 · 021 조회 회사 수 초과 · 014 파일 없음 — 같은 요청은 다시 해도 같다 */
    BAD_REQUEST(false),
    /** 020 요청 제한 초과 */
    RATE_LIMIT(true),
    /** 800 시스템 점검 · 900 정의되지 않은 오류 */
    SERVER_ERROR(true),
    /** 코드표에 없는 status */
    UNKNOWN_STATUS(true),
    /** 본문 형식 오류 · 필수 값 누락 · 날짜 해석 불가 (빈 값을 지어내 채우지 않음) */
    INVALID_RESPONSE(true),
    /** 연결 실패 · 타임아웃 */
    NETWORK_ERROR(true),
    /** 키 미설정 — 키를 넣기 전에는 다시 해도 같다 */
    CONFIG_MISSING(false),
    /** 페이지 상한(assetory.dart.max-pages) 초과 — 일부만 돌려주지 않고 실패 */
    TOO_MANY_PAGES(false);

    private final boolean retryable;

    DartErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 실패 status → 유형. 000(정상) · 013(데이터 없음)은 실패가 아니므로 호출 전에 걸러야 한다 */
    public static DartErrorType fromStatus(String status) {
        if (status == null) return INVALID_RESPONSE;
        return switch (status) {
            case "010", "011", "012", "901" -> KEY_OR_ACCESS;
            case "100", "101", "021", "014" -> BAD_REQUEST;
            case "020" -> RATE_LIMIT;
            case "800", "900" -> SERVER_ERROR;
            default -> UNKNOWN_STATUS;
        };
    }
}
