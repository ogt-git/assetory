package com.project.assetory.connect.codef;

public enum CodefErrorType {
    /** 인증 실패 — updateAccount 호출로 복구 시도 가능 */
    AUTH_ERROR,
    /** 계정 잠금 등 — updateAccount로도 복구 불가, 연동 비활성화 */
    AUTH_UNRECOVERABLE,
    /** 일시적 오류 — 배치 재시도 대상 */
    TRANSIENT_ERROR,
    /** 요청 한도 초과 — 즉시 건너뜀 */
    RATE_LIMIT_ERROR,
    /** 영구 오류 — 연동 비활성화 */
    PERMANENT_ERROR,
    /** 금융기관 사이트 변경/점검 — 재시도·비활성화 없이 스킵 */
    INSTITUTION_UNAVAILABLE,
    /** 중복 로그인·부하 제한 — 즉시 재시도 금지, 연동 유지, 해당 회차 스킵 */
    COOLDOWN,
    /** 조회 결과 없음 — 빈 결과 정상 반환 (에러 아님) */
    EMPTY_RESULT,
    /** 상품/로그인 방식 조합 미지원 — 재시도 없이 연동 비활성화, 사용자에게 재연동 안내 */
    UNSUPPORTED_OPERATION,
    /** 메뉴 조회 권한 없음 — 계정 권한/설정 문제, 재시도 없이 연동 비활성화 */
    OPERATION_PERMISSION_DENIED,
    /** 미분류 오류 — 연동 비활성화 */
    UNKNOWN
}

