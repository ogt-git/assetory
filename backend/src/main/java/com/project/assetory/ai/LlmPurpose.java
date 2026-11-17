package com.project.assetory.ai;

/**
 * LLM 사용 지점 (FR-AI-01 — 원인 요약 · 목표 코멘트 · 월간 총평에만) — 메트릭 llm.calls · llm.tokens의 purpose 태그 값(05 §12)
 */
public enum LlmPurpose {
    CAUSE("cause"),
    GOAL("goal"),
    MONTHLY("monthly");

    private final String tag;

    LlmPurpose(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
