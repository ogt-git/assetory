package com.project.assetory.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜 한국은행 ECOS 서버 — 테스트 · fake 프로필 전용. 실제 EcosApiClient를 HTTP 수준에서 그대로 거치게 한다.
 * 응답 형식은 B4 픽스처와 같은 개발 명세서 기준(실응답 미대조). 값은 지어낸 더미 값이다.
 * - /api/StatisticSearch/{key}/json/kr/{시작번호}/{끝번호}/{통계표}/{주기}/{시작일}/{종료일}/{항목}: 경로에서 읽어 기간으로 걸러
 *   날짜 오름차순으로 준다. 없으면 INFO-200
 * - 경로의 키가 등록한 더미 키와 다르면 INFO-100
 * 모드: NORMAL / BAD_KEY(INFO-100) / NO_DATA(INFO-200) / RATE_LIMIT(ERROR-602) / NETWORK_DOWN(응답 없이 끊음)
 */
public final class FakeEcosServer {

    public enum Mode { NORMAL, BAD_KEY, NO_DATA, RATE_LIMIT, NETWORK_DOWN }

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String PREFIX = "/api/StatisticSearch/";

    private final HttpServer server;
    private final String apiKey;
    private volatile Mode mode = Mode.NORMAL;
    // "통계표/항목" → 날짜순 값
    private final Map<String, TreeMap<LocalDate, BigDecimal>> series = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastStatCode;

    public FakeEcosServer(String apiKey) {
        this.apiKey = apiKey;
        this.server = FakeHttp.start();
        server.createContext(PREFIX, this::search);
    }

    /** assetory.ecos.base-url에 넣을 주소 */
    public String baseUrl() {
        return FakeHttp.origin(server) + "/api";
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- 시나리오 설정 ----------

    public void reset() {
        mode = Mode.NORMAL;
        series.clear();
        requests.set(0);
        lastStatCode = null;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void value(String statCode, String itemCode, LocalDate date, BigDecimal value) {
        series.computeIfAbsent(statCode + "/" + itemCode, k -> new TreeMap<>()).put(date, value);
    }

    public int requests() {
        return requests.get();
    }

    public String lastStatCode() {
        return lastStatCode;
    }

    // ---------- 핸들러 ----------

    private void search(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (mode == Mode.NETWORK_DOWN) {
            FakeHttp.drop(exchange);
            return;
        }
        // {key}/json/kr/{startNo}/{endNo}/{stat}/{cycle}/{from}/{to}/{item}
        String[] p = exchange.getRequestURI().getRawPath().substring(PREFIX.length()).split("/");
        if (p.length < 10) {
            result(exchange, "ERROR-100", "필수 값이 누락되어 있습니다.");
            return;
        }
        String key = FakeHttp.decode(p[0]);
        lastStatCode = p[5];
        if (mode == Mode.BAD_KEY || !apiKey.equals(key)) {
            result(exchange, "INFO-100", "인증키가 유효하지 않습니다.");
            return;
        }
        if (mode == Mode.RATE_LIMIT) {
            result(exchange, "ERROR-602", "과도한 OpenAPI호출로 이용이 제한되었습니다.");
            return;
        }
        int startNo = Integer.parseInt(p[3]);
        int endNo = Integer.parseInt(p[4]);
        LocalDate from = LocalDate.parse(p[7], BASIC);
        LocalDate to = LocalDate.parse(p[8], BASIC);
        TreeMap<LocalDate, BigDecimal> values = series.getOrDefault(p[5] + "/" + p[9], new TreeMap<>());
        List<Map.Entry<LocalDate, BigDecimal>> matched = new ArrayList<>(values.subMap(from, true, to, true).entrySet());
        if (mode == Mode.NO_DATA || matched.isEmpty()) {
            result(exchange, "INFO-200", "해당하는 데이터가 없습니다.");
            return;
        }
        List<String> rows = new ArrayList<>();
        for (int i = startNo - 1; i < Math.min(matched.size(), endNo); i++) {
            Map.Entry<LocalDate, BigDecimal> e = matched.get(i);
            rows.add("{\"STAT_CODE\":%s,\"STAT_NAME\":\"테스트 통계\",\"ITEM_CODE1\":%s,\"ITEM_NAME1\":\"테스트 항목\",\"UNIT_NAME\":\"\",\"WGT\":null,\"TIME\":%s,\"DATA_VALUE\":%s}"
                    .formatted(FakeHttp.json(p[5]), FakeHttp.json(p[9]), FakeHttp.json(e.getKey().format(BASIC)),
                            FakeHttp.json(e.getValue().toPlainString())));
        }
        FakeHttp.sendJson(exchange, 200, "{\"StatisticSearch\":{\"list_total_count\":%d,\"row\":[%s]}}"
                .formatted(matched.size(), String.join(",", rows)));
    }

    private static void result(HttpExchange exchange, String code, String message) throws IOException {
        FakeHttp.sendJson(exchange, 200, "{\"RESULT\":{\"CODE\":%s,\"MESSAGE\":%s}}".formatted(FakeHttp.json(code), FakeHttp.json(message)));
    }
}
