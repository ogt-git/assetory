package com.project.assetory.market.ecos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.exception.EcosApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * StatisticSearch 응답 해석 — ECOS 개발 명세서 기준(실응답 미대조, B0 E1 · E2에서 대조).
 * - 정상: StatisticSearch.row[] → (TIME, DATA_VALUE). TIME은 일 주기 yyyyMMdd 원본 그대로 날짜로 — 없는 날짜를 만들지 않는다
 * - RESULT.CODE: INFO-200은 0건 정상, 그 밖은 EcosApiException(EcosErrorType.fromCode) — 원문 메시지는 버린다
 * - 행 하나라도 TIME · DATA_VALUE를 해석할 수 없거나 다른 항목코드면 INVALID_RESPONSE — 응답 전체를 실패로 본다(그 지표 전체 실패, B4 확정 사항 5)
 */
public final class EcosResponseParser {

    static final String NO_DATA = "INFO-200";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DAILY = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private EcosResponseParser() {
    }

    public static List<DatedValue> parse(String body, String itemCode) {
        JsonNode root = readTree(body);
        JsonNode result = root.path("RESULT");
        if (result.isObject()) {
            String code = text(result, "CODE");
            if (NO_DATA.equals(code)) return List.of();
            throw new EcosApiException(EcosErrorType.fromCode(code), code);
        }
        JsonNode rows = root.path("StatisticSearch").path("row");
        if (!rows.isArray()) {
            throw invalid();
        }
        List<DatedValue> values = new ArrayList<>();
        for (JsonNode row : rows) {
            String item = text(row, "ITEM_CODE1");
            if (item != null && !item.equals(itemCode)) {
                throw invalid();
            }
            values.add(new DatedValue(date(text(row, "TIME")), decimal(text(row, "DATA_VALUE"))));
        }
        return List.copyOf(values);
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) throw invalid();
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (JsonProcessingException e) {
            // 파서 메시지에 본문 일부가 들어갈 수 있어 넘기지 않는다
            throw invalid();
        }
    }

    private static LocalDate date(String time) {
        if (time == null) throw invalid();
        try {
            return LocalDate.parse(time, DAILY);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) throw invalid();
        try {
            return new BigDecimal(value.strip().replace(",", ""));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static EcosApiException invalid() {
        return new EcosApiException(EcosErrorType.INVALID_RESPONSE, null);
    }
}
