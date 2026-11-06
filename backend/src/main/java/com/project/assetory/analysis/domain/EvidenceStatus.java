package com.project.assetory.analysis.domain;

// docs/05 §5.1 stock_event_analyses.evidence_status — 신뢰 등급이 아니라 근거 확인 결과 (FR-CA-06)
public enum EvidenceStatus {
    RELATED_FOUND,
    MARKET_MOVE,
    NOT_FOUND
}
