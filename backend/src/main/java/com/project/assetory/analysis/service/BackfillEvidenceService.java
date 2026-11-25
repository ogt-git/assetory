package com.project.assetory.analysis.service;

import com.project.assetory.analysis.config.AnalysisProperties;
import com.project.assetory.analysis.domain.BackfillCollectSelector;
import com.project.assetory.analysis.domain.BackfillCollectSelector.Candidate;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 연동 직후 소급 분석의 근거 수집 (Q-4, FR-DT-08 · NFR-US-03) — 최초 연동 비동기 흐름이 이벤트 소급 뒤에 부른다.
 *
 * - 대상: 이번 소급이 다룬 분석 중 날짜 < 오늘 · PENDING, |변동률| 큰 순 · 최대 assetory.analysis.backfill-collect-max건
 *   (BackfillCollectSelector). 오늘 날짜 분석은 16:00 배치 몫이라 건드리지 않는다
 * - 수집은 기존 EvidenceCollectService.collect를 순서대로 부른다 — 락(lock:analysis:*) · UNIQUE · 판정 · Q-3 · Q-4 생략을 그대로 탄다.
 *   소급 전용 분석의 요약 생략(SUMMARY_SKIPPED)은 수집 서비스 저장 트랜잭션에서 정한다 (LLM 미호출)
 * - 이 메서드는 트랜잭션을 열지 않는다 — 분석별 트랜잭션은 수집 서비스 경계를 따르고, 외부 호출은 트랜잭션 밖이다
 * - 분석 1건이 예외를 던져도 다음으로 넘어간다. 로그는 analysisId · 종목 · 날짜 · 예외 클래스 이름만
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackfillEvidenceService {

    private final StockEventAnalysisRepository analysisRepository;
    private final EvidenceCollectService evidenceCollectService;
    private final AnalysisProperties analysisProperties;
    private final Clock clock;

    public Result collect(List<Long> analysisIds) {
        if (analysisIds == null || analysisIds.isEmpty()) {
            return new Result(0, 0, 0);
        }
        long start = System.nanoTime();
        List<StockEventAnalysis> analyses = analysisRepository.findAllById(analysisIds);
        List<Candidate> candidates = analyses.stream()
                .map(a -> new Candidate(a.getId(), a.getEventDate(), a.getChangeRate(), a.getAnalysisStatus()))
                .toList();
        List<Long> targets = BackfillCollectSelector.select(candidates, LocalDate.now(clock),
                analysisProperties.backfillCollectMax());
        int failed = 0;
        for (Long id : targets) {
            try {
                evidenceCollectService.collect(id);
            } catch (RuntimeException e) {
                failed++;
                StockEventAnalysis a = analyses.stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
                log.warn("[BackfillEvidence] 소급 분석 근거 수집 실패 — 다음으로 진행 analysisId={} ticker={} date={} error={}", id,
                        a == null ? null : a.getTicker(), a == null ? null : a.getEventDate(), e.getClass().getSimpleName());
            }
        }
        log.info("[BackfillEvidence] 소급 분석 근거 수집 후보={} 대상={} 실패={} elapsedMs={}", analyses.size(), targets.size(), failed,
                (System.nanoTime() - start) / 1_000_000);
        return new Result(analyses.size(), targets.size(), failed);
    }

    /** candidates = 받은 분석 수, selected = 수집을 부른 수(날짜 < 오늘 · PENDING · 상한), failed = 예외로 끝난 수 */
    public record Result(int candidates, int selected, int failed) {
    }
}
