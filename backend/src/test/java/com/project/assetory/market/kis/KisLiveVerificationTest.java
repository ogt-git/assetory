package com.project.assetory.market.kis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 한투 실호출 검증 (Step 0 → Step 1 확인 항목 V1~V5). 기본 `./gradlew test`에서는 제외되고 `./gradlew kisLiveTest`로만 돈다.
 * KIS_APP_KEY · KIS_APP_SECRET 환경변수가 있을 때만 실행. 결과는 build/kis-live/에 남긴다 → docs/ops/kis-verification.md에 옮겨 적는다.
 * 실행할 때마다 토큰 발급 1회(알림톡 1건)가 생긴다 — 6시간 안이면 같은 토큰이 온다.
 * 토큰 · 앱키는 출력하지 않는다.
 */
@Tag("kis-live")
@EnabledIfEnvironmentVariable(named = "KIS_APP_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KisLiveVerificationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Path OUT_DIR = Path.of("build", "kis-live");
    private static final StringBuilder REPORT = new StringBuilder();

    private static RestClient restClient;
    private static KisProperties properties;
    private static KisTokenManager tokenManager;
    private static KisApiClient client;

    @BeforeAll
    static void setUp() {
        properties = new KisProperties(
                env("KIS_BASE_URL", "https://openapi.koreainvestment.com:9443"),
                System.getenv("KIS_APP_KEY"), System.getenv("KIS_APP_SECRET"),
                Long.parseLong(env("KIS_CALL_INTERVAL_MS", "500")),
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMinutes(10),
                new KisProperties.RateLimit(2, 1000));
        restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        KisMetrics metrics = new KisMetrics(new SimpleMeterRegistry());
        // Redis 없이 실행 — 메모리 대역에 토큰을 둔다 (이 실행 안에서만 재사용)
        tokenManager = new KisTokenManager(restClient, new KisTestSupport.FakeRedis().template, properties, metrics);
        client = new KisApiClient(restClient, tokenManager, properties, metrics);
        line("# 한투 실호출 검증 결과 — " + LocalDateTime.now(KST).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        line("- base-url: " + properties.baseUrl());
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(OUT_DIR);
        Path file = OUT_DIR.resolve("verification-" + LocalDateTime.now(KST).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println("\n[kis-live] 결과 파일: " + file.toAbsolutePath());
    }

    @Test
    @Order(1)
    @DisplayName("V1 — prdy_ctrt 기준: 최근 30일 prdy_ctrt = prdy_vrss ÷ (종가 − prdy_vrss) 확인 + 액면분할일(005930 2018-05-04) prdy_vrss vs 종가 − 전일 종가")
    void v1_changeRateBase() {
        line("\n## V1 #5 변동률 기준");
        // (1) 최근 30일 — prdy_ctrt가 prdy_vrss와 같은 기준으로 계산되는지
        List<KisDailyPrice> recent = client.getDailyPrices("005930");
        int consistent = 0;
        for (KisDailyPrice p : recent) {
            BigDecimal base = p.closePrice().subtract(p.change());
            if (base.signum() == 0) continue;
            BigDecimal computed = p.change().multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
            if (computed.subtract(p.changeRate()).abs().compareTo(new BigDecimal("0.01")) <= 0) consistent++;
        }
        line("- 최근 " + recent.size() + "거래일 중 prdy_ctrt ≈ prdy_vrss ÷ (종가 − prdy_vrss) × 100 인 날: " + consistent);

        // (2) 액면분할일 — 원주가(FID_ORG_ADJ_PRC=1)로 분할 전후 행을 받아 prdy_vrss의 기준을 본다. 이 호출은 검증용으로 테스트 안에서만 한다
        JsonNode body = itemChartPriceAround20180504();
        line("- itemchartprice rt_cd=" + body.path("rt_cd").asText() + " msg_cd=" + body.path("msg_cd").asText());
        line("\n| 거래일 | 종가 | prdy_vrss(부호 적용 전 원문) | 부호 | 직전 행 종가 | 종가 − 직전 종가 | flng_cls_code | prtt_rate | mod_yn |");
        line("|---|---|---|---|---|---|---|---|---|");
        JsonNode rows = body.path("output2");
        for (int i = 0; i < rows.size(); i++) {
            JsonNode r = rows.get(i);
            String prev = i + 1 < rows.size() ? rows.get(i + 1).path("stck_clpr").asText() : "";
            String diff = "";
            if (!prev.isBlank() && !r.path("stck_clpr").asText().isBlank()) {
                diff = new BigDecimal(r.path("stck_clpr").asText()).subtract(new BigDecimal(prev)).toPlainString();
            }
            line("| " + r.path("stck_bsop_date").asText() + " | " + r.path("stck_clpr").asText() + " | "
                    + r.path("prdy_vrss").asText() + " | " + r.path("prdy_vrss_sign").asText() + " | " + prev + " | " + diff
                    + " | " + r.path("flng_cls_code").asText() + " | " + r.path("prtt_rate").asText() + " | " + r.path("mod_yn").asText() + " |");
        }
        line("\n판정: 20180504 행의 prdy_vrss가 '종가 − 직전 종가'(≈ −2,598,100)와 같으면 **전일 종가 대비**, 작은 값이면 **기준가(분할 조정) 대비**.");
    }

    @Test
    @Order(2)
    @DisplayName("V2 · V3 — ETF 판별 · 거래정지 후보 필드 실제 값 (069500 ETF, 005930 주식, KIS_HALTED_TICKER 선택)")
    void v2v3_stockInfo() {
        line("\n## V2 · V3 종목 기본정보");
        line("\n| 종목 | 이름 | scty_grp_id_cd | etf_dvsn_cd | etf_type_cd | tr_stop_yn | admn_item_yn | lstg_abol_dt |");
        line("|---|---|---|---|---|---|---|---|");
        Stream.of("069500", "005930", System.getenv("KIS_HALTED_TICKER"))
                .filter(t -> t != null && !t.isBlank())
                .forEach(t -> {
                    KisStockInfo i = client.getStockInfo(t);
                    line("| " + t + " | " + i.name() + " | " + i.securityGroupCode() + " | " + i.etfDivisionCode() + " | "
                            + i.etfTypeCode() + " | " + i.tradingHalted() + " | " + i.administrativeIssue() + " | " + i.delistingDate() + " |");
                });
    }

    @Test
    @Order(3)
    @DisplayName("V4 · V5 — 최신 거래일 행 기록 (16:00 이후 · 다음 날 · 주말에 각각 실행해 비교)")
    void v4v5_latestRows() throws IOException {
        line("\n## V4 · V5 최신 거래일");
        LocalDateTime now = LocalDateTime.now(KST);
        List<KisDailyPrice> prices = client.getDailyPrices("005930");
        line("- 실행 시각(KST): " + now + " (" + now.getDayOfWeek() + ")");
        line("- 최신 stck_bsop_date: " + prices.get(0).tradeDate() + " — 오늘(" + LocalDate.now(KST) + ")과 "
                + (prices.get(0).tradeDate().equals(LocalDate.now(KST)) ? "같음" : "다름 (휴장일이면 직전 거래일이어야 함)"));
        line("\n| 거래일 | 종가 | 대비 | 대비율 | 거래량 |");
        line("|---|---|---|---|---|");
        prices.stream().limit(3).forEach(p -> line("| " + p.tradeDate() + " | " + p.closePrice().toPlainString() + " | "
                + p.change().toPlainString() + " | " + p.changeRate().toPlainString() + " | " + p.volume() + " |"));
        line("\nV4: 16:00 이후 실행한 파일과 다음 날 실행한 파일의 같은 거래일 행을 비교한다 (build/kis-live/verification-*.md).");
    }

    @Test
    @Order(4)
    @DisplayName("V4-b — 종가 조회 방식 비교: inquire-daily-price · itemchartprice × FID_ORG_ADJ_PRC 0 · 1 (005930 최근 5거래일)")
    void v4b_closePriceSources() {
        line("\n## V4-b 종가 조회 방식 비교 (005930)");
        line("- 공식 종가(사용자 확인값): 2026-10-27 225,000 · 2026-10-29 230,500");
        line("- 배치(KisApiClient.getDailyPrices)는 inquire-daily-price + FID_ORG_ADJ_PRC=0");
        String to = LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE);
        String from = LocalDate.now(KST).minusDays(14).format(DateTimeFormatter.BASIC_ISO_DATE);
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        for (String adj : List.of("0", "1")) {
            sources.put("daily-price adj=" + adj, directGet(DAILY_PRICE_PATH,
                    Map.of("m", "J", "t", "005930", "p", "D", "adj", adj), "FHKST01010400").path("output"));
            sources.put("itemchart adj=" + adj, directGet(ITEM_CHART_PATH,
                    Map.of("m", "J", "t", "005930", "from", from, "to", to, "p", "D", "adj", adj), "FHKST03010100").path("output2"));
        }
        // 시장 구분 — J = KRX, NX = 넥스트레이드, UN = 통합. 일별 행에 NXT 거래(~20:00)가 섞이는지 본다
        for (String market : List.of("NX", "UN")) {
            sources.put("daily-price m=" + market, directGet(DAILY_PRICE_PATH,
                    Map.of("m", market, "t", "005930", "p", "D", "adj", "0"), "FHKST01010400").path("output"));
        }
        line("\n| 거래일 | " + String.join(" | ", sources.keySet()) + " |");
        line("|---|" + "---|".repeat(sources.size()));
        JsonNode base = sources.values().iterator().next();
        for (int i = 0; i < Math.min(5, base.size()); i++) {
            String date = base.get(i).path("stck_bsop_date").asText();
            StringBuilder row = new StringBuilder("| " + date + " |");
            for (JsonNode rows : sources.values()) {
                row.append(' ').append(closeAndVolume(rows, date)).append(" |");
            }
            line(row.toString());
        }
        line("\n칸 = 종가 (거래량). 공식 종가와 같은 열이 실제 체결 종가를 주는 조회 방식이다.");
    }

    @Test
    @Order(5)
    @DisplayName("V4-c — 시간외 일자별 시세(FHPST02320000) 원문 필드: 일별 행 종가가 시간외 단일가를 반영하는지 (005930)")
    void v4c_overtimeDailyPrices() {
        line("\n## V4-c 시간외 일자별 시세 (005930)");
        line("- 공식 종가(사용자 확인값): 2026-10-27 225,000 · 2026-10-29 230,500 · 2026-10-30 220,000");
        JsonNode body = directGet(OVERTIME_DAILY_PATH, Map.of("m", "J", "t", "005930"), "FHPST02320000");
        line("- rt_cd=" + body.path("rt_cd").asText() + " msg_cd=" + body.path("msg_cd").asText());
        // 필드 이름을 확인하지 못했으므로 행마다 원문 필드를 모두 남긴다
        JsonNode rows = body.path("output2");
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            StringBuilder fields = new StringBuilder();
            rows.get(i).fields().forEachRemaining(f -> fields.append(f.getKey()).append('=').append(f.getValue().asText()).append(' '));
            line("- " + fields.toString().trim());
        }
        line("\n판정: 공식 종가가 stck_clpr에, 일별 시세(inquire-daily-price) 종가가 시간외 단일가 필드에 있으면 일별 행에 시간외가 섞인 것이다.");
    }

    private static String closeAndVolume(JsonNode rows, String date) {
        for (JsonNode r : rows) {
            if (date.equals(r.path("stck_bsop_date").asText())) {
                return r.path("stck_clpr").asText() + " (" + r.path("acml_vol").asText() + ")";
            }
        }
        return "없음";
    }

    private static final String DAILY_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-price"
            + "?FID_COND_MRKT_DIV_CODE={m}&FID_INPUT_ISCD={t}&FID_PERIOD_DIV_CODE={p}&FID_ORG_ADJ_PRC={adj}";
    private static final String ITEM_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
            + "?FID_COND_MRKT_DIV_CODE={m}&FID_INPUT_ISCD={t}&FID_INPUT_DATE_1={from}&FID_INPUT_DATE_2={to}"
            + "&FID_PERIOD_DIV_CODE={p}&FID_ORG_ADJ_PRC={adj}";
    private static final String OVERTIME_DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-overtimeprice"
            + "?FID_COND_MRKT_DIV_CODE={m}&FID_INPUT_ISCD={t}";

    private static JsonNode itemChartPriceAround20180504() {
        return directGet(ITEM_CHART_PATH,
                Map.of("m", "J", "t", "005930", "from", "20180420", "to", "20180510", "p", "D", "adj", "1"), "FHKST03010100");
    }

    // 검증용 직접 호출 — 클라이언트 스로틀을 거치지 않으므로 호출 전 1초 쉬고, EGW00201(초당 건수 초과)이면 최대 2회 더 시도 (C-07)
    private static JsonNode directGet(String uri, Map<String, String> params, String trId) {
        JsonNode body = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            pause(1000);
            body = restClient.get()
                    .uri(uri, params)
                    .headers(h -> {
                        h.setBearerAuth(tokenManager.getToken());
                        h.set("appkey", properties.appKey());
                        h.set("appsecret", properties.appSecret());
                        h.set("tr_id", trId);
                        h.set("custtype", "P");
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .body(JsonNode.class);
            if (!"EGW00201".equals(body.path("msg_cd").asText())) {
                return body;
            }
        }
        return body;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void line(String text) {
        REPORT.append(text).append('\n');
        System.out.println(text);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
