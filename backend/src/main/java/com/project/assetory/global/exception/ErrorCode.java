package com.project.assetory.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증
    AUTH_FAILED(401, "인증에 실패했습니다"),
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다"),

    // CODEF
    CODEF_SYNC_FAILED(500, "CODEF 동기화에 실패했습니다"),
    CODEF_AUTH_FAILED(401, "CODEF 인증에 실패했습니다"),
    CODEF_ALREADY_CONNECTED(409, "이미 연동된 금융기관입니다."),
    CODEF_CONNECT_IN_PROGRESS(409, "해당 금융기관 연동이 진행 중입니다."),

    // 요청 제한
    TOO_MANY_REQUESTS(429, "5분 후 다시 시도해주세요"),

    // 리소스
    CONNECTION_NOT_FOUND(404, "연동 정보를 찾을 수 없습니다"),
    RESOURCE_NOT_FOUND(404, "요청한 리소스를 찾을 수 없습니다"),
    MANUAL_ASSET_NOT_FOUND(404, "수동 자산 정보를 찾을 수 없습니다"),

    // 권한
    ACCESS_DENIED(403, "접근 권한이 없습니다"),

    // 서버
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다");

    private final int status;
    private final String message;
}
