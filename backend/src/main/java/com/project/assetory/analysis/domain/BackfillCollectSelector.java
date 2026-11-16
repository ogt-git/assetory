package com.project.assetory.analysis.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 소급 직후 근거 수집 대상 선정 (Q-4, FR-DT-08 · NFR-US-03) — 스프링 · DB 의존 없는 순수 규칙.
 *
 * - 날짜 < 오늘 · PENDING만. 오늘 날짜 분석은 16:00 배치가 실시간 분석과 함께 처리한다 — 연동 때 먼저 수집해 요약 생략으로
 *   확정하면 같은 분석을 공유할 실시간 사용자가 요약을 못 받는다
 * - |변동률| 큰 순 → 같으면 날짜 최신 → id 오름차순(결정적). 상한(assetory.analysis.backfill-collect-max)까지 자른다
 * - 같은 분석 id는 한 번만
 */
public final class BackfillCollectSelector {

    private static final Comparator<Candidate> ORDER = Comparator
            .comparing((Candidate c) -> c.changeRate().abs(), Comparator.reverseOrder())
            .thenComparing(Candidate::eventDate, Comparator.reverseOrder())
            .thenComparing(Candidate::id);

    private BackfillCollectSelector() {
    }

    public static List<Long> select(List<Candidate> candidates, LocalDate today, int limit) {
        if (today == null) {
            throw new IllegalArgumentException("오늘 날짜가 없다");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("수집 상한은 1 이상이어야 한다: " + limit);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Candidate> unique = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c != null && c.status() == AnalysisStatus.PENDING && c.eventDate().isBefore(today)) {
                unique.putIfAbsent(c.id(), c);
            }
        }
        return unique.values().stream().sorted(ORDER).limit(limit).map(Candidate::id).toList();
    }

    /** 수집 후보 — 분석 id · 이벤트 날짜 · 종목 변동률(%) · 분석 상태 */
    public record Candidate(Long id, LocalDate eventDate, BigDecimal changeRate, AnalysisStatus status) {
    }
}
