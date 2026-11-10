package com.project.assetory.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 가짜 OpenDART 서버 — 테스트 · fake 프로필 전용. 실제 DartApiClient · 고유번호 캐시를 HTTP 수준에서 그대로 거치게 한다.
 * 응답 형식은 B2 픽스처와 같은 개발가이드 기준(실응답 미대조). 회사 · 공시는 모두 지어낸 더미 값이다.
 * - /api/list.json: corp_code · pblntf_ty · rcept_dt(bgn_de~end_de)로 걸러 page_no · page_count로 나눈다. 없으면 013
 * - /api/corpCode.xml: 등록한 회사로 CORPCODE.xml ZIP을 즉석에서 만든다(비상장 1건 포함 — stock_code 공백)
 * - crtfc_key가 등록한 더미 키와 다르면 010
 * 모드: NORMAL / BAD_KEY(010) / RATE_LIMIT(020) / NETWORK_DOWN(응답 없이 끊음)
 */
public final class FakeDartServer {

    public enum Mode { NORMAL, BAD_KEY, RATE_LIMIT, NETWORK_DOWN }

    private record Corp(String stockCode, String corpCode, String name) {}

    private record Disclosure(String corpCode, String type, String receiptNo, String reportName, LocalDate receiptDate) {}

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpServer server;
    private final String apiKey;
    private volatile Mode mode = Mode.NORMAL;
    private final List<Corp> corps = new CopyOnWriteArrayList<>();
    private final List<Disclosure> disclosures = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final Map<String, String> lastQueries = new ConcurrentHashMap<>();

    public FakeDartServer(String apiKey) {
        this.apiKey = apiKey;
        this.server = FakeHttp.start();
        server.createContext("/api/list.json", this::list);
        server.createContext("/api/corpCode.xml", this::corpCode);
    }

    /** assetory.dart.base-url에 넣을 주소 */
    public String baseUrl() {
        return FakeHttp.origin(server) + "/api";
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- 시나리오 설정 ----------

    public void reset() {
        mode = Mode.NORMAL;
        corps.clear();
        disclosures.clear();
        requests.clear();
        lastQueries.clear();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void corp(String stockCode, String corpCode, String name) {
        corps.add(new Corp(stockCode, corpCode, name));
    }

    /** @param type 공시 유형 pblntf_ty (A~J) */
    public void disclosure(String corpCode, String type, String receiptNo, String reportName, LocalDate receiptDate) {
        disclosures.add(new Disclosure(corpCode, type, receiptNo, reportName, receiptDate));
    }

    /** @param api "list" · "corp-code" */
    public int requests(String api) {
        AtomicInteger count = requests.get(api);
        return count == null ? 0 : count.get();
    }

    /** 마지막 요청의 원본 쿼리(디코딩 전) — 키가 실려 갔는지 확인용 */
    public String lastQuery(String api) {
        return lastQueries.get(api);
    }

    // ---------- 핸들러 ----------

    private void list(HttpExchange exchange) throws IOException {
        record("list", exchange);
        if (mode == Mode.NETWORK_DOWN) {
            FakeHttp.drop(exchange);
            return;
        }
        Map<String, String> q = FakeHttp.query(exchange.getRequestURI());
        String error = error(q.get("crtfc_key"));
        if (error != null) {
            FakeHttp.sendJson(exchange, 200, error);
            return;
        }
        LocalDate from = LocalDate.parse(q.get("bgn_de"), BASIC);
        LocalDate to = LocalDate.parse(q.get("end_de"), BASIC);
        List<Disclosure> matched = new ArrayList<>(disclosures.stream()
                .filter(d -> d.corpCode().equals(q.get("corp_code")))
                .filter(d -> q.get("pblntf_ty") == null || d.type().equals(q.get("pblntf_ty")))
                .filter(d -> !d.receiptDate().isBefore(from) && !d.receiptDate().isAfter(to))
                .toList());
        if (matched.isEmpty()) {
            FakeHttp.sendJson(exchange, 200, "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}");
            return;
        }
        // 최신 접수가 먼저
        matched.sort(Comparator.comparing(Disclosure::receiptNo).reversed());
        int pageNo = Integer.parseInt(q.getOrDefault("page_no", "1"));
        int pageCount = Integer.parseInt(q.getOrDefault("page_count", "10"));
        int totalPage = (matched.size() + pageCount - 1) / pageCount;
        List<String> rows = new ArrayList<>();
        for (int i = (pageNo - 1) * pageCount; i < Math.min(matched.size(), pageNo * pageCount); i++) {
            Disclosure d = matched.get(i);
            Corp corp = corps.stream().filter(c -> c.corpCode().equals(d.corpCode())).findFirst().orElse(null);
            rows.add("{\"corp_code\":%s,\"corp_name\":%s,\"stock_code\":%s,\"corp_cls\":\"Y\",\"report_nm\":%s,\"rcept_no\":%s,"
                    .formatted(FakeHttp.json(d.corpCode()), FakeHttp.json(corp == null ? "" : corp.name()),
                            FakeHttp.json(corp == null ? "" : corp.stockCode()), FakeHttp.json(d.reportName()),
                            FakeHttp.json(d.receiptNo()))
                    + "\"flr_nm\":%s,\"rcept_dt\":%s,\"rm\":\"\"}"
                    .formatted(FakeHttp.json(corp == null ? "" : corp.name()), FakeHttp.json(d.receiptDate().format(BASIC))));
        }
        FakeHttp.sendJson(exchange, 200, """
                {"status":"000","message":"정상","page_no":%d,"page_count":%d,"total_count":%d,"total_page":%d,"list":[%s]}"""
                .formatted(pageNo, pageCount, matched.size(), totalPage, String.join(",", rows)));
    }

    private void corpCode(HttpExchange exchange) throws IOException {
        record("corp-code", exchange);
        if (mode == Mode.NETWORK_DOWN) {
            FakeHttp.drop(exchange);
            return;
        }
        String error = error(FakeHttp.query(exchange.getRequestURI()).get("crtfc_key"));
        if (error != null) {
            FakeHttp.sendJson(exchange, 200, error);
            return;
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<result>\n");
        for (Corp c : corps) {
            xml.append(entry(c.corpCode(), c.name(), c.stockCode()));
        }
        // 실제 파일처럼 비상장 회사도 섞는다(stock_code 공백 1칸)
        xml.append(entry("00000001", "테스트비상장", " "));
        xml.append("</result>\n");
        FakeHttp.send(exchange, 200, "application/x-msdownload", zip(xml.toString()));
    }

    // 키 · 모드 오류 응답 — 정상이면 null
    private String error(String key) {
        if (mode == Mode.BAD_KEY || !apiKey.equals(key)) {
            return "{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}";
        }
        if (mode == Mode.RATE_LIMIT) {
            return "{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}";
        }
        return null;
    }

    private void record(String api, HttpExchange exchange) {
        requests.computeIfAbsent(api, k -> new AtomicInteger()).incrementAndGet();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) lastQueries.put(api, raw);
    }

    private static String entry(String corpCode, String name, String stockCode) {
        return "  <list>\n    <corp_code>%s</corp_code>\n    <corp_name>%s</corp_name>\n    <corp_eng_name>TEST</corp_eng_name>\n"
                .formatted(corpCode, name)
                + "    <stock_code>%s</stock_code>\n    <modify_date>20261101</modify_date>\n  </list>\n".formatted(stockCode);
    }

    private static byte[] zip(String xml) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        }
    }
}
