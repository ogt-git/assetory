package com.project.assetory.market.kis;

// 한투 호출 실패 유형 — 원문 메시지 대신 이 유형과 한투 코드(msg_cd)만 들고 다닌다
public enum KisErrorType {
    CONFIG_MISSING,      // 앱키 · 시크릿 미설정
    TOKEN_ISSUE_FAILED,  // 토큰 발급 실패 (1분 1회 제한 등) — 재시도하지 않음
    TOKEN_EXPIRED,       // 재발급 후에도 토큰 오류
    RATE_LIMIT,          // EGW00201 — 설정 횟수만큼 재시도 후에도 초과
    API_ERROR,           // rt_cd 실패
    INVALID_RESPONSE,    // 본문 형식 오류 · 필수 값 누락 · 숫자 해석 불가 (0으로 채우지 않음)
    NETWORK_ERROR        // 연결 실패 · 타임아웃
}
