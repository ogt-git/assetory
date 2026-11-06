package com.project.assetory.analysis.domain;

// docs/05 §5.1 stock_event_analyses.analysis_status — NO_EVIDENCE · SUMMARY_SKIPPED는 오류가 아닌 정상 결과 (AGENTS 5-D)
public enum AnalysisStatus {
    PENDING,
    COLLECTING,
    ANALYZED,
    NO_EVIDENCE,
    SUMMARY_SKIPPED,
    FAILED
}
