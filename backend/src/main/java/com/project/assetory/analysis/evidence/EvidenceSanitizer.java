package com.project.assetory.analysis.evidence;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 저장 전 값 검증 — event_sources 컬럼(05 §5.2)에 맞춘다. native insert라 길이를 넘으면 DB 오류로 분석 전체가 롤백되므로 미리 막는다.
 * - title(500) · source_name(100): 넘는 부분을 자른다(문자 = 코드 포인트 기준, MySQL utf8mb4 VARCHAR 길이와 같은 단위)
 * - snippet(TEXT, 64KB): SNIPPET_MAX 문자로 자른다. NULL은 그대로
 * - url(1000) · external_id(200): 비었거나 넘으면 그 건만 제외 — 자르면 링크가 깨지거나 중복 기준이 달라진다
 * - source_type · title · source_name이 비면 제외 — 값을 지어내 채우지 않는다 (AGENTS 5-C)
 * 제외 로그에는 종목코드 · 소스 유형 · 사유만 남긴다(값 원문 금지).
 */
@Slf4j
public final class EvidenceSanitizer {

    static final int TITLE_MAX = 500;
    static final int SOURCE_NAME_MAX = 100;
    static final int URL_MAX = 1000;
    static final int EXTERNAL_ID_MAX = 200;
    /** TEXT 최대 65,535바이트 ÷ utf8mb4 최대 4바이트 → 16,000자면 항상 들어간다 */
    static final int SNIPPET_MAX = 16_000;

    private EvidenceSanitizer() {
    }

    /** @param excluded 제외한 건수(null 원소 포함) */
    public record Result(List<CollectedEvidence> accepted, int excluded) {
    }

    public static Result sanitize(String ticker, List<CollectedEvidence> evidences) {
        List<CollectedEvidence> accepted = new ArrayList<>();
        int excluded = 0;
        for (CollectedEvidence e : evidences) {
            String reason = rejectReason(e);
            if (reason != null) {
                excluded++;
                log.warn("근거 제외 ticker={} type={} reason={}", ticker, e == null ? null : e.type(), reason);
                continue;
            }
            accepted.add(new CollectedEvidence(e.type(), truncate(e.title(), TITLE_MAX), truncate(e.sourceName(), SOURCE_NAME_MAX),
                    e.publishedAt(), e.url(), e.snippet() == null ? null : truncate(e.snippet(), SNIPPET_MAX),
                    e.relevanceRank(), e.externalId()));
        }
        return new Result(List.copyOf(accepted), excluded);
    }

    private static String rejectReason(CollectedEvidence e) {
        if (e == null) return "null";
        if (e.type() == null) return "source_type 없음";
        if (isBlank(e.title())) return "title 없음";
        if (isBlank(e.sourceName())) return "source_name 없음";
        if (isBlank(e.url())) return "url 없음";
        if (length(e.url()) > URL_MAX) return "url 길이 초과";
        if (isBlank(e.externalId())) return "external_id 없음";
        if (length(e.externalId()) > EXTERNAL_ID_MAX) return "external_id 길이 초과";
        return null;
    }

    private static String truncate(String value, int max) {
        if (length(value) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
