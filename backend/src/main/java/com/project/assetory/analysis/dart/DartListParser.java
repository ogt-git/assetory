package com.project.assetory.analysis.dart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.analysis.exception.DartApiException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * 공시검색(list.json) 응답 해석 — 필드명은 OpenDART 개발가이드 기준(실응답 미대조, B0 Phase 3에서 대조).
 * - 000: 페이지 읽기 / 013: 0건 정상
 * - 그 밖의 status: DartApiException(DartErrorType.fromStatus) — 응답 원문 메시지는 버린다
 * - 필수 값(status · list · rcept_no 14자리 · report_nm · rcept_dt) 누락 · 형식 오류: INVALID_RESPONSE.
 *   한 행이라도 해석할 수 없으면 페이지 전체를 실패로 본다 — 형식이 바뀐 응답을 일부만 믿지 않는다 (수집기 전부 성공 아니면 실패, 확정 사항 7)
 */
public final class DartListParser {

    static final String STATUS_OK = "000";
    static final String STATUS_NO_DATA = "013";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private DartListParser() {
    }

    public static DartListPage parse(String body) {
        JsonNode root = readTree(body);
        String status = text(root, "status");
        if (status == null) {
            throw new DartApiException(DartErrorType.INVALID_RESPONSE, null);
        }
        if (STATUS_NO_DATA.equals(status)) {
            return DartListPage.empty();
        }
        if (!STATUS_OK.equals(status)) {
            throw new DartApiException(DartErrorType.fromStatus(status), status);
        }
        JsonNode list = root.path("list");
        if (!list.isArray()) {
            throw invalid(status);
        }
        List<DartDisclosure> items = new ArrayList<>();
        for (JsonNode row : list) {
            items.add(disclosure(row, status));
        }
        return new DartListPage(requiredInt(root, "page_no", status), requiredInt(root, "total_page", status),
                requiredInt(root, "total_count", status), List.copyOf(items));
    }

    private static DartDisclosure disclosure(JsonNode row, String status) {
        String receiptNo = text(row, "rcept_no");
        String reportName = text(row, "report_nm");
        LocalDate receiptDate = date(text(row, "rcept_dt"));
        if (!DartReceiptNo.isValid(receiptNo) || reportName == null || reportName.isBlank() || receiptDate == null) {
            throw invalid(status);
        }
        return new DartDisclosure(text(row, "corp_code"), text(row, "corp_name"), text(row, "stock_code"), reportName,
                receiptNo, text(row, "flr_nm"), receiptDate, text(row, "rm"));
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            throw new DartApiException(DartErrorType.INVALID_RESPONSE, null);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new DartApiException(DartErrorType.INVALID_RESPONSE, null);
            }
            return root;
        } catch (JsonProcessingException e) {
            // 파서 메시지에 본문 일부가 들어갈 수 있어 cause로 넘기지 않는다
            throw new DartApiException(DartErrorType.INVALID_RESPONSE, null);
        }
    }

    private static int requiredInt(JsonNode node, String field, String status) {
        JsonNode value = node.path(field);
        if (value.isInt()) return value.intValue();
        if (value.isTextual() && value.asText().matches("\\d+")) return Integer.parseInt(value.asText());
        throw invalid(status);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static LocalDate date(String yyyyMMdd) {
        if (yyyyMMdd == null) return null;
        try {
            return LocalDate.parse(yyyyMMdd, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static DartApiException invalid(String status) {
        return new DartApiException(DartErrorType.INVALID_RESPONSE, status);
    }
}
