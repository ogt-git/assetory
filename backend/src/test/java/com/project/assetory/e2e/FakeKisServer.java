package com.project.assetory.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜 한투(KIS) Open API 서버 — 테스트 전용. 실제 KisApiClient · 토큰 Redis 저장 · 메트릭을 그대로 거치게 하려고
 * HTTP 수준에서 흉내 낸다(JDK 내장 HttpServer, 새 라이브러리 없음). 응답 필드명은 공식 예제 기준이고 값은 모두 더미다.
 *
 * 모드: NORMAL(정상) / NETWORK_DOWN(연결을 응답 없이 끊음 — E5) / BAD_KEY(토큰 발급 403 — E6)
 */
public final class FakeKisServer {

    public enum Mode { NORMAL, NETWORK_DOWN, BAD_KEY }

    // 테스트 전용 더미 토큰 — 실제 토큰이 아니다
    public static final String TOKEN = "e2e-test-only-token";
    public static final long TOKEN_EXPIRES_IN = 86_400;

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpServer server;
    private volatile Mode mode = Mode.NORMAL;
    // 종목코드 → 최신 거래일부터의 (거래일, 종가) — 대비 · 대비율은 다음 행 종가로 계산해 응답에 넣는다
    private final Map<String, List<Day>> dailyPrices = new ConcurrentHashMap<>();
    // 종목코드 → (증권그룹, 거래정지 Y/N)
    private final Map<String, String[]> stockInfos = new ConcurrentHashMap<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    public record Day(LocalDate date, BigDecimal close) {}

    public FakeKisServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/oauth2/tokenP", this::token);
        server.createContext("/uapi/domestic-stock/v1/quotations/inquire-daily-price", this::dailyPrice);
        server.createContext("/uapi/domestic-stock/v1/quotations/search-stock-info", this::stockInfo);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- 시나리오 설정 ----------

    public void reset() {
        mode = Mode.NORMAL;
        dailyPrices.clear();
        stockInfos.clear();
        tokenRequests.set(0);
        requests.clear();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void prices(String ticker, Day... latestFirst) {
        dailyPrices.put(ticker, List.of(latestFirst));
    }

    public void stockInfo(String ticker, String securityGroup, boolean halted) {
        stockInfos.put(ticker, new String[]{securityGroup, halted ? "Y" : "N"});
    }

    public int tokenRequests() {
        return tokenRequests.get();
    }

    public int requests(String path) {
        AtomicInteger count = requests.get(path);
        return count == null ? 0 : count.get();
    }

    // ---------- 핸들러 ----------

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        if (dropIfDown(exchange)) return;
        if (mode == Mode.BAD_KEY) {
            send(exchange, 403, "{\"error_code\":\"EGW00103\",\"error_description\":\"테스트 전용 — 유효하지 않은 AppKey\"}");
            return;
        }
        send(exchange, 200, """
                {"access_token":"%s","access_token_token_expired":"2099-12-31 23:59:59","token_type":"Bearer","expires_in":%d}"""
                .formatted(TOKEN, TOKEN_EXPIRES_IN));
    }

    private void dailyPrice(HttpExchange exchange) throws IOException {
        count("daily-price");
        if (dropIfDown(exchange) || rejectBadToken(exchange)) return;
        String ticker = query(exchange.getRequestURI(), "FID_INPUT_ISCD");
        List<Day> days = dailyPrices.getOrDefault(ticker, List.of());
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            BigDecimal prev = i + 1 < days.size() ? days.get(i + 1).close() : day.close();
            BigDecimal change = day.close().subtract(prev);
            BigDecimal rate = change.multiply(BigDecimal.valueOf(100)).divide(prev, 2, RoundingMode.HALF_UP);
            String sign = change.signum() > 0 ? "2" : change.signum() < 0 ? "5" : "3";
            rows.add("""
                    {"stck_bsop_date":"%s","stck_clpr":"%s","acml_vol":"1000","prdy_vrss":"%s","prdy_vrss_sign":"%s",
                     "prdy_ctrt":"%s","flng_cls_code":"00","acml_prtt_rate":"1.00"}"""
                    .formatted(day.date().format(BASIC), day.close().toPlainString(), change.toPlainString(), sign,
                            rate.toPlainString()));
        }
        send(exchange, 200, "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"정상처리 되었습니다.\",\"output\":["
                + String.join(",", rows) + "]}");
    }

    private void stockInfo(HttpExchange exchange) throws IOException {
        count("stock-info");
        if (dropIfDown(exchange) || rejectBadToken(exchange)) return;
        String ticker = query(exchange.getRequestURI(), "PDNO");
        String[] info = stockInfos.getOrDefault(ticker, new String[]{"ST", "N"});
        send(exchange, 200, """
                {"rt_cd":"0","msg_cd":"KIOK0000","msg1":"정상처리 되었습니다","output":{"pdno":"%s","prdt_abrv_name":"테스트%s",
                 "scty_grp_id_cd":"%s","etf_dvsn_cd":"","etf_type_cd":"","tr_stop_yn":"%s","admn_item_yn":"N","lstg_abol_dt":""}}"""
                .formatted(ticker, ticker, info[0], info[1]));
    }

    // NETWORK_DOWN — 응답 없이 연결을 끊는다(클라이언트에는 I/O 오류)
    private boolean dropIfDown(HttpExchange exchange) {
        if (mode != Mode.NETWORK_DOWN) return false;
        exchange.close();
        return true;
    }

    private boolean rejectBadToken(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("authorization");
        if (("Bearer " + TOKEN).equals(auth)) return false;
        send(exchange, 500, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00121\",\"msg1\":\"테스트 전용 — 유효하지 않은 token\"}");
        return true;
    }

    private void count(String api) {
        requests.computeIfAbsent(api, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String query(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return kv.length > 1 ? kv[1] : "";
        }
        return "";
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
