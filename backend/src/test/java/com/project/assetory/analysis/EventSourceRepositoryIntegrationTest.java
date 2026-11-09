package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// docs/05 §5.2 event_sources — 공유 분석의 근거. 이번 범위(B1 Phase 1)는 저장 · 조회까지
class EventSourceRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    @Autowired EventSourceRepository repository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (analysis_id, external_id) 근거를 두 번 넣으면 1건 — 예외 없이 무시된다")
    void duplicateExternalId_ignored() {
        Long analysisId = newAnalysis();

        insert(analysisId, SourceType.DISCLOSURE, "20261105000123", null);
        insert(analysisId, SourceType.DISCLOSURE, "20261105000123", null);

        assertThat(repository.countByAnalysisId(analysisId)).isEqualTo(1);
    }

    @Test
    @DisplayName("분석이 다르면 같은 external_id도 따로 저장된다")
    void sameExternalIdOtherAnalysis_allowed() {
        Long first = newAnalysis();
        Long second = newAnalysis();

        insert(first, SourceType.NEWS, "https://news.example.com/a/1", LocalDateTime.of(2026, 11, 5, 10, 0));
        insert(second, SourceType.NEWS, "https://news.example.com/a/1", LocalDateTime.of(2026, 11, 5, 10, 0));

        assertThat(repository.countByAnalysisId(first)).isEqualTo(1);
        assertThat(repository.countByAnalysisId(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("published_at NULL(시각 미제공 공시)로 저장 · 조회된다 — 날짜를 추측해 채우지 않는다 (#8)")
    void publishedAtNull() {
        Long analysisId = newAnalysis();

        insert(analysisId, SourceType.DISCLOSURE, "20261105000456", null);

        EventSource found = repository.findAllByAnalysisIdOrderByPublishedAt(analysisId).get(0);
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getSourceType()).isEqualTo(SourceType.DISCLOSURE);
        assertThat(found.getExternalId()).isEqualTo("20261105000456");
        assertThat(found.getSnippet()).isNull();
        assertThat(found.getRelevanceRank()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select published_at from event_sources where analysis_id = ?",
                LocalDateTime.class, analysisId)).isNull();
    }

    @Test
    @DisplayName("분석별 목록은 published_at 오름차순, NULL은 뒤, 같으면 id 순 — 다른 분석 근거는 섞이지 않는다")
    void listOrder_publishedAtAscNullsLast() {
        Long analysisId = newAnalysis();
        Long other = newAnalysis();
        insert(analysisId, SourceType.DISCLOSURE, "null-1", null);
        insert(analysisId, SourceType.NEWS, "late", LocalDateTime.of(2026, 11, 5, 17, 0));
        insert(analysisId, SourceType.NEWS, "early", LocalDateTime.of(2026, 11, 4, 9, 0));
        insert(analysisId, SourceType.DISCLOSURE, "null-2", null);
        insert(other, SourceType.NEWS, "other", LocalDateTime.of(2026, 11, 1, 9, 0));

        List<String> order = repository.findAllByAnalysisIdOrderByPublishedAt(analysisId).stream()
                .map(EventSource::getExternalId).toList();

        assertThat(order).containsExactly("early", "late", "null-1", "null-2");
    }

    @Test
    @DisplayName("길이 경계값(title 500 · source_name 100 · url 1000 · external_id 200, 한글 포함)이 그대로 저장된다")
    void columnLengthBoundaries() {
        Long analysisId = newAnalysis();
        String title = "가".repeat(500);
        String sourceName = "나".repeat(100);
        String url = "https://e.com/" + "u".repeat(1000 - "https://e.com/".length());
        String externalId = "x".repeat(200);

        tx.executeWithoutResult(s -> repository.insertIfAbsent(analysisId, SourceType.NEWS.name(), title, sourceName,
                LocalDateTime.of(2026, 11, 5, 12, 0), url, "스니펫", 1, externalId, LocalDateTime.now()));

        EventSource found = repository.findAllByAnalysisIdOrderByPublishedAt(analysisId).get(0);
        assertThat(found.getTitle()).isEqualTo(title);
        assertThat(found.getSourceName()).isEqualTo(sourceName);
        assertThat(found.getUrl()).isEqualTo(url).hasSize(1000);
        assertThat(found.getExternalId()).isEqualTo(externalId);
        assertThat(found.getSnippet()).isEqualTo("스니펫");
        assertThat(found.getRelevanceRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("컬럼 타입 · 길이 · NULL 허용이 05 §5.2와 같다")
    void columnsMatchErd() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, data_type, character_maximum_length, is_nullable
                from information_schema.columns
                where table_schema = database() and table_name = 'event_sources'""");
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        columns.forEach(c -> byName.put(((String) c.get("column_name")).toLowerCase(), c));

        assertColumn(byName, "analysis_id", "bigint", null, "NO");
        // Hibernate 6은 @Enumerated(STRING)을 MySQL 네이티브 ENUM으로 만든다(값은 알파벳순) — 05 §5.2 "ENUM"과 같다
        assertColumn(byName, "source_type", "enum", null, "NO");
        assertThat(jdbc.queryForObject("""
                select column_type from information_schema.columns
                where table_schema = database() and table_name = 'event_sources' and column_name = 'source_type'""",
                String.class)).isEqualTo("enum('DISCLOSURE','MACRO','NEWS')");
        assertColumn(byName, "title", "varchar", 500L, "NO");
        assertColumn(byName, "source_name", "varchar", 100L, "NO");
        assertColumn(byName, "published_at", "datetime", null, "YES");
        assertColumn(byName, "url", "varchar", 1000L, "NO");
        assertColumn(byName, "snippet", "text", null, "YES");
        assertColumn(byName, "relevance_rank", "int", null, "YES");
        assertColumn(byName, "external_id", "varchar", 200L, "NO");
        assertColumn(byName, "created_at", "datetime", null, "NO");
        assertColumn(byName, "updated_at", "datetime", null, "NO");
    }

    @Test
    @DisplayName("UNIQUE (analysis_id, external_id) · 인덱스 (analysis_id, published_at)가 이름 그대로 있다 (05 §10)")
    void indexes() {
        assertThat(indexColumns("uk_event_sources_analysis_external")).containsExactly("analysis_id", "external_id");
        assertThat(indexColumns("idx_event_sources_analysis_published")).containsExactly("analysis_id", "published_at");
    }

    private void assertColumn(Map<String, Map<String, Object>> byName, String name, String type, Long length, String nullable) {
        Map<String, Object> column = byName.get(name);
        assertThat(column).as(name).isNotNull();
        assertThat(column.get("data_type")).as(name + " type").isEqualTo(type);
        if (length != null) {
            assertThat(((Number) column.get("character_maximum_length")).longValue()).as(name + " length").isEqualTo(length);
        }
        assertThat(column.get("is_nullable")).as(name + " nullable").isEqualTo(nullable);
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'event_sources' and index_name = ?
                order by seq_in_index""", String.class, indexName);
    }

    private void insert(Long analysisId, SourceType type, String externalId, LocalDateTime publishedAt) {
        tx.executeWithoutResult(s -> repository.insertIfAbsent(analysisId, type.name(), "테스트 근거 " + externalId,
                type == SourceType.DISCLOSURE ? "DART" : "테스트 언론사", publishedAt,
                "https://example.com/" + externalId.hashCode(), null, null, externalId, LocalDateTime.now()));
    }

    private Long newAnalysis() {
        return analysisRepository.saveAndFlush(
                StockEventAnalysis.create(uniqueTicker(), "테스트 종목", DATE, new BigDecimal("-6.1"))).getId();
    }

    // 테스트 전용 가짜 종목코드 (다른 테스트와 겹치지 않게 매번 새로)
    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
