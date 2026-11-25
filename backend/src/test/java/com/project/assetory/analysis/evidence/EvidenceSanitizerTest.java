package com.project.assetory.analysis.evidence;

import com.project.assetory.analysis.domain.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 저장 전 값 검증 — event_sources 컬럼 길이(05 §5.2)를 넘는 값이 native insert에서 DB 오류가 되지 않게 막는다
class EvidenceSanitizerTest {

    private static final String TICKER = "000660";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 10, 0);

    @Test
    @DisplayName("정상 값은 그대로 통과한다")
    void valid_passesUnchanged() {
        CollectedEvidence evidence = evidence("제목", "DART", "https://dart.fss.or.kr/x", "20261105000123");

        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, List.of(evidence));

        assertThat(result.accepted()).containsExactly(evidence);
        assertThat(result.excluded()).isZero();
    }

    @Test
    @DisplayName("title 500자 · source_name 100자를 넘으면 그 길이로 자른다 (경계값은 그대로)")
    void longTitleAndSourceName_truncated() {
        CollectedEvidence atLimit = evidence("가".repeat(500), "나".repeat(100), "https://e.com/1", "id-1");
        CollectedEvidence over = evidence("가".repeat(501), "나".repeat(101), "https://e.com/2", "id-2");

        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, List.of(atLimit, over));

        assertThat(result.accepted()).hasSize(2);
        assertThat(result.accepted().get(0)).isEqualTo(atLimit);
        assertThat(result.accepted().get(1).title()).isEqualTo("가".repeat(500));
        assertThat(result.accepted().get(1).sourceName()).isEqualTo("나".repeat(100));
        assertThat(result.accepted().get(1).url()).isEqualTo("https://e.com/2");
        assertThat(result.excluded()).isZero();
    }

    @Test
    @DisplayName("자를 때 이모지(서로게이트 쌍)를 반으로 쪼개지 않는다 — 길이는 문자(코드 포인트) 기준")
    void truncate_keepsSurrogatePairs() {
        String title = "가".repeat(499) + "😀😀";

        CollectedEvidence result = EvidenceSanitizer.sanitize(TICKER,
                List.of(evidence(title, "언론사", "https://e.com/1", "id-1"))).accepted().get(0);

        assertThat(result.title()).isEqualTo("가".repeat(499) + "😀");
        assertThat(result.title().codePointCount(0, result.title().length())).isEqualTo(500);
    }

    @Test
    @DisplayName("url · external_id가 비었거나 길이(1000 · 200)를 넘으면 그 건만 제외한다 — 자르면 링크 · 중복 키가 깨진다")
    void invalidUrlOrExternalId_excluded() {
        CollectedEvidence ok = evidence("정상", "DART", "https://e.com/ok", "ok");
        List<CollectedEvidence> input = List.of(
                ok,
                evidence("빈 url", "DART", " ", "a"),
                evidence("null url", "DART", null, "b"),
                evidence("긴 url", "DART", "https://e.com/" + "u".repeat(1000), "c"),
                evidence("빈 id", "DART", "https://e.com/d", ""),
                evidence("긴 id", "DART", "https://e.com/e", "x".repeat(201)),
                evidence("경계 id", "DART", "https://e.com/" + "u".repeat(1000 - "https://e.com/".length()), "y".repeat(200)));

        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, input);

        assertThat(result.accepted()).extracting(CollectedEvidence::title).containsExactly("정상", "경계 id");
        assertThat(result.excluded()).isEqualTo(5);
    }

    @Test
    @DisplayName("NOT NULL 컬럼(source_type · title · source_name)이 비면 그 건만 제외한다 — 값을 지어내 채우지 않는다")
    void missingRequired_excluded() {
        List<CollectedEvidence> input = List.of(
                new CollectedEvidence(null, "제목", "DART", AT, "https://e.com/1", null, null, "1"),
                evidence(" ", "DART", "https://e.com/2", "2"),
                evidence("제목", null, "https://e.com/3", "3"),
                evidence("제목", "DART", "https://e.com/4", "4"));

        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, input);

        assertThat(result.accepted()).extracting(CollectedEvidence::externalId).containsExactly("4");
        assertThat(result.excluded()).isEqualTo(3);
    }

    @Test
    @DisplayName("snippet(TEXT)은 한도를 넘으면 자르고, NULL은 NULL 그대로 둔다")
    void snippet() {
        CollectedEvidence longSnippet = new CollectedEvidence(SourceType.NEWS, "제목", "언론사", AT, "https://e.com/1",
                "가".repeat(EvidenceSanitizer.SNIPPET_MAX + 1), null, "1");
        CollectedEvidence noSnippet = evidence("제목", "언론사", "https://e.com/2", "2");

        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, List.of(longSnippet, noSnippet));

        assertThat(result.accepted().get(0).snippet()).hasSize(EvidenceSanitizer.SNIPPET_MAX);
        assertThat(result.accepted().get(1).snippet()).isNull();
    }

    @Test
    @DisplayName("빈 목록 · null 원소는 오류 없이 처리한다 (null 원소는 제외 1건)")
    void emptyAndNullElement() {
        assertThat(EvidenceSanitizer.sanitize(TICKER, List.of()).accepted()).isEmpty();

        java.util.ArrayList<CollectedEvidence> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        EvidenceSanitizer.Result result = EvidenceSanitizer.sanitize(TICKER, withNull);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.excluded()).isEqualTo(1);
    }

    private static CollectedEvidence evidence(String title, String sourceName, String url, String externalId) {
        return new CollectedEvidence(SourceType.DISCLOSURE, title, sourceName, null, url, null, null, externalId);
    }
}
