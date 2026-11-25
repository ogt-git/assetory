package com.project.assetory.market.ecos;

import com.project.assetory.market.exception.EcosApiException;
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
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;

import static com.project.assetory.market.ecos.EcosTestSupport.BASE_URL;
import static com.project.assetory.market.ecos.EcosTestSupport.TEST_KEY;
import static com.project.assetory.market.ecos.EcosTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// ECOS StatisticSearch HTTP 호출 — MockRestServiceServer(B2 DART 테스트와 같은 방식). 실호출 없음. 키는 URL 경로에 들어간다
@ExtendWith(OutputCaptureExtension.class)
class EcosApiClientTest {

    private static final String SEARCH = BASE_URL + "/StatisticSearch/";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private MockRestServiceServer server;
    private SimpleMeterRegistry registry;
    private EcosApiClient client;

    @BeforeEach
    void setUp() {
        client = client(TEST_KEY);
    }

    private EcosApiClient client(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        registry = new SimpleMeterRegistry();
        return new EcosApiClient(builder.build(), EcosTestSupport.properties(apiKey), new EcosMetrics(registry));
    }

    @Test
    @DisplayName("1. 요청 경로: 키 · json · kr · 1~100 · 통계표 · 주기 D · 시작 · 종료 · 항목코드")
    void requestPath() {
        server.expect(requestTo(SEARCH + TEST_KEY + "/json/kr/1/100/731Y001/D/20260921/20261105/0000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("usd-krw-success.json"), MediaType.APPLICATION_JSON));

        assertThat(client.search("731Y001", "0000001", FROM, TO)).hasSize(4);
        server.verify();
    }

    @Test
    @DisplayName("2. INFO-200 → 0건 정상 · 성공으로 기록")
    void noData() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(fixture("info-200.json"), MediaType.APPLICATION_JSON));

        assertThat(client.search("722Y001", "0101000", FROM, TO)).isEmpty();
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("2-b. INFO-100(키 오류) → 1회만 부르고 KEY 실패 (인증 실패 재시도 금지)")
    void invalidKey_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess(fixture("info-100.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.search("722Y001", "0101000", FROM, TO))
                .isInstanceOfSatisfying(EcosApiException.class, e -> assertThat(e.getErrorType()).isEqualTo(EcosErrorType.KEY));
        server.verify();
    }

    @Test
    @DisplayName("2-c. ERROR-602(과다 호출) · HTTP 5xx · 타임아웃 → 실패(다음 실행 때 재시도 대상)")
    void otherFailures() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(fixture("error-602.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withException(new SocketTimeoutException("Read timed out")));

        for (EcosErrorType expected : List.of(EcosErrorType.RATE_LIMIT, EcosErrorType.SERVER_ERROR, EcosErrorType.NETWORK_ERROR)) {
            assertThatThrownBy(() -> client.search("731Y001", "0000001", FROM, TO))
                    .isInstanceOfSatisfying(EcosApiException.class, e -> {
                        assertThat(e.getErrorType()).isEqualTo(expected);
                        assertThat(e.getErrorType().isRetryable()).isTrue();
                    });
        }
        assertThat(count("fail")).isEqualTo(3);
    }

    @Test
    @DisplayName("메트릭 external.api.calls · external.api.latency — provider=ecos, api=statistic-search, 태그는 provider · api · result만")
    void metricsTags() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(fixture("base-rate-success.json"), MediaType.APPLICATION_JSON));

        client.search("722Y001", "0101000", FROM, TO);

        assertThat(registry.get("external.api.latency").tags("provider", "ecos", "api", "statistic-search").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(m -> m.getId().getTags()).extracting(Tag::getKey)
                .containsOnly("provider", "api", "result");
    }

    @Test
    @DisplayName("4. 키 유출 없음 — 실패 예외 메시지 · cause 체인 · 로그 · 메트릭 · 설정 toString 어디에도 키가 없다")
    void noKeyLeak(CapturedOutput output) {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(fixture("info-100.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("<html>오류</html>"));

        List<Throwable> failures = List.of(
                catchThrowable(() -> client.search("731Y001", "0000001", FROM, TO)),
                catchThrowable(() -> client.search("731Y001", "0000001", FROM, TO)),
                catchThrowable(() -> client.search("731Y001", "0000001", FROM, TO)));

        for (Throwable t : failures) {
            assertThat(t).isInstanceOf(EcosApiException.class);
            for (Throwable c = t; c != null; c = c.getCause()) {
                assertThat(c.toString()).doesNotContain(TEST_KEY);
            }
        }
        assertThat(output.getAll()).doesNotContain(TEST_KEY);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().toString()).doesNotContain(TEST_KEY);
        }
        assertThat(EcosTestSupport.properties(TEST_KEY).toString()).doesNotContain(TEST_KEY);
    }

    @Test
    @DisplayName("5. 키가 비면 HTTP를 부르지 않고 CONFIG_MISSING")
    void emptyKey_failsWithoutCall() {
        EcosApiClient noKey = client(" ");

        assertThatThrownBy(() -> noKey.search("731Y001", "0000001", FROM, TO))
                .isInstanceOfSatisfying(EcosApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(EcosErrorType.CONFIG_MISSING));
        server.verify();
    }

    private double count(String result) {
        var counter = registry.find("external.api.calls").tags("provider", "ecos", "api", "statistic-search", "result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
