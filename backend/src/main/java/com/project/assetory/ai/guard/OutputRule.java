package com.project.assetory.ai.guard;

import java.util.Locale;

/**
 * LLM 출력 검사 규칙 (AI-3, Q-7) — 메트릭 llm.output.rejected의 rule 태그 값(소문자)이다. 값이 고정돼 카디널리티가 제한된다.
 * - CAUSAL: 인과 단정 (AGENTS 5-D, FR-CA-08)
 * - SOLICIT: 투자 권유 (5-D, FR-CA-09)
 * - FORECAST: 전망 — 01 §9 "사실 정보 전달과 요약까지만"의 해석 (문서 반영 필요)
 * - UNGROUNDED_NUMBER: 검사 컨텍스트의 입력에 없는 숫자 · 날짜 · 시각 (5-D "숫자는 코드가 계산")
 * - EMPTY: 빈 요약 · 공백뿐인 요약
 */
public enum OutputRule {
    CAUSAL,
    SOLICIT,
    FORECAST,
    UNGROUNDED_NUMBER,
    EMPTY;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
