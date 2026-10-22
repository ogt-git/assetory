package com.project.assetory.connect.codef;

import java.util.Set;

public final class CodefErrorClassifier {

    private static final Set<String> AUTH_ERROR_CODES = Set.of(
            "CF-04008", "CF-04010", "CF-04019", "CF-04038",
            "CF-12899"
    );

    private static final Set<String> AUTH_UNRECOVERABLE_CODES = Set.of(
            "CF-12802", "CF-12806",
            "CF-12801", "CF-12800", "CF-12803",  // 금융사 비밀번호/아이디 오류 — updateAccount로 복구 불가
            "CF-12834",                           // 계정 잠김
            "CF-13031", "CF-13032"                // 증권 계좌 비밀번호 오류/잠금
    );

    // 금융기관 비밀번호 만료 — 상태를 EXPIRED로 구분하기 위해 AUTH_UNRECOVERABLE에서 분리
    private static final Set<String> AUTH_EXPIRED_CODES = Set.of(
            "CF-12833"
    );

    // easycodef 라이브러리가 통신 실패·타임아웃(IOException 등)을 예외 대신 이 코드로 돌려준다
    private static final Set<String> NETWORK_ERROR_CODES = Set.of(
            "CF-09980"
    );

    // 중복 로그인·부하 제한 — 즉시 재시도 금지, 연동 유지
    private static final Set<String> COOLDOWN_CODES = Set.of(
            "CF-12201",  // 다른 기기 동시 로그인
            "CF-01006",  // 중복 로그인 방지 제한
            "CF-12207",  // 반복 로그인으로 금융기관 측 일시 제한
            "CF-12106"   // 과도한 시스템 부하 방지 제한
    );

    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
            "CF-01002", "CF-01004", "CF-01007", "CF-00016",
            "CF-12003", "CF-12104", "CF-12703",
            "CF-09999"  // CODEF 서버 처리 오류 (HTTP 5xx) — easycodef가 이 코드로 돌려줌
    );

    private static final Set<String> INSTITUTION_UNAVAILABLE_CODES = Set.of(
            "CF-12701", "CF-12710",
            "CF-12041"  // 증권사 시스템 점검 시간 (새벽 배치 충돌 가능) — CODEF 정의 재확인 전 유지
    );

    // 상품/엔진/로그인 방식 조합 미지원 — 재시도해도 해결 불가, 사전 차단이 불가능한 케이스 방어
    private static final Set<String> UNSUPPORTED_OPERATION_CODES = Set.of(
            "CF-12030",  // 현재 엔진 버전에서 미지원
            "CF-12040",  // 현재 모듈 미지원
            "CF-12050"   // 대상기관에서 제공하지 않는 업무
    );

    // 메뉴 조회 권한 없음 — 로그인 방식 문제가 아닌 계정 권한/설정 문제
    private static final Set<String> OPERATION_PERMISSION_DENIED_CODES = Set.of(
            "CF-12049"
    );

    private static final Set<String> RATE_LIMIT_CODES = Set.of(
            "CF-00012", "CF-00022", "CF-00023"
    );

    private static final Set<String> PERMANENT_ERROR_CODES = Set.of(
            "CF-04000", "CF-04015",
            "CF-13010", "CF-13013"  // 증권 계좌 해지/존재하지 않는 계좌
    );

    // 조회 결과 없음 — 에러가 아닌 정상 빈 결과
    private static final Set<String> EMPTY_RESULT_CODES = Set.of(
            "CF-03999", "CF-12109", "CF-12111", "CF-13025", "CF-13105"
    );

    private CodefErrorClassifier() {}

    public static CodefErrorType classify(String errorCode) {
        if (errorCode == null) return CodefErrorType.UNKNOWN;
        if (EMPTY_RESULT_CODES.contains(errorCode))              return CodefErrorType.EMPTY_RESULT;
        if (AUTH_ERROR_CODES.contains(errorCode))                return CodefErrorType.AUTH_ERROR;
        if (AUTH_EXPIRED_CODES.contains(errorCode))              return CodefErrorType.AUTH_EXPIRED;
        if (AUTH_UNRECOVERABLE_CODES.contains(errorCode))        return CodefErrorType.AUTH_UNRECOVERABLE;
        if (COOLDOWN_CODES.contains(errorCode))                  return CodefErrorType.COOLDOWN;
        if (NETWORK_ERROR_CODES.contains(errorCode))             return CodefErrorType.NETWORK_ERROR;
        if (TRANSIENT_ERROR_CODES.contains(errorCode))           return CodefErrorType.TRANSIENT_ERROR;
        if (RATE_LIMIT_CODES.contains(errorCode))                return CodefErrorType.RATE_LIMIT_ERROR;
        if (PERMANENT_ERROR_CODES.contains(errorCode))            return CodefErrorType.PERMANENT_ERROR;
        if (INSTITUTION_UNAVAILABLE_CODES.contains(errorCode))    return CodefErrorType.INSTITUTION_UNAVAILABLE;
        if (UNSUPPORTED_OPERATION_CODES.contains(errorCode))      return CodefErrorType.UNSUPPORTED_OPERATION;
        if (OPERATION_PERMISSION_DENIED_CODES.contains(errorCode)) return CodefErrorType.OPERATION_PERMISSION_DENIED;
        return CodefErrorType.UNKNOWN;
    }
}
