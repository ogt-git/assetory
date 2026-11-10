package com.project.assetory.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 가짜 외부 서버 공용 HTTP 도우미 — 테스트 전용(JDK 내장 HttpServer, 새 라이브러리 없음). 스프링 빈이 아니다
 */
final class FakeHttp {

    private FakeHttp() {
    }

    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String origin(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 쿼리를 디코딩해 이름 → 값 (같은 이름이 여러 번이면 마지막 값) */
    static Map<String, String> query(URI uri) {
        Map<String, String> params = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return params;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return params;
    }

    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** 연결 끊김 모드 — 응답 없이 닫는다(클라이언트에는 I/O 오류) */
    static void drop(HttpExchange exchange) {
        exchange.close();
    }

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** JSON 문자열 값 이스케이프 — 데모 문구에 따옴표 · 역슬래시가 들어가도 깨지지 않게 */
    static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
