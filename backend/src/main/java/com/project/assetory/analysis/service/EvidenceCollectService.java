package com.project.assetory.analysis.service;

import com.project.assetory.analysis.config.AnalysisProperties;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EvidenceStatusCalculator;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.repository.MacroIndicatorRepository;
import com.project.assetory.market.service.StockMarketResolver;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.evidence.EvidenceCollector;
import com.project.assetory.analysis.evidence.EvidenceSanitizer;
import com.project.assetory.analysis.evidence.EvidenceTarget;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult.CollectorRun;
import com.project.assetory.analysis.service.EvidenceCollectResult.Outcome;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.HoldingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 공유 분석 1건의 근거 수집 (FR-CA-01 · 02 · 07 · 11, 05 §11.3). 배치 · 소급 연결은 B2 · B3 이후.
 *
 * 흐름 (B1 확정 사항 2)
 * 1. 분석 조회 → Redis 락 lock:analysis:{ticker}:{date} (SET NX EX 5분, 값 UUID — 05 §9). 못 잡으면 건너뜀
 * 2. 락을 잡은 뒤 다시 읽어 PENDING이 아니면 종료 (멱등). 수집 중에도 PENDING이다 — 동시 실행은 락이 막는다
 *    → 시장 구분이 비어 있으면 한투 종목정보로 비교 지수를 조회한다(Q-2, 트랜잭션 밖, 실패 = 비교 지수 없음)
 * 3. supports()가 참인 수집기만 실행 — 트랜잭션 밖(외부 호출 동안 DB 커넥션을 잡지 않는다). 수집기별 예외는 잡아 실패로 기록하고 계속
 * 4. 실행한 수집기가 모두 실패 → 아무것도 바꾸지 않고 PENDING 유지(다음 실행 때 재시도). 실행한 수집기가 0개면 근거 0건으로 진행
 * 5. 한 트랜잭션: 값 검증 → insert-if-absent 저장 → evidence_count = 저장 행 수 → 0건 NO_EVIDENCE / 1건 이상 COLLECTING
 *    → 근거 상태 판정(지수 변동률 · 초과 변동 · evidence_status, EvidenceStatusCalculator — 공시 · 뉴스만 관련 근거로 센다)
 *    → 공시가 0건인 COLLECTING(뉴스 · 거시지표만)은 SUMMARY_SKIPPED (Q-3, LLM 입력 근거 없음)
 * 6. finally 락 해제 — 값이 내 UUID일 때만 (TTL이 지나 남이 잡은 락을 지우지 않는다)
 *
 * 락 1차 · DB UNIQUE (analysis_id, external_id) 2차 방어(05 §13). 공유 분석 생성(A4 · A5)은 이 락을 쓰지 않고
 * UNIQUE (ticker, event_date) 업서트로 막으므로, 같은 키를 수집에만 쓴다.
 * 로그: analysisId · ticker · 날짜 · 수집기별 건수 · 예외 클래스 이름만 (근거 본문 · 키 · 예외 메시지 금지 — 메시지에 요청 URL이 들어갈 수 있다)
 */
@Slf4j
@Service
public class EvidenceCollectService {

    static final String LOCK_PREFIX = "lock:analysis:";
    /** 05 §9 공유 분석 락 5분. 수집기 전체 실행 시간 합이 이보다 작아야 한다(EvidenceCollector 주석) */
    static final Duration LOCK_TTL = Duration.ofMinutes(5);

    // 소유권(UUID)이 일치할 때만 삭제 — 기존 락(ConnectService · StockSyncService)과 같은 방식
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StockEventAnalysisRepository analysisRepository;
    private final EventSourceRepository eventSourceRepository;
    private final HoldingRepository holdingRepository;
    private final List<EvidenceCollector> collectors;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final AnalysisProperties analysisProperties;
    private final StockMarketResolver stockMarketResolver;

