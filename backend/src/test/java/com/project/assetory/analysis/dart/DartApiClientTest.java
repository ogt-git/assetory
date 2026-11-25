package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.project.assetory.analysis.dart.DartFixtures.fixture;
import static com.project.assetory.analysis.dart.DartTestSupport.BASE_URL;
import static com.project.assetory.analysis.dart.DartTestSupport.CORP_CODE_XML;
import static com.project.assetory.analysis.dart.DartTestSupport.TEST_KEY;
import static com.project.assetory.analysis.dart.DartTestSupport.corpCodeZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// DART HTTP 호출 — MockRestServiceServer(KIS 테스트와 같은 방식). 실호출 없음
@ExtendWith(OutputCaptureExtension.class)
class DartApiClientTest {

    private static final String LIST = BASE_URL + "/list.json";
    private static final String CORP_CODE = BASE_URL + "/corpCode.xml";
    private static final LocalDate FROM = LocalDate.of(2026, 11, 4);
    private static final LocalDate TO = LocalDate.of(2026, 11, 6);

    private MockRestServiceServer server;
    private SimpleMeterRegistry registry;
    private DartApiClient client;

    @BeforeEach
    void setUp() {
        client = client(TEST_KEY, 5);
    }

    private DartApiClient client(String apiKey, int maxPages) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        registry = new SimpleMeterRegistry();
        return new DartApiClient(restClient, restClient, DartTestSupport.properties(apiKey, maxPages), new DartMetrics(registry));
    }

    @Test
    @DisplayName("1. 유형별 1회 — pblntf_ty · bgn_de · end_de · corp_code · page_no · page_count를 넣어 부른다")
    void list_requestParams() {
        for (String type : List.of("B", "I", "D")) {
            server.expect(requestTo(startsWith(LIST))).andExpect(method(HttpMethod.GET))
                    .andExpect(queryParam("crtfc_key", TEST_KEY))
                    .andExpect(queryParam("corp_code", "00999901"))
                    .andExpect(queryParam("bgn_de", "20261104"))
                    .andExpect(queryParam("end_de", "20261106"))
                    .andExpect(queryParam("pblntf_ty", type))
                    .andExpect(queryParam("page_no", "1"))
                    .andExpect(queryParam("page_count", "100"))
                    .andRespond(withSuccess(fixture("list-success.json"), MediaType.APPLICATION_JSON));
        }

        for (String type : List.of("B", "I", "D")) {
            assertThat(client.list("00999901", FROM, TO, type)).hasSize(3);
        }
        server.verify();
    }

    @Test
    @DisplayName("2. total_page 2 → 2페이지까지 부르고 합친다")
    void list_pages() {
        server.expect(requestTo(startsWith(LIST))).andExpect(queryParam("page_no", "1"))
                .andRespond(withSuccess(fixture("list-page-1-of-2.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(LIST))).andExpect(queryParam("page_no", "2"))
                .andRespond(withSuccess(fixture("list-page-2-of-2.json"), MediaType.APPLICATION_JSON));

        List<DartDisclosure> result = client.list("00999901", FROM, TO, "B");

        assertThat(result).extracting(DartDisclosure::receiptNo)
                .containsExactly("20261105900011", "20261105900012", "20261106900013");
        server.verify();
    }

    @Test
    @DisplayName("2-b. 페이지 상한(max-pages)을 넘으면 실패 — 일부만 돌려주지 않는다")
    void list_pageLimit() {
        client = client(TEST_KEY, 1);
        server.expect(ExpectedCount.once(), requestTo(startsWith(LIST)))
                .andRespond(withSuccess(fixture("list-page-1-of-2.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.list("00999901", FROM, TO, "B"))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.TOO_MANY_PAGES));
        server.verify();
    }

    @Test
    @DisplayName("013(데이터 없음) → 빈 목록, 성공으로 기록")
    void list_noData() {
        server.expect(requestTo(startsWith(LIST)))
                .andRespond(withSuccess(fixture("list-no-data.json"), MediaType.APPLICATION_JSON));

        assertThat(client.list("00999901", FROM, TO, "B")).isEmpty();
        assertThat(count("list", "success")).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 타임아웃(읽기 시간 초과) → NETWORK_ERROR")
    void list_timeout() {
        server.expect(requestTo(startsWith(LIST))).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.list("00999901", FROM, TO, "B"))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.NETWORK_ERROR));
        assertThat(count("list", "fail")).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 키 오류(010) → 1회만 부르고 실패 (인증 실패 재시도 금지)")
    void list_invalidKey_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(LIST)))
                .andRespond(withSuccess(fixture("error-invalid-key.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.list("00999901", FROM, TO, "B"))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.KEY_OR_ACCESS));
        server.verify();
    }

    @Test
    @DisplayName("5. corpCode ZIP → 상장사만 · 비상장 제외")
    void corpCodes() {
        server.expect(requestTo(startsWith(CORP_CODE))).andExpect(queryParam("crtfc_key", TEST_KEY))
                .andRespond(withSuccess(corpCodeZip(CORP_CODE_XML), MediaType.APPLICATION_OCTET_STREAM));

        assertThat(client.corpCodes()).containsExactlyInAnyOrderEntriesOf(Map.of("999901", "00999901", "999903", "00999903"));
        assertThat(count("corp-code", "success")).isEqualTo(1);
    }

    @Test
    @DisplayName("5-b. corpCode가 ZIP이 아닌 오류 응답 → 실패")
    void corpCodes_error() {
        server.expect(requestTo(startsWith(CORP_CODE)))
                .andRespond(withSuccess(fixture("error-invalid-key.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.corpCodes())
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.KEY_OR_ACCESS));
        assertThat(count("corp-code", "fail")).isEqualTo(1);
    }

    @Test
    @DisplayName("6. 메트릭 external.api.calls · external.api.latency — provider=dart, api=list · corp-code")
    void metricsTags() {
        server.expect(requestTo(startsWith(LIST)))
                .andRespond(withSuccess(fixture("list-success.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(CORP_CODE)))
                .andRespond(withSuccess(corpCodeZip(CORP_CODE_XML), MediaType.APPLICATION_OCTET_STREAM));

        client.list("00999901", FROM, TO, "B");
        client.corpCodes();

        assertThat(registry.get("external.api.calls").tags("provider", "dart", "api", "list", "result", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("external.api.latency").tags("provider", "dart", "api", "corp-code").timer().count()).isEqualTo(1);
        // 태그는 provider · api · result만 — 종목 · 고유번호 · 유형 등 값이 들어가지 않는다
        assertThat(registry.getMeters()).flatExtracting(m -> m.getId().getTags()).extracting(Tag::getKey)
                .containsOnly("provider", "api", "result");
    }

    @Test
    @DisplayName("7. 키 유출 없음 — 실패 예외 메시지 · cause 체인 · 로그 · 메트릭 태그 어디에도 키가 없다")
    void noKeyLeak(CapturedOutput output) {
        server.expect(requestTo(startsWith(LIST))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(startsWith(LIST)))
                .andRespond(withSuccess(fixture("error-invalid-key.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(CORP_CODE))).andRespond(withException(new SocketTimeoutException("Read timed out")));

        Throwable timeout = catchThrowable(() -> client.list("00999901", FROM, TO, "B"));
        Throwable invalidKey = catchThrowable(() -> client.list("00999901", FROM, TO, "I"));
        Throwable corpCode = catchThrowable(() -> client.corpCodes());

        for (Throwable t : List.of(timeout, invalidKey, corpCode)) {
            assertThat(t).isInstanceOf(DartApiException.class);
            for (Throwable c = t; c != null; c = c.getCause()) {
                assertThat(String.valueOf(c.getMessage())).doesNotContain(TEST_KEY).doesNotContain("crtfc_key");
                assertThat(c.toString()).doesNotContain(TEST_KEY);
            }
        }
        assertThat(output.getAll()).doesNotContain(TEST_KEY);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().toString()).doesNotContain(TEST_KEY);
        }
    }

    @Test
    @DisplayName("8. 키가 비어 있으면 HTTP를 부르지 않고 CONFIG_MISSING으로 실패")
    void emptyKey_failsWithoutCall() {
        DartApiClient noKey = client(" ", 5);

        assertThatThrownBy(() -> noKey.list("00999901", FROM, TO, "B"))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.CONFIG_MISSING));
        assertThatThrownBy(noKey::corpCodes).isInstanceOf(DartApiException.class);
        server.verify();
    }

    private double count(String api, String result) {
        var counter = registry.find("external.api.calls").tags("provider", "dart", "api", api, "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.catchThrowable(callable);
    }
}
