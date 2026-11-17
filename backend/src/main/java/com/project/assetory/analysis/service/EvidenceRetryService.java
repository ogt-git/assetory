package com.project.assetory.analysis.service;

import com.project.assetory.analysis.config.AnalysisProperties;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING 분석 재시도 1회 실행 (Q-6, ANL-7, FR-CA-14 · NFR-RL-03) — 스케줄러와 분리한 실행 단위.
 *
 * - 대상: PENDING · created_at ≥ 지금 − window, |변동률| 큰 순 · 최대 max-per-run건 (assetory.analysis.retry.*)
 *   재시도 횟수 컬럼이 없어 시간 창이 상한이다. 창이 지난 PENDING은 그대로 둔다(NO_EVIDENCE로 바꾸지 않음 — 수집 실패는 근거 0건이 아니다)
 * - 분석마다 기존 EvidenceCollectService.collect — 락(못 잡으면 건너뜀) · 판정 · Q-3 · Q-4 · D2를 그대로 탄다. 새 수집 로직 · 락 없음
 * - 분석 1건이 예외를 던져도 다음으로 넘어간다. 로그는 analysisId · 종목 · 날짜 · 결과 유형 · 예외 클래스 이름만
 * - 이 메서드는 트랜잭션을 열지 않는다(분석별 트랜잭션은 수집 서비스 경계)
 * - FAILED(LLM 실패) 재시도는 요약 서비스(AI-5) 때 추가한다
 */
@Slf4j
@Service
public class EvidenceRetryService {

    static final String METRIC_STEP = "analyze-retry";

    private final StockEventAnalysisRepository analysisRepository;
    private final EvidenceCollectService evidenceCollectService;
    private final AnalysisProperties analysisProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EvidenceRetryService(StockEventAnalysisRepository analysisRepository, EvidenceCollectService evidenceCollectService,
                                AnalysisProperties analysisProperties, MeterRegistry meterRegistry, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.evidenceCollectService = evidenceCollectService;
        this.analysisProperties = analysisProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public Result run() {
        long start = System.nanoTime();
        AnalysisProperties.Retry retry = analysisProperties.retry();
        LocalDateTime since = LocalDateTime.now(clock).minus(retry.window());
        List<StockEventAnalysis> targets = analysisRepository.findRetryTargets(AnalysisStatus.PENDING, since, Limit.of(retry.maxPerRun()));
        int completed = 0;
        int stillPending = 0;
        int skippedLocked = 0;
        int failed = 0;
        for (StockEventAnalysis target : targets) {
            try {
                EvidenceCollectResult result = evidenceCollectService.collect(target.getId());
                if (result.outcome() == Outcome.SKIPPED_LOCKED) {
                    skippedLocked++;
                } else if (result.status() == AnalysisStatus.PENDING) {
                    stillPending++;
                } else {
                    completed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[EvidenceRetry] 재시도 실패 — 다음으로 진행 analysisId={} ticker={} date={} error={}", target.getId(),
                        target.getTicker(), target.getEventDate(), e.getClass().getSimpleName());
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Timer.builder("batch.step.duration").tag("step", METRIC_STEP).register(meterRegistry).record(elapsed);
        log.info("[EvidenceRetry] 재시도 완료 대상={} 완료={} 여전히PENDING={} 건너뜀(락)={} 예외={} since={} elapsedMs={}", targets.size(),
                completed, stillPending, skippedLocked, failed, since, elapsed.toMillis());
        return new Result(targets.size(), completed, stillPending, skippedLocked, failed);
    }

    /**
     * @param completed    수집이 끝나 PENDING을 벗어난 수(NO_EVIDENCE · SUMMARY_SKIPPED · COLLECTING)
     * @param stillPending 다시 했지만 PENDING(수집기 모두 실패 · 공시 재시도 · 저장 실패)
     */
    public record Result(int targets, int completed, int stillPending, int skippedLocked, int failed) {
    }
}
