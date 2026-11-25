package com.project.assetory.market.ecos;

import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.exception.EcosApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static com.project.assetory.market.ecos.EcosTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// StatisticSearch 응답 해석 — TIME → 날짜(원본 그대로), DATA_VALUE → 값. 결과 코드 분류 (B4 확정 사항 9)
class EcosResponseParserTest {

    @Test
    @DisplayName("정상 — TIME · DATA_VALUE를 응답 순서 그대로 읽는다(정렬 · 계산은 IndicatorSeries)")
    void success() {
        List<DatedValue> values = EcosResponseParser.parse(fixture("usd-krw-success.json"), "0000001");

        assertThat(values).extracting(DatedValue::date).containsExactly(
                LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 5));
        assertThat(values.get(1).value()).isEqualByComparingTo("1380.5");
    }

    @Test
    @DisplayName("INFO-200(데이터 없음) → 0건 정상")
    void noData() {
        assertThat(EcosResponseParser.parse(fixture("info-200.json"), "0000001")).isEmpty();
    }

    @ParameterizedTest(name = "{0} → {1}, 재시도 {2}")
    @CsvSource({
            "INFO-100, KEY, false",
            "ERROR-100, BAD_REQUEST, false",
            "ERROR-101, BAD_REQUEST, false",
            "ERROR-200, BAD_REQUEST, false",
            "ERROR-300, BAD_REQUEST, false",
            "ERROR-301, BAD_REQUEST, false",
            "ERROR-602, RATE_LIMIT, true",
            "ERROR-400, SERVER_ERROR, true",
            "ERROR-500, SERVER_ERROR, true",
            "ERROR-600, SERVER_ERROR, true",
            "ERROR-601, SERVER_ERROR, true",
            "ERROR-999, UNKNOWN_CODE, true"
    })
    @DisplayName("결과 코드 — 키 · 요청 오류는 재시도 없음, 과다 호출 · 서버 · 모르는 코드는 다음 실행 때 재시도 대상, 원문 메시지는 버린다")
    void resultCodes(String code, EcosErrorType expected, boolean retryable) {
        String body = "{\"RESULT\":{\"CODE\":\"" + code + "\",\"MESSAGE\":\"테스트 전용 메시지\"}}";

        assertThatThrownBy(() -> EcosResponseParser.parse(body, "0000001"))
                .isInstanceOfSatisfying(EcosApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(expected);
                    assertThat(e.getErrorType().isRetryable()).isEqualTo(retryable);
                    assertThat(e.getEcosCode()).isEqualTo(code);
                    assertThat(e.getMessage()).doesNotContain("테스트 전용 메시지");
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "JSON 아님 | <html>점검 중</html>",
            "row 없음 | {\"StatisticSearch\":{\"list_total_count\":0}}",
            "RESULT · StatisticSearch 둘 다 없음 | {\"other\":1}",
            "TIME 형식 | {\"StatisticSearch\":{\"row\":[{\"ITEM_CODE1\":\"0000001\",\"TIME\":\"2026-11-05\",\"DATA_VALUE\":\"1400\"}]}}",
            "TIME 없음 | {\"StatisticSearch\":{\"row\":[{\"ITEM_CODE1\":\"0000001\",\"DATA_VALUE\":\"1400\"}]}}",
            "DATA_VALUE 숫자 아님 | {\"StatisticSearch\":{\"row\":[{\"ITEM_CODE1\":\"0000001\",\"TIME\":\"20261105\",\"DATA_VALUE\":\"-\"}]}}",
            "DATA_VALUE 빈 값 | {\"StatisticSearch\":{\"row\":[{\"ITEM_CODE1\":\"0000001\",\"TIME\":\"20261105\",\"DATA_VALUE\":\"\"}]}}",
            "다른 항목코드 | {\"StatisticSearch\":{\"row\":[{\"ITEM_CODE1\":\"0000002\",\"TIME\":\"20261105\",\"DATA_VALUE\":\"1400\"}]}}"
    })
    @DisplayName("해석할 수 없는 응답 → INVALID_RESPONSE (0 · 직전 값으로 채우지 않는다)")
    void invalid(String label, String body) {
        assertThatThrownBy(() -> EcosResponseParser.parse(body, "0000001"))
                .isInstanceOfSatisfying(EcosApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(EcosErrorType.INVALID_RESPONSE));
    }
}
