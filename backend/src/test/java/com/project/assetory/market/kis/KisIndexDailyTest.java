package com.project.assetory.market.kis;

import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.IndicatorSeries;
import com.project.assetory.market.domain.MacroIndicatorRow;
import com.project.assetory.market.exception.IndicatorSeriesException;
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
import java.math.RoundingMode;
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

// 한투 업종기간별시세(일) — 필드는 B0 K1 실호출로 대조(2026-11-16). 실응답 output2에 없는 대비율은 픽스처에만 넣어 비교 경로를 본다. KisApiClient의 토큰 · 재시도 · 0 치환 금지 규칙을 그대로 쓴다
class KisIndexDailyTest {

    private static final String INDEX = KisTestSupport.BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private MockRestServiceServer server;
    private SimpleMeterRegistry meterRegistry;
    private KisApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        KisTestSupport.FakeRedis redis = new KisTestSupport.FakeRedis();
        redis.values.put(KisTokenManager.TOKEN_KEY, "cached-token");
        meterRegistry = new SimpleMeterRegistry();
        KisMetrics metrics = new KisMetrics(meterRegistry);
        RestClient restClient = builder.build();
        KisProperties properties = KisTestSupport.properties(2);
        client = new KisApiClient(restClient, new KisTokenManager(restClient, redis.template, properties, metrics), properties, metrics);
    }

    @Test
    @DisplayName("1. 요청: tr_id FHKUP03500100 · 시장 U · 업종코드 · 기간 D · 시작 · 종료일")
    void request() {
        server.expect(requestTo(startsWith(INDEX))).andExpect(method(HttpMethod.GET))
                .andExpect(header("tr_id", "FHKUP03500100"))
                .andExpect(header("authorization", "Bearer cached-token"))
                .andExpect(queryParam("FID_COND_MRKT_DIV_CODE", "U"))
                .andExpect(queryParam("FID_INPUT_ISCD", "0001"))
                .andExpect(queryParam("FID_INPUT_DATE_1", "20260921"))
                .andExpect(queryParam("FID_INPUT_DATE_2", "20261105"))
                .andExpect(queryParam("FID_PERIOD_DIV_CODE", "D"))
                .andRespond(withSuccess(fixture("index-daily-desc.json"), MediaType.APPLICATION_JSON));

        List<KisIndexDaily> rows = client.getIndexDaily("0001", FROM, TO);

        // 빈 채움 행은 건너뛴다(시세 조회와 같은 규칙)
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).tradeDate()).isEqualTo(TO);
        assertThat(rows.get(0).value()).isEqualByComparingTo("2601.51");
        assertThat(rows.get(0).kisChangeRate()).isEqualByComparingTo("2.00");
        server.verify();
    }

    @Test
    @DisplayName("2 · 3. 최신순 · 오름차순 응답이 같은 저장 행 — 가장 오래된 날짜만 제외, 최신일 포함, 변동률은 직접 계산")
    void orderIndependent_computedRate() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-desc.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-asc.json"), MediaType.APPLICATION_JSON));

        List<MacroIndicatorRow> fromDesc = toRows(client.getIndexDaily("0001", FROM, TO));
        List<MacroIndicatorRow> fromAsc = toRows(client.getIndexDaily("0001", FROM, TO));

        assertThat(fromDesc).isEqualTo(fromAsc);
        assertThat(fromDesc).extracting(MacroIndicatorRow::baseDate).containsExactly(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4), TO);
        assertThat(fromDesc).extracting(MacroIndicatorRow::changeRate).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1.0000"), new BigDecimal("-0.9901"), new BigDecimal("2.0200"), new BigDecimal("2.0000"));
    }

    @Test
    @DisplayName("3-b. 한투 대비율은 저장에 쓰지 않는다 — 계산값과 소수 2자리에서 일치하는지만 본다(픽스처 기준 — 실응답에는 대비율이 없어 운영에서는 비교를 건너뛴다)")
    void kisRate_onlyCompared() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-desc.json"), MediaType.APPLICATION_JSON));

        List<KisIndexDaily> raw = client.getIndexDaily("0001", FROM, TO);
        List<MacroIndicatorRow> rows = toRows(raw);

        for (MacroIndicatorRow row : rows) {
            KisIndexDaily kis = raw.stream().filter(r -> r.tradeDate().equals(row.baseDate())).findFirst().orElseThrow();
            assertThat(row.changeRate().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(kis.kisChangeRate());
        }
    }

    @Test
    @DisplayName("4. 같은 날짜 중복 → 그 지표 실패")
    void duplicateDate_fails() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-duplicate.json"), MediaType.APPLICATION_JSON));

        List<KisIndexDaily> raw = client.getIndexDaily("0001", FROM, TO);

        assertThatThrownBy(() -> toRows(raw)).isInstanceOf(IndicatorSeriesException.class);
    }

    @Test
    @DisplayName("5. 해석할 수 없는 값 → INVALID_RESPONSE (0으로 채우지 않는다)")
    void invalidValue_fails() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-invalid.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getIndexDaily("0001", FROM, TO))
                .isInstanceOfSatisfying(KisApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(KisErrorType.INVALID_RESPONSE));
    }

    @Test
    @DisplayName("6. 기준일(오늘) 행이 없는 응답 → 받은 날짜만, 오늘을 만들지 않는다")
    void noTodayRow_notFilled() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-desc.json"), MediaType.APPLICATION_JSON));

        List<MacroIndicatorRow> rows = toRows(client.getIndexDaily("0001", FROM, LocalDate.of(2026, 11, 6)));

        assertThat(rows).extracting(MacroIndicatorRow::baseDate).doesNotContain(LocalDate.of(2026, 11, 6));
        assertThat(rows.get(rows.size() - 1).baseDate()).isEqualTo(TO);
    }

    @Test
    @DisplayName("7. rt_cd 실패 → API_ERROR(재시도 없음) · 초당 한도(EGW00201) → 설정 횟수만큼 재시도 뒤 실패 · 메트릭 api=index-daily")
    void errors_existingRules() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(INDEX)))
                .andRespond(withSuccess(fixture("error-rt-cd.json"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getIndexDaily("0001", FROM, TO))
                .isInstanceOfSatisfying(KisApiException.class, e -> assertThat(e.getErrorType()).isEqualTo(KisErrorType.API_ERROR));
        server.verify();
        server.reset();

        server.expect(ExpectedCount.times(3), requestTo(startsWith(INDEX)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-rate-limit.json")));
        assertThatThrownBy(() -> client.getIndexDaily("0001", FROM, TO))
                .isInstanceOfSatisfying(KisApiException.class, e -> assertThat(e.getErrorType()).isEqualTo(KisErrorType.RATE_LIMIT));
        server.verify();

        assertThat(meterRegistry.get("external.api.calls").tags("provider", "kis", "api", "index-daily", "result", "fail")
                .counter().count()).isEqualTo(4);
    }

    @Test
    @DisplayName("성공 메트릭 provider=kis · api=index-daily")
    void successMetric() {
        server.expect(requestTo(startsWith(INDEX))).andRespond(withSuccess(fixture("index-daily-desc.json"), MediaType.APPLICATION_JSON));

        client.getIndexDaily("0001", FROM, TO);

        assertThat(meterRegistry.get("external.api.calls").tags("provider", "kis", "api", "index-daily", "result", "success")
                .counter().count()).isEqualTo(1);
    }

    private static List<MacroIndicatorRow> toRows(List<KisIndexDaily> raw) {
        return IndicatorSeries.toRows(IndicatorCode.KOSPI,
                raw.stream().map(r -> new DatedValue(r.tradeDate(), r.value())).toList(), true);
    }
}
