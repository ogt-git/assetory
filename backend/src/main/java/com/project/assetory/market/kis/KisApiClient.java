package com.project.assetory.market.kis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.market.exception.KisApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한투(KIS) 국내주식 시세 조회 (FR-MK-01).
 * - URI 템플릿으로 호출한다 — http.client.requests의 uri 태그에 종목코드 · 쿼리 값이 남지 않게
 * - 순차 호출 전제의 단순 스로틀(call-interval-ms)
 * - EGW00201만 설정 횟수까지 재시도, 토큰 오류는 1회 재발급 후 재시도, 그 외는 재시도하지 않음
 * - 필수 값 누락 · 숫자 해석 불가는 0으로 채우지 않고 INVALID_RESPONSE (AGENTS 5-C)
 */
@Slf4j
@Component
public class KisApiClient {

    private static final String DAILY_PRICE_URI = "/uapi/domestic-stock/v1/quotations/inquire-daily-price"
            + "?FID_COND_MRKT_DIV_CODE={market}&FID_INPUT_ISCD={ticker}&FID_PERIOD_DIV_CODE={period}&FID_ORG_ADJ_PRC={adjusted}";
    private static final String DAILY_PRICE_TR_ID = "FHKST01010400";

    private static final String STOCK_INFO_URI = "/uapi/domestic-stock/v1/quotations/search-stock-info"
            + "?PRDT_TYPE_CD={productType}&PDNO={ticker}";
    private static final String STOCK_INFO_TR_ID = "CTPF1002R";

    // KRX 고정 — NX(넥스트레이드) · UN(통합)은 장 마감 뒤 거래가 섞일 수 있다
    private static final String MARKET_KRX = "J";
    // inquire-daily-price: 0 = 수정주가 미반영(실제 체결가). 평가금액은 실제 종가 × 수량이므로 원주가로 받는다
    private static final String RAW_PRICE = "0";
    // search-stock-info 상품유형 300 = 주식 · ETF · ETN · ELW
    private static final String PRODUCT_TYPE_STOCK = "300";

    private static final String RATE_LIMIT_CODE = "EGW00201";
    // 토큰 오류 코드 — 공식 예제에 목록이 없어 실호출로 확인 필요 (EGW00121 유효하지 않은 토큰, EGW00123 만료 토큰)
    private static final Set<String> TOKEN_ERROR_CODES = Set.of("EGW00121", "EGW00123");

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final KisTokenManager tokenManager;
    private final KisProperties properties;
    private final KisMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long lastCallNanos;

    public KisApiClient(@Qualifier("kisRestClient") RestClient restClient, KisTokenManager tokenManager,
                        KisProperties properties, KisMetrics metrics) {
        this.restClient = restClient;
        this.tokenManager = tokenManager;
        this.properties = properties;
        this.metrics = metrics;
    }

    // 최근 거래일 목록(최신이 먼저, 최대 30거래일). 장 마감 뒤 조회하면 첫 행이 당일
    public List<KisDailyPrice> getDailyPrices(String ticker) {
        JsonNode body = get(KisMetrics.API_DAILY_PRICE, DAILY_PRICE_URI, DAILY_PRICE_TR_ID, Map.of(
                "market", MARKET_KRX, "ticker", ticker, "period", "D", "adjusted", RAW_PRICE));
        JsonNode output = body.path("output");
        if (!output.isArray()) {
            throw invalid("output 형식");
        }
        List<KisDailyPrice> prices = new ArrayList<>();
        for (JsonNode row : output) {
            if (isBlankRow(row)) continue; // 상장 30거래일 미만 등 빈 채움 행
            prices.add(new KisDailyPrice(
                    requiredDate(row, "stck_bsop_date"),
                    requiredDecimal(row, "stck_clpr"),
                    requiredDecimal(row, "prdy_vrss"),
                    requiredText(row, "prdy_vrss_sign"),
                    requiredDecimal(row, "prdy_ctrt"),
                    requiredLong(row, "acml_vol"),
                    optionalText(row, "flng_cls_code"),
                    optionalDecimal(row, "acml_prtt_rate")));
        }
        return prices;
    }

    // 종목 기본정보 — Step 1에서는 ETF · 거래정지 판별 필드 검증용 (security_type 채우기는 Step 2 결정 후)
    public KisStockInfo getStockInfo(String ticker) {
        JsonNode body = get(KisMetrics.API_STOCK_INFO, STOCK_INFO_URI, STOCK_INFO_TR_ID, Map.of("productType", PRODUCT_TYPE_STOCK, "ticker", ticker));
        JsonNode output = body.path("output");
        if (!output.isObject()) {
            throw invalid("output 형식");
        }
        return new KisStockInfo(
                optionalText(output, "pdno"),
                optionalText(output, "prdt_abrv_name"),
                optionalText(output, "scty_grp_id_cd"),
                optionalText(output, "etf_dvsn_cd"),
                optionalText(output, "etf_type_cd"),
                optionalText(output, "tr_stop_yn"),
                optionalText(output, "admn_item_yn"),
                optionalText(output, "lstg_abol_dt"));
    }

