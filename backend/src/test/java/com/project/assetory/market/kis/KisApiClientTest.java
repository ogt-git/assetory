package com.project.assetory.market.kis;

import com.project.assetory.market.exception.KisApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.project.assetory.market.kis.KisTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 한투 시세 · 종목정보 호출 — 재시도 규칙, 0 치환 금지(5-C), 응답 매핑
class KisApiClientTest {

    private static final String DAILY_PRICE = KisTestSupport.BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-daily-price";
    private static final String TOKEN = KisTestSupport.BASE_URL + "/oauth2/tokenP";

    private MockRestServiceServer server;
    private KisTestSupport.FakeRedis redis;
    private SimpleMeterRegistry meterRegistry;
    private KisApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        redis = new KisTestSupport.FakeRedis();
        redis.values.put(KisTokenManager.TOKEN_KEY, "cached-token");
        meterRegistry = new SimpleMeterRegistry();
        KisMetrics metrics = new KisMetrics(meterRegistry);
        RestClient restClient = builder.build();
        KisProperties properties = KisTestSupport.properties(2);
        client = new KisApiClient(restClient, new KisTokenManager(restClient, redis.template, properties, metrics),
                properties, metrics);
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator kisError(String fixtureName) {
        return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(fixture(fixtureName));
    }

    @Test
    @DisplayName("일자별 시세: KRX(J) 고정 · 원주가로 조회하고 거래일자 · 종가 · 대비 · 부호 · 대비율 · 거래량을 그대로 매핑한다")
    void dailyPrices_mapping() {
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("FID_COND_MRKT_DIV_CODE", "J"))
                .andExpect(queryParam("FID_INPUT_ISCD", "005930"))
                .andExpect(queryParam("FID_PERIOD_DIV_CODE", "D"))
                .andExpect(queryParam("FID_ORG_ADJ_PRC", "0"))
                .andExpect(header("tr_id", "FHKST01010400"))
                .andExpect(header("authorization", "Bearer cached-token"))
                .andExpect(header("appkey", KisTestSupport.APP_KEY))
                .andRespond(withSuccess(fixture("daily-price-success.json"), MediaType.APPLICATION_JSON));

        List<KisDailyPrice> prices = client.getDailyPrices("005930");

        // 빈 채움 행(모든 값이 빈 문자열)은 건너뛴다
        assertThat(prices).hasSize(2);
        KisDailyPrice latest = prices.get(0);
        assertThat(latest.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(latest.closePrice()).isEqualByComparingTo("71200");
        assertThat(latest.change()).isEqualByComparingTo("-800");
        assertThat(latest.changeSign()).isEqualTo("5");
        assertThat(latest.changeRate()).isEqualByComparingTo("-1.11");
        assertThat(latest.volume()).isEqualTo(15_234_567L);
        assertThat(latest.lockCode()).isEqualTo("00");
        assertThat(latest.cumulativeSplitRate()).isEqualByComparingTo("1.00");
        assertThat(prices.get(1).changeRate()).isEqualByComparingTo(new BigDecimal("2.13"));
        assertThat(prices.get(1).lockCode()).isEqualTo("01");
        server.verify();
    }

