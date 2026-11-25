package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §5.1 stock_event_analyses — 종목 · 날짜 단위 공유 분석. 이번 범위(A3)는 PENDING 생성까지
class StockEventAnalysisRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    @Autowired StockEventAnalysisRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (종목, 날짜) 분석을 두 번 저장하면 UNIQUE 위반 — 공유 분석 멱등성")
    void duplicateTickerDate_rejected() {
        String ticker = uniqueTicker();
        repository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal("-6.1234")));

        assertThatThrownBy(() -> repository.saveAndFlush(
                StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal("-6.1234"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 종목이라도 날짜가 다르면 따로 저장된다")
    void sameTickerOtherDate_allowed() {
        String ticker = uniqueTicker();
        repository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal("5.5")));
        repository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE.plusDays(1), new BigDecimal("-5.5")));

        assertThat(repository.findByTickerAndEventDate(ticker, DATE)).isPresent();
        assertThat(repository.findByTickerAndEventDate(ticker, DATE.plusDays(1))).isPresent();
    }

    @Test
    @DisplayName("생성 직후 PENDING · 근거 0건, 종목 사실 값은 그대로 저장된다")
    void createdPending() {
        String ticker = uniqueTicker();
        repository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal("-6.1234")));

        StockEventAnalysis found = repository.findByTickerAndEventDate(ticker, DATE).orElseThrow();
        assertThat(found.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(found.getEvidenceCount()).isZero();
        assertThat(found.getTicker()).isEqualTo(ticker);
        assertThat(found.getStockName()).isEqualTo("테스트 종목");
        assertThat(found.getEventDate()).isEqualTo(DATE);
        assertThat(found.getChangeRate()).isEqualByComparingTo("-6.1234");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING 단계에서 채울 수 없는 8개 컬럼은 NULL이다 (추측 · 0 채움 없음, 5-C)")
    void pendingColumnsNull() {
        String ticker = uniqueTicker();
        repository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal("7")));

        StockEventAnalysis found = repository.findByTickerAndEventDate(ticker, DATE).orElseThrow();
        assertThat(found.getEvidenceStatus()).isNull();
        assertThat(found.getMarketIndexCode()).isNull();
        assertThat(found.getMarketChangeRate()).isNull();
        assertThat(found.getExcessRate()).isNull();
        assertThat(found.getAiSummary()).isNull();
        assertThat(found.getAnalyzedAt()).isNull();
        assertThat(found.getLlmPromptTokens()).isNull();
        assertThat(found.getLlmCompletionTokens()).isNull();

        // DB 행에서도 NULL (엔티티 기본값이 아니라 실제 저장값)
        Map<String, Object> row = jdbc.queryForMap("""
                select evidence_status, market_index_code, market_change_rate, excess_rate, ai_summary,
                       analyzed_at, llm_prompt_tokens, llm_completion_tokens, evidence_count, analysis_status
                from stock_event_analyses where ticker = ? and event_date = ?""", ticker, DATE);
        assertThat(row).containsEntry("evidence_status", null).containsEntry("market_index_code", null)
                .containsEntry("market_change_rate", null).containsEntry("excess_rate", null)
                .containsEntry("ai_summary", null).containsEntry("analyzed_at", null)
                .containsEntry("llm_prompt_tokens", null).containsEntry("llm_completion_tokens", null)
                .containsEntry("analysis_status", "PENDING");
        assertThat(((Number) row.get("evidence_count")).intValue()).isZero();
    }

    @Test
    @DisplayName("없는 (종목, 날짜)는 빈 결과")
    void findMissing_empty() {
        assertThat(repository.findByTickerAndEventDate(uniqueTicker(), DATE)).isEmpty();
    }

    @Test
    @DisplayName("재시도 조회용 인덱스 (analysis_status, created_at)와 UNIQUE (ticker, event_date)가 이름 그대로 있다")
    void indexes() {
        assertThat(indexColumns("idx_stock_event_analyses_status_created")).containsExactly("analysis_status", "created_at");
        assertThat(indexColumns("uk_stock_event_analyses_ticker_date")).containsExactly("ticker", "event_date");
    }

    @Test
    @DisplayName("ai_summary는 TEXT 컬럼이다")
    void aiSummaryText() {
        String type = jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = database() and table_name = 'stock_event_analyses' and column_name = 'ai_summary'""",
                String.class);
        assertThat(type).isEqualTo("text");
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'stock_event_analyses' and index_name = ?
                order by seq_in_index""", String.class, indexName);
    }

    // 테스트 전용 가짜 종목코드 (다른 테스트와 겹치지 않게 매번 새로)
    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
