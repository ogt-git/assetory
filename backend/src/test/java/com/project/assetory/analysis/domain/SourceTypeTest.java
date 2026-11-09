package com.project.assetory.analysis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeTest {

    @Test
    @DisplayName("LLM 입력은 공시 · 거시지표만 가능, 뉴스는 불가 — 네이버 검색 결과는 AI 입력 금지 (B0 A안)")
    void llmInputAllowed() {
        assertThat(SourceType.DISCLOSURE.isLlmInputAllowed()).isTrue();
        assertThat(SourceType.MACRO.isLlmInputAllowed()).isTrue();
        assertThat(SourceType.NEWS.isLlmInputAllowed()).isFalse();
    }

    @Test
    @DisplayName("값은 05 §5.2 그대로 3개")
    void values() {
        assertThat(SourceType.values()).containsExactly(SourceType.DISCLOSURE, SourceType.NEWS, SourceType.MACRO);
    }
}