    @Test
    @DisplayName("부호: prdy_vrss · prdy_ctrt는 부호 포함 원문 — 하락(부호 5)은 음수 그대로, 분할일은 기준가 대비 −2.08%")
    void dailyPrices_signedValues_splitDay() {
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("daily-price-split.json"), MediaType.APPLICATION_JSON));

        KisDailyPrice split = client.getDailyPrices("005930").get(0);

        assertThat(split.changeSign()).isEqualTo("5");
        assertThat(split.change()).isEqualByComparingTo("-1100");
        assertThat(split.changeRate()).isEqualByComparingTo("-2.08");
    }

    @Test
    @DisplayName("필수 숫자(종가)가 비어 있으면 0으로 채우지 않고 실패한다")
    void dailyPrices_missingClose_fails() {
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("daily-price-missing-close.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getDailyPrices("005930"))
                .isInstanceOf(KisApiException.class)
                .extracting(e -> ((KisApiException) e).getErrorType())
                .isEqualTo(KisErrorType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("연결 실패 · 타임아웃 → 재시도 없이 NETWORK_ERROR, 실패 메트릭 1건 (C-06)")
    void networkError_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(DAILY_PRICE)))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("테스트 전용 타임아웃"); });

        assertThatThrownBy(() -> client.getDailyPrices("005930"))
                .isInstanceOf(KisApiException.class)
                .extracting(e -> ((KisApiException) e).getErrorType())
                .isEqualTo(KisErrorType.NETWORK_ERROR);
        server.verify();
        assertThat(meterRegistry.get("external.api.calls")
                .tags("provider", "kis", "api", "daily-price", "result", "fail").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rt_cd가 성공이 아니면 재시도 없이 실패한다")
    void rtCdFailure_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("error-rt-cd.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getDailyPrices("005930"))
                .isInstanceOf(KisApiException.class)
                .satisfies(e -> {
                    KisApiException ex = (KisApiException) e;
                    assertThat(ex.getErrorType()).isEqualTo(KisErrorType.API_ERROR);
                    assertThat(ex.getKisCode()).isEqualTo("OPSQ0002");
                    // 외부 응답 원문 메시지를 담지 않는다
                    assertThat(ex.getMessage()).doesNotContain("테스트 전용 실패 메시지");
                });
        server.verify();
    }

    @Test
    @DisplayName("EGW00201(초당 건수 초과)은 설정 횟수(2)만큼 재시도한 뒤 실패한다")
    void rateLimit_retriesThenFails() {
        server.expect(ExpectedCount.times(3), requestTo(startsWith(DAILY_PRICE)))
                .andRespond(kisError("error-rate-limit.json"));

        assertThatThrownBy(() -> client.getDailyPrices("005930"))
                .isInstanceOf(KisApiException.class)
                .extracting(e -> ((KisApiException) e).getErrorType())
                .isEqualTo(KisErrorType.RATE_LIMIT);
        server.verify();
    }

    @Test
    @DisplayName("EGW00201 뒤 재시도가 성공하면 결과를 돌려준다")
    void rateLimit_thenSuccess() {
        server.expect(requestTo(startsWith(DAILY_PRICE))).andRespond(kisError("error-rate-limit.json"));
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("daily-price-success.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getDailyPrices("005930")).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("토큰 만료 응답 → 1회 재발급 후 재시도해 성공")
    void tokenExpired_reissueOnceThenSuccess() {
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andExpect(header("authorization", "Bearer cached-token"))
                .andRespond(kisError("error-token-expired.json"));
        server.expect(requestTo(TOKEN))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andExpect(header("authorization", "Bearer test-only-kis-token-1"))
                .andRespond(withSuccess(fixture("daily-price-success.json"), MediaType.APPLICATION_JSON));

        assertThat(client.getDailyPrices("005930")).hasSize(2);
        server.verify();
        assertThat(redis.values).containsEntry(KisTokenManager.TOKEN_KEY, "test-only-kis-token-1");
    }

    @Test
    @DisplayName("재발급 뒤에도 토큰 만료 응답이면 더 재발급하지 않고 실패한다")
    void tokenExpired_twice_fails() {
        server.expect(requestTo(startsWith(DAILY_PRICE))).andRespond(kisError("error-token-expired.json"));
        server.expect(ExpectedCount.once(), requestTo(TOKEN))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(DAILY_PRICE))).andRespond(kisError("error-token-expired.json"));

        assertThatThrownBy(() -> client.getDailyPrices("005930"))
                .isInstanceOf(KisApiException.class)
                .extracting(e -> ((KisApiException) e).getErrorType())
                .isEqualTo(KisErrorType.TOKEN_EXPIRED);
        server.verify();
    }

    @Test
    @DisplayName("종목 기본정보: ETF · 거래정지 판별 후보 필드를 원문 코드 그대로 담는다")
    void stockInfo_mapping() {
        server.expect(requestTo(startsWith(KisTestSupport.BASE_URL + "/uapi/domestic-stock/v1/quotations/search-stock-info")))
                .andExpect(queryParam("PRDT_TYPE_CD", "300"))
                .andExpect(queryParam("PDNO", "069500"))
                .andExpect(header("tr_id", "CTPF1002R"))
                .andRespond(withSuccess(fixture("stock-info-etf.json"), MediaType.APPLICATION_JSON));

        KisStockInfo info = client.getStockInfo("069500");

        assertThat(info.ticker()).isEqualTo("069500");
        assertThat(info.name()).isEqualTo("테스트ETF");
        assertThat(info.securityGroupCode()).isEqualTo("EF");
        assertThat(info.etfDivisionCode()).isEqualTo("1");
        assertThat(info.etfTypeCode()).isEqualTo("1");
        assertThat(info.tradingHalted()).isEqualTo("N");
        assertThat(info.administrativeIssue()).isEqualTo("N");
        // 빈 문자열은 null (0이나 빈 값으로 의미를 만들지 않는다)
        assertThat(info.delistingDate()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("http.client.requests의 uri 태그는 URI 템플릿 — 종목코드 · 쿼리 값이 남지 않는다")
    void httpClientMetric_uriTemplate() {
        SimpleMeterRegistry httpMeters = new SimpleMeterRegistry();
        io.micrometer.observation.ObservationRegistry observations = io.micrometer.observation.ObservationRegistry.create();
        observations.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(httpMeters));
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL).observationRegistry(observations);
        MockRestServiceServer observedServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        KisMetrics metrics = new KisMetrics(new SimpleMeterRegistry());
        KisProperties properties = KisTestSupport.properties(2);
        KisApiClient observedClient = new KisApiClient(restClient,
                new KisTokenManager(restClient, redis.template, properties, metrics), properties, metrics);
        observedServer.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("daily-price-success.json"), MediaType.APPLICATION_JSON));

        observedClient.getDailyPrices("005930");

        assertThat(httpMeters.get("http.client.requests").timer().getId().getTag("uri"))
                .startsWith("/uapi/domestic-stock/v1/quotations/inquire-daily-price?")
                .contains("{ticker}")
                .doesNotContain("005930");
    }

    @Test
    @DisplayName("호출마다 external.api.calls(provider=kis, api, result) · external.api.latency(provider, api)를 남긴다")
    void metrics() {
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("daily-price-success.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(DAILY_PRICE)))
                .andRespond(withSuccess(fixture("error-rt-cd.json"), MediaType.APPLICATION_JSON));

        client.getDailyPrices("005930");
        assertThatThrownBy(() -> client.getDailyPrices("005930")).isInstanceOf(KisApiException.class);

        assertThat(meterRegistry.get("external.api.calls")
                .tags("provider", "kis", "api", "daily-price", "result", "success").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("external.api.calls")
                .tags("provider", "kis", "api", "daily-price", "result", "fail").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("external.api.latency")
                .tags("provider", "kis", "api", "daily-price").timer().count()).isEqualTo(2);
        // 종목코드는 태그로 남기지 않는다 (카디널리티)
        assertThat(meterRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getValue().contains("005930")));
    }
}
