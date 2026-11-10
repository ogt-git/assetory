package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.NewsEvidenceRetentionJob;
import com.project.assetory.analysis.service.NewsEvidenceRetentionService;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 뉴스 근거 보관 기한 삭제 (B3 확정 사항 8) — NEWS AND created_at < 기준 시각(지금 − days) 삭제, evidence_count는 그대로.
 * 시각은 2020년으로 둔다 — 같은 DB를 쓰는 다른 테스트의 뉴스 행(지금 시각)이 기준 시각에 걸리지 않게
 */
class NewsEvidenceRetentionIntegrationTest extends IntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2020, 1, 31, 3, 30);
    private static final int DAYS = 20; // 기본 설정값
    private static final LocalDateTime THRESHOLD = NOW.minusDays(DAYS); // 2020-01-11 03:30

    @Autowired NewsEvidenceRetentionService service;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired EventSourceRepository eventSourceRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("1 · 2 · 3. 기준 시각을 넘긴 NEWS만 지우고, 기준 안 된 NEWS · 공시 · 거시지표는 기간과 관계없이 남긴다 — evidence_count 그대로")
    void purge_oldNewsOnly() {
        Long analysisId = analysisWithCount(5);
        insert(analysisId, SourceType.NEWS, "old-news", THRESHOLD.minusDays(1));
        insert(analysisId, SourceType.NEWS, "fresh-news", THRESHOLD.plusHours(1));
        insert(analysisId, SourceType.DISCLOSURE, "20200101900001", THRESHOLD.minusDays(30));
        insert(analysisId, SourceType.MACRO, "old-macro", THRESHOLD.minusDays(30));
        insert(analysisId, SourceType.NEWS, "older-news", THRESHOLD.minusDays(10));

        int deleted = service.purgeAt(NOW);

        assertThat(deleted).isEqualTo(2);
        assertThat(externalIds(analysisId)).containsExactlyInAnyOrder("fresh-news", "20200101900001", "old-macro");
        assertThat(analysisRepository.findById(analysisId).orElseThrow().getEvidenceCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("4. 같은 시각에 두 번 실행 → 두 번째는 0건 (멱등)")
    void purge_idempotent() {
        Long analysisId = analysisWithCount(1);
        insert(analysisId, SourceType.NEWS, "old-news", THRESHOLD.minusDays(1));

        assertThat(service.purgeAt(NOW)).isEqualTo(1);
        assertThat(service.purgeAt(NOW)).isZero();
    }

    @Test
    @DisplayName("6. 경계: created_at이 정확히 기준 시각인 행은 남긴다(< 비교) — 1초 전은 지운다")
    void purge_boundary() {
        Long analysisId = analysisWithCount(2);
        insert(analysisId, SourceType.NEWS, "exactly-threshold", THRESHOLD);
        insert(analysisId, SourceType.NEWS, "one-second-before", THRESHOLD.minusSeconds(1));

        assertThat(service.purgeAt(NOW)).isEqualTo(1);
        assertThat(externalIds(analysisId)).containsExactly("exactly-threshold");
    }

    @Test
    @DisplayName("5. test 프로필은 삭제 배치가 꺼져 있다 — 스케줄 빈이 등록되지 않는다")
    void jobDisabledInTestProfile() {
        assertThat(context.getBeanNamesForType(NewsEvidenceRetentionJob.class)).isEmpty();
    }

    private Long analysisWithCount(int evidenceCount) {
        StockEventAnalysis analysis = analysisRepository.saveAndFlush(StockEventAnalysis.create(uniqueTicker(), "테스트 종목",
                LocalDate.of(2020, 1, 2), new BigDecimal("-6.1")));
        // 수집 시점 기록값 — 삭제 뒤에도 바뀌지 않아야 한다 (B1 확정 사항 3)
        jdbc.update("update stock_event_analyses set evidence_count = ? where id = ?", evidenceCount, analysis.getId());
        return analysis.getId();
    }

    private void insert(Long analysisId, SourceType type, String externalId, LocalDateTime createdAt) {
        tx.executeWithoutResult(s -> eventSourceRepository.insertIfAbsent(analysisId, type.name(), "테스트 근거 " + externalId,
                "테스트 출처", type == SourceType.DISCLOSURE ? null : createdAt, "https://example.com/" + externalId,
                null, null, externalId, createdAt));
    }

    private List<String> externalIds(Long analysisId) {
        return jdbc.queryForList("select external_id from event_sources where analysis_id = ?", String.class, analysisId);
    }

    // 테스트 전용 가짜 종목코드 (다른 테스트와 겹치지 않게 매번 새로)
    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
