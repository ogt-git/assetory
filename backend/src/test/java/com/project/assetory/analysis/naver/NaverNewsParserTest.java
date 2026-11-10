package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.exception.NaverApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static com.project.assetory.analysis.naver.NaverFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 뉴스 검색 응답 해석 — 값은 API가 준 그대로 읽고, pubDate만 KST 시각으로 바꾼다. 픽스처는 문서 기준(실응답 미대조)
class NaverNewsParserTest {

    @Test
    @DisplayName("정상 — total · start · display와 기사 필드를 원문 그대로 읽는다(<b> · &quot; 유지)")
    void success() {
        NaverNewsSearchResult result = NaverNewsParser.parse(fixture("news-success.json"));

        assertThat(result.total()).isEqualTo(1234);
        assertThat(result.start()).isEqualTo(1);
        assertThat(result.display()).isEqualTo(4);
        assertThat(result.items()).hasSize(4);
        NaverNewsItem first = result.items().get(0);
        assertThat(first.title()).isEqualTo("<b>테스트전자</b>, 자사주 매입 &quot;발표&quot;");
        assertThat(first.description()).isEqualTo("<b>테스트전자</b>가 5일 자기주식 취득을 결정했다고 &amp; 공시했다.");
        assertThat(first.originallink()).isEqualTo("https://news.example-press.co.kr/article/1001");
        assertThat(first.link()).isEqualTo("https://n.news.naver.com/mnews/article/999/0000001001");
        assertThat(first.publishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 20));
        assertThat(result.items().get(1).originallink()).isEmpty();
    }

    @Test
    @DisplayName("0건 — 오류가 아니라 빈 목록")
    void empty() {
        NaverNewsSearchResult result = NaverNewsParser.parse(fixture("news-empty.json"));

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "JSON 아님 | <html>점검 중</html>",
            "빈 본문 | ' '",
            "items 없음 | {\"total\":1,\"start\":1,\"display\":1}",
            "total 없음 | {\"start\":1,\"display\":0,\"items\":[]}",
            "pubDate 해석 불가 | {\"total\":1,\"start\":1,\"display\":1,\"items\":[{\"title\":\"a\",\"originallink\":\"https://e.com/1\",\"link\":\"https://e.com/1\",\"description\":\"d\",\"pubDate\":\"2026-11-05 16:20\"}]}",
            "pubDate 없음 | {\"total\":1,\"start\":1,\"display\":1,\"items\":[{\"title\":\"a\",\"originallink\":\"https://e.com/1\",\"link\":\"https://e.com/1\",\"description\":\"d\"}]}"
    })
    @DisplayName("해석할 수 없는 응답 · pubDate 해석 불가 → INVALID_RESPONSE (시각을 추측해 채우지 않는다)")
    void invalid(String label, String body) {
        assertThatThrownBy(() -> NaverNewsParser.parse(body))
                .isInstanceOfSatisfying(NaverApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(NaverErrorType.INVALID_RESPONSE));
    }

    @Test
    @DisplayName("오류 본문의 errorCode만 꺼낸다 — 원문 메시지는 버린다 (형식 미확인이라 없으면 null)")
    void errorCode() {
        assertThat(NaverNewsParser.errorCode(fixture("error-unauthorized.json"))).isEqualTo("024");
        assertThat(NaverNewsParser.errorCode(fixture("error-rate-limit.json"))).isEqualTo("012");
        assertThat(NaverNewsParser.errorCode("<html>Unauthorized</html>")).isNull();
        assertThat(NaverNewsParser.errorCode(null)).isNull();
    }

    @ParameterizedTest(name = "HTTP {0} → {1}, 재시도 {2}")
    @CsvSource({
            "401, AUTH, false",
            "403, AUTH, false",
            "400, BAD_REQUEST, false",
            "429, RATE_LIMIT, true",
            "500, SERVER_ERROR, true",
            "503, SERVER_ERROR, true",
            "404, UNKNOWN_STATUS, true"
    })
    @DisplayName("HTTP 상태 분류 — 인증 · 요청 오류는 재시도 없음, 한도 · 서버 · 모르는 상태는 다음 실행 때 재시도 대상")
    void httpStatus(int status, NaverErrorType expected, boolean retryable) {
        assertThat(NaverErrorType.fromHttpStatus(status)).isEqualTo(expected);
        assertThat(expected.isRetryable()).isEqualTo(retryable);
    }
}
