package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static com.project.assetory.analysis.dart.DartFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// list.json 응답 해석 — 상태 코드 분류(B2 확정 사항 8)와 필드 읽기. 픽스처는 공식 개발가이드 기준(B0 실호출로 필드 대조 완료(2026-11-16))
class DartListParserTest {

    @Test
    @DisplayName("정상(000) — 페이지 정보와 공시 행을 원문 그대로 읽는다 (정정 공시 · 비고 포함)")
    void success() {
        DartListPage page = DartListParser.parse(fixture("list-success.json"));

        assertThat(page.pageNo()).isEqualTo(1);
        assertThat(page.totalPage()).isEqualTo(1);
        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        DartDisclosure first = page.items().get(0);
        assertThat(first.corpCode()).isEqualTo("00999901");
        assertThat(first.stockCode()).isEqualTo("999901");
        assertThat(first.reportName()).isEqualTo("  주요사항보고서(자기주식취득결정)  ");
        assertThat(first.receiptNo()).isEqualTo("20261105900001");
        assertThat(first.receiptDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(page.items().get(1).reportName()).startsWith("[기재정정]");
        assertThat(page.items().get(1).remark()).isEqualTo("정");
    }

    @Test
    @DisplayName("데이터 없음(013) — 오류가 아니라 0건 정상")
    void noData_emptyPage() {
        DartListPage page = DartListParser.parse(fixture("list-no-data.json"));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalPage()).isZero();
    }

    @Test
    @DisplayName("여러 페이지 응답의 total_page · page_no를 읽는다")
    void multiPage() {
        DartListPage first = DartListParser.parse(fixture("list-page-1-of-2.json"));
        DartListPage second = DartListParser.parse(fixture("list-page-2-of-2.json"));

        assertThat(first.totalPage()).isEqualTo(2);
        assertThat(first.items()).hasSize(2);
        assertThat(second.pageNo()).isEqualTo(2);
        assertThat(second.items()).hasSize(1);
    }

    @ParameterizedTest(name = "status {0} → {1}, 재시도 {2}")
    @CsvSource({
            "010, KEY_OR_ACCESS, false",
            "011, KEY_OR_ACCESS, false",
            "012, KEY_OR_ACCESS, false",
            "901, KEY_OR_ACCESS, false",
            "100, BAD_REQUEST, false",
            "101, BAD_REQUEST, false",
            "021, BAD_REQUEST, false",
            "014, BAD_REQUEST, false",
            "020, RATE_LIMIT, true",
            "800, SERVER_ERROR, true",
            "900, SERVER_ERROR, true",
            "999, UNKNOWN_STATUS, true"
    })
    @DisplayName("실패 상태 코드 — 키 · 접근 · 요청 오류는 재시도 없음, 한도 · 서버 · 모르는 코드는 다음 실행 때 재시도 대상")
    void failureStatus(String status, DartErrorType expected, boolean retryable) {
        String body = "{\"status\":\"" + status + "\",\"message\":\"테스트 전용 메시지\"}";

        assertThatThrownBy(() -> DartListParser.parse(body))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(expected);
                    assertThat(e.getErrorType().isRetryable()).isEqualTo(retryable);
                    assertThat(e.getDartCode()).isEqualTo(status);
                    // 응답 원문 메시지를 예외에 담지 않는다 (KIS와 같은 규칙 — 유형과 코드만)
                    assertThat(e.getMessage()).doesNotContain("테스트 전용 메시지");
                });
    }

    @Test
    @DisplayName("픽스처의 010 · 020도 같은 분류")
    void errorFixtures() {
        assertThatThrownBy(() -> DartListParser.parse(fixture("error-invalid-key.json")))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.KEY_OR_ACCESS));
        assertThatThrownBy(() -> DartListParser.parse(fixture("error-rate-limit.json")))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.RATE_LIMIT));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "JSON 아님 | <html>점검 중</html>",
            "status 없음 | {\"message\":\"x\"}",
            "list 없음 | {\"status\":\"000\",\"page_no\":1,\"total_page\":1,\"total_count\":0}",
            "rcept_no 누락 | {\"status\":\"000\",\"page_no\":1,\"total_page\":1,\"total_count\":1,\"list\":[{\"report_nm\":\"a\",\"rcept_dt\":\"20261105\"}]}",
            "rcept_no 형식 | {\"status\":\"000\",\"page_no\":1,\"total_page\":1,\"total_count\":1,\"list\":[{\"report_nm\":\"a\",\"rcept_no\":\"2026\",\"rcept_dt\":\"20261105\"}]}",
            "report_nm 빈 값 | {\"status\":\"000\",\"page_no\":1,\"total_page\":1,\"total_count\":1,\"list\":[{\"report_nm\":\" \",\"rcept_no\":\"20261105900001\",\"rcept_dt\":\"20261105\"}]}",
            "rcept_dt 형식 | {\"status\":\"000\",\"page_no\":1,\"total_page\":1,\"total_count\":1,\"list\":[{\"report_nm\":\"a\",\"rcept_no\":\"20261105900001\",\"rcept_dt\":\"2026-11-05\"}]}"
    })
    @DisplayName("해석할 수 없는 응답은 INVALID_RESPONSE(재시도 대상) — 빈 값을 지어내 채우지 않는다")
    void invalidResponse(String label, String body) {
        assertThatThrownBy(() -> DartListParser.parse(body))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.INVALID_RESPONSE));
    }
}
