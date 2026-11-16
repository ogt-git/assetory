package com.project.assetory.analysis.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * docs/05 §5.1 stock_event_analyses — 종목 · 날짜 단위 공유 분석 (FR-CA-10). 보유 사용자 모두가 재사용한다.
 * UNIQUE (ticker, event_date)가 공유 분석 멱등성의 최종 방어선이다 (Redis 락은 11주차).
 * 종목의 사실만 담는다 — 사용자 비중 · 손익은 user_market_events.
 *
 * 생성 시 PENDING. 근거 수집 · 요약 전에는 시장 비교 · 근거 상태 · 요약 · 토큰 값을 알 수 없어 NULL로 둔다
 * (추측값 · 0으로 채우지 않음, AGENTS 5-C). 상태 전환은 근거 수집(B1) · 요약(11주차)에서 추가한다.
 *
 * change_rate 한계: 16:00 배치가 읽은 잠정 변동률(한투 prdy_ctrt)이다. 다음 거래일 07:30 잠정 종가 보정은
 * 이 값을 고치지 않고, 이벤트도 다시 만들거나 지우지 않는다 (10주차 결정 D1). 확정 변동률과 다를 수 있다.
 */
@Entity
@Table(name = "stock_event_analyses",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_event_analyses_ticker_date",
                columnNames = {"ticker", "event_date"}),
        indexes = @Index(name = "idx_stock_event_analyses_status_created", columnList = "analysis_status, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockEventAnalysis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    // 퍼센트 — 위 한계(잠정값, D1) 참고
    @Column(name = "change_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal changeRate;

    // KOSPI / KOSDAQ — 시장 비교(FR-CA-05)는 11주차
    @Column(name = "market_index_code", length = 30)
    private String marketIndexCode;

    @Column(name = "market_change_rate", precision = 8, scale = 4)
    private BigDecimal marketChangeRate;

    // 시장 대비 초과 변동 (%p)
    @Column(name = "excess_rate", precision = 8, scale = 4)
    private BigDecimal excessRate;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", length = 20)
    private EvidenceStatus evidenceStatus;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus;

    // 운영 지표용 — 사용자 화면 · API 응답에 노출하지 않는다 (AGENTS 5-D)
    @Column(name = "llm_prompt_tokens")
    private Integer llmPromptTokens;

    @Column(name = "llm_completion_tokens")
    private Integer llmCompletionTokens;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    public static StockEventAnalysis create(String ticker, String stockName, LocalDate eventDate, BigDecimal changeRate) {
        StockEventAnalysis analysis = new StockEventAnalysis();
        analysis.ticker = ticker;
        analysis.stockName = stockName;
        analysis.eventDate = eventDate;
        analysis.changeRate = changeRate;
        analysis.evidenceCount = 0;
        analysis.analysisStatus = AnalysisStatus.PENDING;
        return analysis;
    }

    /**
     * 근거 수집 완료 (B1) — PENDING에서만. 0건이면 NO_EVIDENCE(정상 결과, LLM 미호출 — FR-CA-07),
     * 1건 이상이면 COLLECTING(수집 완료 · 요약 대기 — 05 §5.1 "수집 중" 의미 변경은 문서 반영 필요).
     * evidence_count는 수집 시점 저장 건수(기록값) — 뉴스 21일 삭제 뒤에도 다시 세지 않는다.
     * 근거 상태(evidence_status)는 지수 대비 판정이 필요해 여기서 정하지 않는다(B4 이후)
     */
    public void completeCollection(int evidenceCount) {
        if (analysisStatus != AnalysisStatus.PENDING) {
            throw new IllegalStateException("PENDING이 아닌 분석은 수집 완료로 바꾸지 않는다: " + analysisStatus);
        }
        if (evidenceCount < 0) {
            throw new IllegalArgumentException("근거 건수는 0 이상이어야 한다: " + evidenceCount);
        }
        this.evidenceCount = evidenceCount;
        this.analysisStatus = evidenceCount == 0 ? AnalysisStatus.NO_EVIDENCE : AnalysisStatus.COLLECTING;
    }

    /**
     * 근거 상태 판정 반영 (FR-CA-05 · 06, 01 §14 #12) — 수집이 끝난 분석(NO_EVIDENCE · COLLECTING)에서만.
     * 지수 변동률 · 초과 변동은 시장 구분 · 지수 값이 없으면 NULL 그대로 둔다(0으로 채우지 않음, AGENTS 5-C).
     * analysis_status · evidence_count · market_index_code(시장 구분은 Q-2 몫)는 바꾸지 않는다
     */
    public void applyEvidenceStatus(BigDecimal marketChangeRate, EvidenceStatusCalculator.Result result) {
        if (analysisStatus != AnalysisStatus.NO_EVIDENCE && analysisStatus != AnalysisStatus.COLLECTING) {
            throw new IllegalStateException("수집이 끝나지 않은 분석에는 근거 상태를 반영하지 않는다: " + analysisStatus);
        }
        if (result == null || result.status() == null) {
            throw new IllegalArgumentException("판정 결과가 없다");
        }
        this.marketChangeRate = marketChangeRate;
        this.excessRate = result.excessRate();
        this.evidenceStatus = result.status();
    }
}
