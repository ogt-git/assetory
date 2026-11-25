package com.project.assetory.ai.guard;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 출력 검사 결과 (Q-7) — 걸린 규칙 전부(D5). 비어 있으면 통과. 요약 원문은 담지 않는다(AGENTS 9장) — toString에는 규칙 이름만
 */
public record OutputGuardResult(Set<OutputRule> violations) {

    public OutputGuardResult {
        violations = violations == null || violations.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(OutputRule.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(violations));
    }

    public boolean passed() {
        return violations.isEmpty();
    }
}
