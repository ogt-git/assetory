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

    @Test
    @DisplayName("시장 구분 — 비어 있을 때만 넣고, 이미 있으면 덮어쓰지 않는다 (null은 무시)")
    void assignMarketIndex() {
        StockEventAnalysis analysis = pending();

        analysis.assignMarketIndex(null);
        assertThat(analysis.getMarketIndexCode()).isNull();

        analysis.assignMarketIndex(com.project.assetory.market.domain.IndicatorCode.KOSDAQ);
        assertThat(analysis.getMarketIndexCode()).isEqualTo("KOSDAQ");

        analysis.assignMarketIndex(com.project.assetory.market.domain.IndicatorCode.KOSPI);
        assertThat(analysis.getMarketIndexCode()).isEqualTo("KOSDAQ");
    }

    @Test
    @DisplayName("요약 생략(Q-3) — COLLECTING에서만 SUMMARY_SKIPPED, 근거 건수 · 근거 상태는 그대로")
    void skipSummary_fromCollecting() {
        StockEventAnalysis analysis = pending();
        analysis.completeCollection(2);
        analysis.applyEvidenceStatus(null, new EvidenceStatusCalculator.Result(null, EvidenceStatus.RELATED_FOUND));

        analysis.skipSummary();

        assertThat(analysis.getAnalysisStatus()).isEqualTo(AnalysisStatus.SUMMARY_SKIPPED);
        assertThat(analysis.getEvidenceCount()).isEqualTo(2);
        assertThat(analysis.getEvidenceStatus()).isEqualTo(EvidenceStatus.RELATED_FOUND);
        assertThat(analysis.getAiSummary()).isNull();
        assertThat(analysis.getAnalyzedAt()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}에서는 거부")
    @DisplayName("요약 생략 — COLLECTING이 아닌 상태에서 부르면 예외, 상태는 그대로")
    @org.junit.jupiter.params.provider.EnumSource(value = AnalysisStatus.class, names = "COLLECTING",
            mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void skipSummary_guards(AnalysisStatus status) {
        StockEventAnalysis analysis = pending();
        // 테스트 전용 — 아직 전이 메서드가 없는 상태(ANALYZED · FAILED 등)를 직접 만든다
        org.springframework.test.util.ReflectionTestUtils.setField(analysis, "analysisStatus", status);

        assertThatThrownBy(analysis::skipSummary).isInstanceOf(IllegalStateException.class);
        assertThat(analysis.getAnalysisStatus()).isEqualTo(status);
    }

    private static StockEventAnalysis pending() {
        return StockEventAnalysis.create("000660", "테스트 종목", LocalDate.of(2026, 11, 5), new BigDecimal("-6.1"));
    }
}
