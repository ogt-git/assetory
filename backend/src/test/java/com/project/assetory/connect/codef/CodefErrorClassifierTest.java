package com.project.assetory.connect.codef;

import com.project.assetory.connect.exception.CodefApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// FR-CN-06: CODEF 응답 코드 → 오류 유형 → 재시도 여부 / connected_accounts.status 전이
class CodefErrorClassifierTest {

    @ParameterizedTest(name = "{0} → {1} (재시도 {2}, 상태 {3})")
    @DisplayName("코드 → 유형 → 재시도 여부 → 상태 전이")
    @CsvSource({
            // 인증 실패 → AUTH_FAILED, 재시도 금지
            "CF-04008, AUTH_ERROR,                  false, AUTH_FAILED",
            "CF-04010, AUTH_ERROR,                  false, AUTH_FAILED",
            "CF-04019, AUTH_ERROR,                  false, AUTH_FAILED",
            "CF-04038, AUTH_ERROR,                  false, AUTH_FAILED",
            "CF-12899, AUTH_ERROR,                  false, AUTH_FAILED",
            "CF-12800, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-12801, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-12802, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-12803, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-12806, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-12834, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-13031, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            "CF-13032, AUTH_UNRECOVERABLE,          false, AUTH_FAILED",
            // 인증 만료 → EXPIRED
            "CF-12833, AUTH_EXPIRED,                false, EXPIRED",
            // 네트워크·타임아웃·CODEF 일시 장애만 재시도
            "CF-09980, NETWORK_ERROR,               true,  KEEP",
            "CF-09999, TRANSIENT_ERROR,             true,  KEEP",
            "CF-01002, TRANSIENT_ERROR,             true,  KEEP",
            "CF-01004, TRANSIENT_ERROR,             true,  KEEP",
            "CF-01007, TRANSIENT_ERROR,             true,  KEEP",
            "CF-00016, TRANSIENT_ERROR,             true,  KEEP",
            "CF-12003, TRANSIENT_ERROR,             true,  KEEP",
            "CF-12104, TRANSIENT_ERROR,             true,  KEEP",
            "CF-12703, TRANSIENT_ERROR,             true,  KEEP",
            // 그 외 — 재시도 안 함, 상태 유지
            "CF-00012, RATE_LIMIT_ERROR,            false, KEEP",
            "CF-00022, RATE_LIMIT_ERROR,            false, KEEP",
            "CF-00023, RATE_LIMIT_ERROR,            false, KEEP",
            "CF-12201, COOLDOWN,                    false, KEEP",
            "CF-01006, COOLDOWN,                    false, KEEP",
            "CF-12207, COOLDOWN,                    false, KEEP",
            "CF-12106, COOLDOWN,                    false, KEEP",
            "CF-04000, PERMANENT_ERROR,             false, KEEP",
            "CF-04015, PERMANENT_ERROR,             false, KEEP",
            "CF-13010, PERMANENT_ERROR,             false, KEEP",
            "CF-13013, PERMANENT_ERROR,             false, KEEP",
            "CF-12701, INSTITUTION_UNAVAILABLE,     false, KEEP",
            "CF-12710, INSTITUTION_UNAVAILABLE,     false, KEEP",
            "CF-12041, INSTITUTION_UNAVAILABLE,     false, KEEP",
            "CF-12030, UNSUPPORTED_OPERATION,       false, KEEP",
            "CF-12040, UNSUPPORTED_OPERATION,       false, KEEP",
            "CF-12050, UNSUPPORTED_OPERATION,       false, KEEP",
            "CF-12049, OPERATION_PERMISSION_DENIED, false, KEEP",
            "CF-03999, EMPTY_RESULT,                false, KEEP",
            "CF-12109, EMPTY_RESULT,                false, KEEP",
            "CF-12111, EMPTY_RESULT,                false, KEEP",
            "CF-13025, EMPTY_RESULT,                false, KEEP",
            "CF-13105, EMPTY_RESULT,                false, KEEP",
            "CF-99999, UNKNOWN,                     false, KEEP"
    })
    void mapping(String code, CodefErrorType type, boolean retryable, String status) {
        CodefErrorType classified = CodefErrorClassifier.classify(code);

        assertThat(classified).isEqualTo(type);
        assertThat(classified.isRetryable()).isEqualTo(retryable);
        assertThat(classified.statusTransition().map(Enum::name).orElse("KEEP")).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CodefErrorType.class)
    @DisplayName("모든 유형에 사용자 메시지가 있고, CODEF 코드·영문이 섞이지 않는다")
    void everyTypeHasUserMessage(CodefErrorType type) {
        assertThat(type.getUserMessage()).isNotBlank().doesNotContain("CF-");
    }

    @Test
    @DisplayName("재시도 가능 유형은 네트워크·일시 장애 두 가지뿐 (인증 실패는 절대 재시도 안 함)")
    void onlyNetworkAndTransientAreRetryable() {
        Set<CodefErrorType> retryable = Arrays.stream(CodefErrorType.values())
                .filter(CodefErrorType::isRetryable).collect(Collectors.toSet());

        assertThat(retryable).containsExactlyInAnyOrder(CodefErrorType.NETWORK_ERROR, CodefErrorType.TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("null 코드 → UNKNOWN, CodefApiException은 생성 시 유형을 분류해 둔다")
    void nullAndException() {
        assertThat(CodefErrorClassifier.classify(null)).isEqualTo(CodefErrorType.UNKNOWN);
        assertThat(new CodefApiException("CF-12800", "비밀번호 오류").getErrorType())
                .isEqualTo(CodefErrorType.AUTH_UNRECOVERABLE);
    }
}
