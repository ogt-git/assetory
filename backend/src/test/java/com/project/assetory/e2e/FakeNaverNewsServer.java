package com.project.assetory.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜 NAVER API HUB 뉴스 검색 서버 — 테스트 · fake 프로필 전용. 실제 NaverNewsApiClient를 HTTP 수준에서 그대로 거치게 한다.
 * 응답 형식은 B3 픽스처와 같은 문서 기준(실응답 미대조). 기사 · 언론사 · 링크는 모두 지어낸 더미 값이다.
 * - /search/v1/news: query(디코딩해서 매칭)별 등록 기사를 pubDate 최신순으로, start · display로 나눈다
 * - X-NCP-APIGW-API-KEY-ID · X-NCP-APIGW-API-KEY가 등록한 더미 키와 다르면 401
 * 모드: NORMAL / UNAUTHORIZED(401) / RATE_LIMIT(429) / NETWORK_DOWN(응답 없이 끊음)
 */
public final class FakeNaverNewsServer {

    public enum Mode { NORMAL, UNAUTHORIZED, RATE_LIMIT, NETWORK_DOWN }

    private record Article(String query, String title, String originallink, String link, String description, ZonedDateTime pubDate) {}

    /** 네이버 pubDate 형식(일자 두 자리) — 예: "Thu, 05 Nov 2026 16:20:00 +0900" */
    private static final DateTimeFormatter PUB_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final HttpServer server;
    private final String clientId;
    private final String clientSecret;
    private volatile Mode mode = Mode.NORMAL;
    private final List<Article> articles = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastQuery;

    public FakeNaverNewsServer(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.server = FakeHttp.start();
        server.createContext("/search/v1/news", this::news);
    }

    /** assetory.naver.base-url에 넣을 주소 */
    public String baseUrl() {
        return FakeHttp.origin(server);
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- 시나리오 설정 ----------

    public void reset() {
        mode = Mode.NORMAL;
        articles.clear();
        requests.set(0);
        lastQuery = null;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    /** @param query 이 검색어로 찾았을 때 나오는 기사 (수집기는 종목명으로 검색한다) */
    public void article(String query, String title, String originallink, String link, String description, ZonedDateTime pubDate) {
        articles.add(new Article(query, title, originallink, link, description, pubDate));
    }

    public int requests() {
        return requests.get();
    }

    /** 마지막 요청의 query(디코딩 후) — 한글 · 공백 인코딩 확인용 */
    public String lastQuery() {
        return lastQuery;
    }

    // ---------- 핸들러 ----------

    private void news(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (mode == Mode.NETWORK_DOWN) {
            FakeHttp.drop(exchange);
            return;
        }
        String id = exchange.getRequestHeaders().getFirst("X-NCP-APIGW-API-KEY-ID");
        String secret = exchange.getRequestHeaders().getFirst("X-NCP-APIGW-API-KEY");
        if (mode == Mode.UNAUTHORIZED || !clientId.equals(id) || !clientSecret.equals(secret)) {
            FakeHttp.sendJson(exchange, 401, "{\"errorMessage\":\"테스트 전용 인증 실패\",\"errorCode\":\"024\"}");
            return;
        }
        if (mode == Mode.RATE_LIMIT) {
            FakeHttp.sendJson(exchange, 429, "{\"errorMessage\":\"테스트 전용 한도 초과\",\"errorCode\":\"012\"}");
            return;
        }
        Map<String, String> q = FakeHttp.query(exchange.getRequestURI());
        lastQuery = q.get("query");
        List<Article> matched = new ArrayList<>(articles.stream().filter(a -> a.query().equals(lastQuery)).toList());
        matched.sort(Comparator.comparing(Article::pubDate).reversed());
        int start = Integer.parseInt(q.getOrDefault("start", "1"));
        int display = Integer.parseInt(q.getOrDefault("display", "10"));
        List<String> items = new ArrayList<>();
        for (int i = start - 1; i < Math.min(matched.size(), start - 1 + display); i++) {
            Article a = matched.get(i);
            items.add("{\"title\":%s,\"originallink\":%s,\"link\":%s,\"description\":%s,\"pubDate\":%s}".formatted(
                    FakeHttp.json(a.title()), FakeHttp.json(a.originallink()), FakeHttp.json(a.link()),
                    FakeHttp.json(a.description()), FakeHttp.json(a.pubDate().format(PUB_DATE))));
        }
        FakeHttp.sendJson(exchange, 200, """
                {"lastBuildDate":"Thu, 05 Nov 2026 18:40:00 +0900","total":%d,"start":%d,"display":%d,"items":[%s]}"""
                .formatted(matched.size(), start, items.size(), String.join(",", items)));
    }
}
