package com.project.assetory.analysis.domain;

/**
 * docs/05 §5.2 event_sources.source_type — 근거 출처 유형.
 * llmInputAllowed: 원인 요약(LLM)에 넣어도 되는지. 컬럼이 아니라 유형 규칙이다.
 * 뉴스(네이버 API HUB 검색 결과)는 약관상 AI 입력 금지라 표시 전용 (B0 결정 A안 — 01 §6 문서 반영 필요)
 */
public enum SourceType {
    DISCLOSURE(true),
    NEWS(false),
    MACRO(true);

    private final boolean llmInputAllowed;

    SourceType(boolean llmInputAllowed) {
        this.llmInputAllowed = llmInputAllowed;
    }

    public boolean isLlmInputAllowed() {
        return llmInputAllowed;
    }
}
