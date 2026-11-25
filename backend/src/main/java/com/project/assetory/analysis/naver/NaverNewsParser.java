package com.project.assetory.analysis.naver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.analysis.exception.NaverApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * 뉴스 검색 응답 해석 — 필드명은 NAVER API HUB 문서 기준(B0 실호출로 필드 대조 완료, 2026-11-16).
 * - 2xx 본문: total · start · display · items[title, originallink, link, description, pubDate]. 값은 원문 그대로
 * - 필수 값(total · start · display · items 배열 · pubDate) 누락 · 형식 오류 → INVALID_RESPONSE.
 *   한 건이라도 해석할 수 없으면 응답 전체를 실패로 본다 — 형식이 바뀐 응답을 일부만 믿지 않는다 (B3 확정 사항 7, B2와 같은 원칙)
 * - 실패 본문은 errorCode만 꺼낸다(원문 메시지는 버림). 분류는 HTTP 상태로 한다
 */
public final class NaverNewsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NaverNewsParser() {
    }

    public static NaverNewsSearchResult parse(String body) {
        JsonNode root = readTree(body);
        if (root == null) {
            throw invalid();
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw invalid();
        }
        List<NaverNewsItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(item(item));
        }
        return new NaverNewsSearchResult(requiredLong(root, "total"), (int) requiredLong(root, "start"),
                (int) requiredLong(root, "display"), List.copyOf(result));
    }

    /** 실패 본문의 errorCode — 형식을 확인하지 못해 없거나 JSON이 아니면 null */
    public static String errorCode(String body) {
        JsonNode root = readTree(body);
        if (root == null) return null;
        JsonNode code = root.path("errorCode");
        if (code.isMissingNode()) code = root.path("error").path("errorCode");
        return code.isMissingNode() || code.isNull() ? null : code.asText();
    }

    private static NaverNewsItem item(JsonNode node) {
        try {
            return new NaverNewsItem(text(node, "title"), text(node, "originallink"), text(node, "link"),
                    text(node, "description"), NaverPubDate.toKst(text(node, "pubDate")));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException e) {
            // 파서 메시지에 본문 일부가 들어갈 수 있어 넘기지 않는다
            return null;
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isTextual() && value.asText().matches("\\d+")) return Long.parseLong(value.asText());
        throw invalid();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static NaverApiException invalid() {
        return new NaverApiException(NaverErrorType.INVALID_RESPONSE, null, null);
    }
}
