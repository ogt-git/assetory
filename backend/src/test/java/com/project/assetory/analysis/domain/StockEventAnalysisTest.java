package com.project.assetory.analysis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 근거 수집 완료 상태 전이 (B1 확정 사항 2) — 0건 NO_EVIDENCE(정상 결과) · 1건 이상 COLLECTING(요약 대기), PENDING에서만
class StockEventAnalysisTest {

    @Test
    @DisplayName("근거 0건이면 NO_EVIDENCE, 건수 0")
    void zero_noEvidence() {
        StockEventAnalysis analysis = pending();

        analysis.completeCollection(0);

        assertThat(analysis.getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(analysis.getEvidenceCount()).isZero();
    }

    @Test
    @DisplayName("근거 1건 이상이면 COLLECTING, 건수 기록 — 요약 전이라 요약 · 근거 상태는 NULL 그대로")
    void some_collecting() {
        StockEventAnalysis analysis = pending();

        analysis.completeCollection(3);

        assertThat(analysis.getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(analysis.getEvidenceCount()).isEqualTo(3);
        assertThat(analysis.getAiSummary()).isNull();
        assertThat(analysis.getEvidenceStatus()).isNull();
        assertThat(analysis.getAnalyzedAt()).isNull();
    }

    @Test
    @DisplayName("PENDING이 아니면 다시 바꾸지 않는다 · 음수 건수는 거부")
    void guards() {
        StockEventAnalysis analysis = pending();
        analysis.completeCollection(1);

        assertThatThrownBy(() -> analysis.completeCollection(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().completeCollection(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(analysis.getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    private static StockEventAnalysis pending() {
        return StockEventAnalysis.create("000660", "테스트 종목", LocalDate.of(2026, 11, 5), new BigDecimal("-6.1"));
    }
}