    public EvidenceCollectService(StockEventAnalysisRepository analysisRepository,
                                  EventSourceRepository eventSourceRepository,
                                  HoldingRepository holdingRepository,
                                  ObjectProvider<EvidenceCollector> collectors,
                                  StringRedisTemplate redisTemplate,
                                  TransactionTemplate transactionTemplate,
                                  MacroIndicatorRepository macroIndicatorRepository,
                                  AnalysisProperties analysisProperties,
                                  StockMarketResolver stockMarketResolver) {
        this.analysisRepository = analysisRepository;
        this.eventSourceRepository = eventSourceRepository;
        this.holdingRepository = holdingRepository;
        this.macroIndicatorRepository = macroIndicatorRepository;
        this.analysisProperties = analysisProperties;
        this.stockMarketResolver = stockMarketResolver;
        // 등록된 수집기 빈 전부(@Order 순). 지금은 운영 구현이 0개라 빈 목록으로도 기동돼야 한다 — B2 · B3 · B4에서 추가
        this.collectors = collectors.orderedStream().toList();
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public EvidenceCollectResult collect(Long analysisId) {
        Optional<StockEventAnalysis> found = analysisRepository.findById(analysisId);
        if (found.isEmpty()) {
            return EvidenceCollectResult.skipped(Outcome.NOT_FOUND, null);
        }
        StockEventAnalysis analysis = found.get();
        String lockKey = LOCK_PREFIX + analysis.getTicker() + ":" + analysis.getEventDate();
        String lockToken = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_TTL))) {
            log.info("[Evidence] 락 사용 중 — 건너뜀 analysisId={} ticker={} date={}", analysisId, analysis.getTicker(), analysis.getEventDate());
            return EvidenceCollectResult.skipped(Outcome.SKIPPED_LOCKED, analysis.getAnalysisStatus());
        }
        try {
            return collectLocked(analysisId);
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
        }
    }

    private EvidenceCollectResult collectLocked(Long analysisId) {
        // 락을 잡기 전에 다른 실행이 끝냈을 수 있어 다시 읽는다
        StockEventAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        if (analysis.getAnalysisStatus() != AnalysisStatus.PENDING) {
            return EvidenceCollectResult.skipped(Outcome.SKIPPED_NOT_PENDING, analysis.getAnalysisStatus());
        }
        EvidenceTarget target = new EvidenceTarget(analysis.getTicker(), analysis.getStockName(), analysis.getEventDate(),
                holdingRepository.existsByTickerAndSecurityType(analysis.getTicker(), SecurityType.ETF));
        // 시장 구분(Q-2) — 트랜잭션 밖 외부 호출, 값이 없을 때만 분석 1건당 1회. 실패해도 수집은 계속한다(비교 지수 없음)
        IndicatorCode marketIndex = analysis.getMarketIndexCode() == null
                ? stockMarketResolver.resolve(analysis.getTicker()).orElse(null)
                : null;

        List<CollectorRun> runs = new ArrayList<>();
        List<CollectedEvidence> collected = new ArrayList<>();
        for (EvidenceCollector collector : collectors) {
            if (!collector.supports(target)) continue;
            try {
                List<CollectedEvidence> result = collector.collect(target);
                int size = result == null ? 0 : result.size();
                if (result != null) collected.addAll(result);
                runs.add(new CollectorRun(collector.sourceType(), true, size));
            } catch (RuntimeException e) {
                runs.add(new CollectorRun(collector.sourceType(), false, 0));
                log.warn("[Evidence] 수집기 실패 analysisId={} ticker={} date={} type={} error={}", analysisId,
                        target.ticker(), target.eventDate(), collector.sourceType(), e.getClass().getSimpleName());
            }
        }
        if (!runs.isEmpty() && runs.stream().noneMatch(CollectorRun::success)) {
            log.warn("[Evidence] 수집기 모두 실패 — PENDING 유지 analysisId={} ticker={} date={}", analysisId, target.ticker(), target.eventDate());
            return new EvidenceCollectResult(Outcome.ALL_FAILED, List.copyOf(runs), 0, 0, AnalysisStatus.PENDING);
        }

        EvidenceSanitizer.Result sanitized = EvidenceSanitizer.sanitize(target.ticker(), collected);
        try {
            AnalysisStatus status = transactionTemplate.execute(tx -> save(analysisId, sanitized.accepted(), marketIndex));
            StockEventAnalysis saved = analysisRepository.findById(analysisId).orElseThrow();
            log.info("[Evidence] 수집 완료 analysisId={} ticker={} date={} runs={} saved={} excluded={} status={}", analysisId,
                    target.ticker(), target.eventDate(), runs, saved.getEvidenceCount(), sanitized.excluded(), status);
            return new EvidenceCollectResult(Outcome.COMPLETED, List.copyOf(runs), saved.getEvidenceCount(),
                    sanitized.excluded(), status);
        } catch (RuntimeException e) {
            log.error("[Evidence] 저장 실패 — 롤백, PENDING 유지 analysisId={} ticker={} date={} error={}", analysisId,
                    target.ticker(), target.eventDate(), e.getClass().getSimpleName());
            return new EvidenceCollectResult(Outcome.SAVE_FAILED, List.copyOf(runs), 0, sanitized.excluded(), AnalysisStatus.PENDING);
        }
    }

    private AnalysisStatus save(Long analysisId, List<CollectedEvidence> evidences, IndicatorCode marketIndex) {
        StockEventAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        // 판정(applyEvidenceStatus)이 market_index_code로 지수를 고르므로 그보다 먼저 넣는다. 이미 값이 있으면 그대로
        analysis.assignMarketIndex(marketIndex);
        LocalDateTime now = LocalDateTime.now();
        for (CollectedEvidence e : evidences) {
            eventSourceRepository.insertIfAbsent(analysisId, e.type().name(), e.title(), e.sourceName(), e.publishedAt(),
                    e.url(), e.snippet(), e.relevanceRank(), e.externalId(), now);
        }
        analysis.completeCollection(Math.toIntExact(eventSourceRepository.countByAnalysisId(analysisId)));
        Map<SourceType, Long> counts = new EnumMap<>(SourceType.class);
        for (EventSourceRepository.SourceTypeCount row : eventSourceRepository.countBySourceType(analysisId)) {
            counts.put(row.getSourceType(), row.getCount());
        }
        applyEvidenceStatus(analysis, counts);
        // Q-3 — 근거는 있지만 LLM에 넣을 공시가 없으면(뉴스 · 거시지표만) 요약 대기로 두지 않는다. LLM · AI 한도는 건드리지 않는다
        if (analysis.getAnalysisStatus() == AnalysisStatus.COLLECTING && !EvidenceStatusCalculator.hasSummaryInput(counts)) {
            analysis.skipSummary();
        }
        return analysis.getAnalysisStatus();
    }

    /**
     * 근거 상태 판정 (01 §14 #12) — 수집 저장과 같은 트랜잭션. 실패하면 수집 저장까지 롤백되고 PENDING으로 남아 다음 실행 때 다시 한다.
     * 시장 구분(market_index_code)이 없거나 KOSPI · KOSDAQ가 아니면 지수를 추측하지 않고 NULL로 판정한다
     */
    private void applyEvidenceStatus(StockEventAnalysis analysis, Map<SourceType, Long> counts) {
        BigDecimal marketChangeRate = marketIndex(analysis.getMarketIndexCode())
                .flatMap(code -> macroIndicatorRepository.findByIndicatorCodeAndBaseDate(code, analysis.getEventDate()))
                .map(MacroIndicator::getChangeRate)
                .orElse(null);
        EvidenceStatusCalculator.Result result = EvidenceStatusCalculator.judge(analysis.getChangeRate(), marketChangeRate,
                counts, analysisProperties.marketMove().maxExcessRate());
        analysis.applyEvidenceStatus(marketChangeRate, result);
    }

    private static Optional<IndicatorCode> marketIndex(String marketIndexCode) {
        if (IndicatorCode.KOSPI.name().equals(marketIndexCode)) return Optional.of(IndicatorCode.KOSPI);
        if (IndicatorCode.KOSDAQ.name().equals(marketIndexCode)) return Optional.of(IndicatorCode.KOSDAQ);
        return Optional.empty();
    }
}
