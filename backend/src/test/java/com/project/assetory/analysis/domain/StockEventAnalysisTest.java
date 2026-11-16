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

    @Test
    @DisplayName("판정 반영 — 수집이 끝난 분석(NO_EVIDENCE · COLLECTING)에 지수 변동률 · 초과 변동 · 근거 상태를 저장, 상태 · 건수는 그대로")
    void applyEvidenceStatus_afterCollection() {
        StockEventAnalysis noEvidence = pending();
        noEvidence.completeCollection(0);
        noEvidence.applyEvidenceStatus(new BigDecimal("-4.0000"),
                new EvidenceStatusCalculator.Result(new BigDecimal("-2.1000"), EvidenceStatus.NOT_FOUND));

        assertThat(noEvidence.getMarketChangeRate()).isEqualTo(new BigDecimal("-4.0000"));
        assertThat(noEvidence.getExcessRate()).isEqualTo(new BigDecimal("-2.1000"));
        assertThat(noEvidence.getEvidenceStatus()).isEqualTo(EvidenceStatus.NOT_FOUND);
        assertThat(noEvidence.getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(noEvidence.getEvidenceCount()).isZero();
        assertThat(noEvidence.getMarketIndexCode()).isNull();

        StockEventAnalysis collecting = pending();
        collecting.completeCollection(2);
        collecting.applyEvidenceStatus(null, new EvidenceStatusCalculator.Result(null, EvidenceStatus.RELATED_FOUND));

        assertThat(collecting.getMarketChangeRate()).isNull();
        assertThat(collecting.getExcessRate()).isNull();
        assertThat(collecting.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
        assertThat(collecting.getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("판정 반영 — 수집 전(PENDING)이거나 결과가 없으면 거부")
    void applyEvidenceStatus_guards() {
        StockEventAnalysis analysis = pending();
        EvidenceStatusCalculator.Result result = new EvidenceStatusCalculator.Result(null, EvidenceStatus.NOT_FOUND);

        assertThatThrownBy(() -> analysis.applyEvidenceStatus(null, result)).isInstanceOf(IllegalStateException.class);
        assertThat(analysis.getEvidenceStatus()).isNull();

        analysis.completeCollection(0);
        assertThatThrownBy(() -> analysis.applyEvidenceStatus(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analysis.applyEvidenceStatus(null, new EvidenceStatusCalculator.Result(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StockEventAnalysis pending() {
        return StockEventAnalysis.create("000660", "테스트 종목", LocalDate.of(2026, 11, 5), new BigDecimal("-6.1"));
    }
}
