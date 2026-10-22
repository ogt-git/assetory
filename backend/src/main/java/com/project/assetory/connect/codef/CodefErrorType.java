package com.project.assetory.connect.codef;

import com.project.assetory.connect.domain.ConnectedAccountStatus;
import lombok.Getter;

import java.util.Optional;

/**
 * CODEF 오류 유형 (FR-CN-06). sync_jobs.error_type에 이 이름 그대로 저장한다.
 * - userMessage: 사용자에게 보여줄 문구 (CODEF 원문 메시지는 노출하지 않음)
 * - retryable: 네트워크·타임아웃·CODEF 일시 장애만 true. 인증 실패는 재시도하면 기관 계정이 잠길 수 있어 false
 * - statusOnError: connected_accounts.status 전이. null이면 현재 상태 유지
 */
@Getter
public enum CodefErrorType {

    /** 인증 실패 — 동기화 단계에서 updateAccount로 복구를 시도할 수 있음 */
    AUTH_ERROR("금융기관 인증에 실패했습니다. 계정 정보를 확인하고 다시 연결해주세요.",
            false, ConnectedAccountStatus.AUTH_FAILED),
    /** 아이디·비밀번호 오류, 계정 잠김 — updateAccount로도 복구 불가 */
    AUTH_UNRECOVERABLE("금융기관 아이디·비밀번호가 맞지 않거나 계정이 잠겼습니다. 금융기관에서 확인한 뒤 다시 연결해주세요.",
            false, ConnectedAccountStatus.AUTH_FAILED),
    /** 금융기관 비밀번호 만료 */
    AUTH_EXPIRED("금융기관 비밀번호가 만료되었습니다. 금융기관에서 비밀번호를 변경한 뒤 다시 연결해주세요.",
            false, ConnectedAccountStatus.EXPIRED),
    /** 통신 실패·타임아웃 (easycodef가 CF-09980으로 돌려줌) */
    NETWORK_ERROR("금융기관과 연결이 원활하지 않습니다. 잠시 후 다시 시도해주세요.",
            true, null),
    /** CODEF 일시 장애 */
    TRANSIENT_ERROR("일시적인 오류로 조회하지 못했습니다. 잠시 후 다시 시도해주세요.",
            true, null),
    /** 요청 한도 초과 — 바로 재시도하면 더 막힘 */
    RATE_LIMIT_ERROR("요청이 많아 지금은 조회할 수 없습니다. 잠시 후 다시 시도해주세요.",
            false, null),
    /** 해지·존재하지 않는 계좌 등 */
    PERMANENT_ERROR("해지되었거나 존재하지 않는 계정입니다. 연동 정보를 확인해주세요.",
            false, null),
    /** 금융기관 사이트 변경·점검 */
    INSTITUTION_UNAVAILABLE("현재 해당 금융기관 시스템 점검·개편으로 조회가 불가능합니다. 금융사의 조치가 완료되면 순차적으로 반영될 예정입니다.",
            false, null),
    /** 다른 기기 동시 로그인·부하 제한 — 즉시 재시도 금지 */
    COOLDOWN("다른 곳에서 금융기관에 접속 중이거나 접속이 일시 제한되었습니다. 잠시 후 다시 시도해주세요.",
            false, null),
    /** 조회 결과 없음 — 오류가 아닌 빈 결과 */
    EMPTY_RESULT("조회된 내역이 없습니다.",
            false, null),
    /** 상품·로그인 방식 조합 미지원 */
    UNSUPPORTED_OPERATION("해당 금융기관은 현재 로그인 방식으로 조회를 지원하지 않습니다. 다른 로그인 방식으로 다시 연결해주세요.",
            false, null),
    /** 메뉴 조회 권한 없음 — 계정 권한·설정 문제 */
    OPERATION_PERMISSION_DENIED("해당 계정으로 조회할 권한이 없습니다. 금융기관 설정을 확인해주세요.",
            false, null),
    /** 미분류 */
    UNKNOWN("금융기관 조회 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
            false, null);

    private final String userMessage;
    private final boolean retryable;
    private final ConnectedAccountStatus statusOnError;

    CodefErrorType(String userMessage, boolean retryable, ConnectedAccountStatus statusOnError) {
        this.userMessage = userMessage;
        this.retryable = retryable;
        this.statusOnError = statusOnError;
    }

    // 상태 전이가 필요하면 바뀔 상태, 유지면 빈 값
    public Optional<ConnectedAccountStatus> statusTransition() {
        return Optional.ofNullable(statusOnError);
    }
}
