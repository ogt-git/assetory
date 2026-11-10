package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.exception.NaverApiException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.project.assetory.analysis.naver.NaverFixtures.fixture;
import static com.project.assetory.analysis.naver.NaverTestSupport.BASE_URL;
import static com.project.assetory.analysis.naver.NaverTestSupport.TEST_CLIENT_ID;
import static com.project.assetory.analysis.naver.NaverTestSupport.TEST_CLIENT_SECRET;
import static com.project.assetory.analysis.naver.NaverTestSupport.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 네이버 API HUB 뉴스 검색 HTTP 호출 — MockRestServiceServer(B2 DART 테스트와 같은 방식). 실호출 없음
@ExtendWith(OutputCaptureExtension.class)
class NaverNewsApiClientTest {

    private static final String NEWS = BASE_URL + "/search/v1/news";
    private static final LocalDateTime LATEST = LocalDateTime.of(2026, 11, 7, 12, 0);

    private MockRestServiceServer server;
    private SimpleMeterRegistry registry;
    private NaverNewsApiClient client;

    @BeforeEach
    void setUp() {
        client = client(TEST_CLIENT_ID, TEST_CLIENT_SECRET, 3);
    }

    private NaverNewsApiClient client(String id, String secret, int maxPages) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        registry = new SimpleMeterRegistry();
        return new NaverNewsApiClient(builder.build(), NaverTestSupport.properties(id, secret, maxPages), new NaverMetrics(registry));
    }

    @Test
    @DisplayName("1. 헤더 2개 · display=100 · sort=date · start · 인코딩된 query(한글 · 공백 · &)")
    void search_request() {
        server.expect(requestTo(startsWith(NEWS))).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", TEST_CLIENT_ID))
                .andExpect(header("X-NCP-APIGW-API-KEY", TEST_CLIENT_SECRET))
                .andExpect(query("query", "테스트 AT&T"))
                .andExpect(query("display", "100"))
                .andExpect(query("start", "101"))
                .andExpect(query("sort", "date"))
                .andRespond(withSuccess(fixture("news-success.json"), MediaType.APPLICATION_JSON));

        NaverNewsSearchResult result = client.search("테스트 AT&T", 101);

        assertThat(result.items()).hasSize(4);
        server.verify();
    }

    @Test
    @DisplayName("2. 최신순 반복: 기간 시작보다 오래된 기사가 나오면 다음 페이지를 부르지 않는다")
    void fetchSince_stopsAtWindowStart() {
        // 1페이지: 11/07 12시부터 100시간 전(11/03 09시)까지 → 기간 시작 11/04보다 오래된 기사 포함 → 멈춤
        server.expect(ExpectedCount.once(), requestTo(startsWith(NEWS))).andExpect(query("start", "1"))
                .andRespond(withSuccess(page(1, 100, LATEST), MediaType.APPLICATION_JSON));

        NaverNewsPages pages = client.fetchSince("테스트전자", LocalDate.of(2026, 11, 4));

        assertThat(pages.items()).hasSize(100);
        assertThat(pages.limitReached()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("2-b. 마지막 페이지(100건 미만)면 더 부르지 않는다")
    void fetchSince_lastPage() {
        server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", "1"))
                .andRespond(withSuccess(page(1, 100, LATEST), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", "101"))
                .andRespond(withSuccess(page(101, 30, LATEST.minusHours(100)), MediaType.APPLICATION_JSON));

        NaverNewsPages pages = client.fetchSince("테스트전자", LocalDate.of(2026, 10, 1));

        assertThat(pages.items()).hasSize(130);
        assertThat(pages.limitReached()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("3. 페이지 상한(max-pages) 도달 → 실패가 아니라 정상, limitReached 표시 · 받은 것까지 반환")
    void fetchSince_maxPages() {
        for (int i = 0; i < 3; i++) {
            int start = 1 + i * 100;
            server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", String.valueOf(start)))
                    .andRespond(withSuccess(page(start, 100, LATEST.minusMinutes(i * 100L)), MediaType.APPLICATION_JSON));
        }

        NaverNewsPages pages = client.fetchSince("테스트전자", LocalDate.of(2026, 10, 1));

        assertThat(pages.items()).hasSize(300);
        assertThat(pages.limitReached()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("3-b. start 1,000 상한 도달도 같다 — start 901까지 10페이지 부른 뒤 멈춤")
    void fetchSince_startLimit() {
        client = client(TEST_CLIENT_ID, TEST_CLIENT_SECRET, 50);
        for (int i = 0; i < 10; i++) {
            int start = 1 + i * 100;
            server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", String.valueOf(start)))
                    .andRespond(withSuccess(page(start, 100, LATEST.minusMinutes(i * 100L)), MediaType.APPLICATION_JSON));
        }

        NaverNewsPages pages = client.fetchSince("테스트전자", LocalDate.of(2026, 10, 1));

        assertThat(pages.items()).hasSize(1000);
        assertThat(pages.limitReached()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("0건 → 빈 목록, 성공으로 기록")
    void fetchSince_empty() {
        server.expect(requestTo(startsWith(NEWS))).andRespond(withSuccess(fixture("news-empty.json"), MediaType.APPLICATION_JSON));

        NaverNewsPages pages = client.fetchSince("테스트전자", LocalDate.of(2026, 11, 4));

        assertThat(pages.items()).isEmpty();
        assertThat(pages.limitReached()).isFalse();
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 401 → 1회만 부르고 AUTH 실패 (인증 실패 재시도 금지)")
    void unauthorized_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(NEWS)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-unauthorized.json")));

        assertThatThrownBy(() -> client.fetchSince("테스트전자", LocalDate.of(2026, 11, 4)))
                .isInstanceOfSatisfying(NaverApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(NaverErrorType.AUTH);
                    assertThat(e.getHttpStatus()).isEqualTo(401);
                    assertThat(e.getErrorCode()).isEqualTo("024");
                    assertThat(e.getMessage()).doesNotContain("테스트 전용 인증 실패 메시지");
                });
        server.verify();
    }

    @Test
    @DisplayName("4-b. 429 · 5xx · 타임아웃 → 실패 (다음 실행 때 재시도 대상)")
    void otherFailures() {
        server.expect(requestTo(startsWith(NEWS))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("error-rate-limit.json")));
        server.expect(requestTo(startsWith(NEWS))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(startsWith(NEWS))).andRespond(withException(new SocketTimeoutException("Read timed out")));

        for (NaverErrorType expected : List.of(NaverErrorType.RATE_LIMIT, NaverErrorType.SERVER_ERROR, NaverErrorType.NETWORK_ERROR)) {
            assertThatThrownBy(() -> client.search("테스트전자", 1))
                    .isInstanceOfSatisfying(NaverApiException.class, e -> {
                        assertThat(e.getErrorType()).isEqualTo(expected);
                        assertThat(e.getErrorType().isRetryable()).isTrue();
                    });
        }
        assertThat(count("fail")).isEqualTo(3);
    }

    @Test
    @DisplayName("5. 2페이지째 실패 → 전체 실패(1페이지 결과를 돌려주지 않는다)")
    void secondPageFails_allFail() {
        server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", "1"))
                .andRespond(withSuccess(page(1, 100, LATEST), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(NEWS))).andExpect(query("start", "101"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetchSince("테스트전자", LocalDate.of(2026, 10, 1)))
                .isInstanceOfSatisfying(NaverApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(NaverErrorType.SERVER_ERROR));
        server.verify();
    }

    @Test
    @DisplayName("6. 메트릭 external.api.calls · external.api.latency — provider=naver, api=news, 태그는 provider · api · result만")
    void metricsTags() {
        server.expect(requestTo(startsWith(NEWS))).andRespond(withSuccess(fixture("news-success.json"), MediaType.APPLICATION_JSON));

        client.search("테스트전자", 1);

        assertThat(registry.get("external.api.calls").tags("provider", "naver", "api", "news", "result", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("external.api.latency").tags("provider", "naver", "api", "news").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(m -> m.getId().getTags()).extracting(Tag::getKey)
                .containsOnly("provider", "api", "result");
    }

    @Test
    @DisplayName("7. 키 유출 없음 — 실패 예외 메시지 · cause 체인 · 로그 · 메트릭 어디에도 Client ID · Secret이 없다")
    void noKeyLeak(CapturedOutput output) {
        server.expect(requestTo(startsWith(NEWS))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(startsWith(NEWS))).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("error-unauthorized.json")));
        server.expect(requestTo(startsWith(NEWS))).andRespond(withSuccess("<html>깨진 응답</html>", MediaType.TEXT_HTML));

        List<Throwable> failures = List.of(
                catchThrowable(() -> client.search("테스트전자", 1)),
                catchThrowable(() -> client.search("테스트전자", 1)),
                catchThrowable(() -> client.search("테스트전자", 1)));

        for (Throwable t : failures) {
            assertThat(t).isInstanceOf(NaverApiException.class);
            for (Throwable c = t; c != null; c = c.getCause()) {
                assertThat(c.toString()).doesNotContain(TEST_CLIENT_ID).doesNotContain(TEST_CLIENT_SECRET);
            }
        }
        assertThat(output.getAll()).doesNotContain(TEST_CLIENT_ID).doesNotContain(TEST_CLIENT_SECRET);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().toString()).doesNotContain(TEST_CLIENT_ID).doesNotContain(TEST_CLIENT_SECRET);
        }
    }

    @Test
    @DisplayName("8. Client ID 또는 Secret이 비면 HTTP를 부르지 않고 CONFIG_MISSING")
    void emptyKey_failsWithoutCall() {
        for (NaverNewsApiClient noKey : List.of(client("", TEST_CLIENT_SECRET, 3), client(TEST_CLIENT_ID, " ", 3))) {
            assertThatThrownBy(() -> noKey.search("테스트전자", 1))
                    .isInstanceOfSatisfying(NaverApiException.class,
                            e -> assertThat(e.getErrorType()).isEqualTo(NaverErrorType.CONFIG_MISSING));
            server.verify();
        }
    }

    /** 쿼리 파라미터를 디코드해 비교 — 한글 · 공백 · & 가 인코딩되어 나갔는지 함께 본다 */
    private static RequestMatcher query(String name, String expected) {
        return request -> {
            var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            assertThat(params.get(name)).as("query param " + name).hasSize(1);
            String raw = params.getFirst(name);
            assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).isEqualTo(expected);
            if (!expected.equals(URLDecoder.decode(expected, StandardCharsets.UTF_8)) || expected.matches(".*[^A-Za-z0-9].*")) {
                assertThat(raw).as("encoded " + name).doesNotContain(" ").doesNotContain("&");
            }
        };
    }

    private double count(String result) {
        var counter = registry.find("external.api.calls").tags("provider", "naver", "api", "news", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