    private JsonNode get(String api, String uriTemplate, String trId, Map<String, ?> uriVariables) {
        String token = tokenManager.getToken();
        int rateLimitRetries = 0;
        boolean tokenReissued = false;

        while (true) {
            throttle();
            long start = System.nanoTime();
            ResponseEntity<String> response;
            try {
                final String bearer = token;
                response = restClient.get()
                        .uri(uriTemplate, uriVariables)
                        .headers(h -> {
                            h.setBearerAuth(bearer);
                            h.set("appkey", properties.appKey());
                            h.set("appsecret", properties.appSecret());
                            h.set("tr_id", trId);
                            h.set("custtype", "P");
                        })
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> { })
                        .toEntity(String.class);
            } catch (RestClientException e) {
                metrics.record(api, false, start);
                log.warn("[KIS] 호출 실패 trId={} error={}", trId, e.getClass().getSimpleName());
                throw new KisApiException(KisErrorType.NETWORK_ERROR, null, e);
            }

            JsonNode body = readTree(response.getBody());
            String rtCd = body.path("rt_cd").asText("");
            String msgCd = body.path("msg_cd").asText(null);
            if (response.getStatusCode().is2xxSuccessful() && "0".equals(rtCd)) {
                metrics.record(api, true, start);
                return body;
            }
            metrics.record(api, false, start);

            if (RATE_LIMIT_CODE.equals(msgCd)) {
                if (rateLimitRetries < properties.rateLimit().maxRetries()) {
                    rateLimitRetries++;
                    log.info("[KIS] 초당 건수 초과 재시도 {}/{} trId={}", rateLimitRetries, properties.rateLimit().maxRetries(), trId);
                    sleep(properties.rateLimit().backoffMs());
                    continue;
                }
                throw new KisApiException(KisErrorType.RATE_LIMIT, msgCd);
            }
            if (msgCd != null && TOKEN_ERROR_CODES.contains(msgCd)) {
                if (!tokenReissued) {
                    tokenReissued = true;
                    log.info("[KIS] 토큰 오류 — 1회 재발급 후 재시도 trId={} code={}", trId, msgCd);
                    token = tokenManager.reissue(token);
                    continue;
                }
                throw new KisApiException(KisErrorType.TOKEN_EXPIRED, msgCd);
            }
            log.warn("[KIS] 실패 응답 trId={} status={} code={}", trId, response.getStatusCode().value(), msgCd);
            throw new KisApiException(rtCd.isEmpty() ? KisErrorType.INVALID_RESPONSE : KisErrorType.API_ERROR, msgCd);
        }
    }

    // 순차 호출 전제 — 직전 호출에서 call-interval-ms가 지나지 않았으면 기다린다
    private synchronized void throttle() {
        long intervalNanos = properties.callIntervalMs() * 1_000_000L;
        long waitNanos = lastCallNanos + intervalNanos - System.nanoTime();
        if (lastCallNanos != 0 && waitNanos > 0) {
            sleep(waitNanos / 1_000_000L);
        }
        lastCallNanos = System.nanoTime();
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisApiException(KisErrorType.NETWORK_ERROR, null, e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return body == null ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static boolean isBlankRow(JsonNode row) {
        for (Iterator<JsonNode> it = row.elements(); it.hasNext(); ) {
            if (!it.next().asText("").isBlank()) return false;
        }
        return true;
    }

    private static LocalDate requiredDate(JsonNode node, String field) {
        try {
            return LocalDate.parse(requiredText(node, field), BASIC_DATE);
        } catch (DateTimeParseException e) {
            throw invalid(field);
        }
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = optionalDecimal(node, field);
        if (value == null) throw invalid(field);
        return value;
    }

    private static long requiredLong(JsonNode node, String field) {
        try {
            return requiredDecimal(node, field).longValueExact();
        } catch (ArithmeticException e) {
            throw invalid(field);
        }
    }

    // 없거나 빈 값은 null, 값이 있는데 숫자가 아니면 실패 (0으로 바꾸지 않는다)
    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        String raw = optionalText(node, field);
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            throw invalid(field);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) throw invalid(field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static KisApiException invalid(String field) {
        log.warn("[KIS] 응답 해석 불가 field={}", field);
        return new KisApiException(KisErrorType.INVALID_RESPONSE, null);
    }
}
