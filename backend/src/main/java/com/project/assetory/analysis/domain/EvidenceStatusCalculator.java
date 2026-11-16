package com.project.assetory.analysis.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 근거 상태 판정 (FR-CA-05 · 06 · 07, 05 §5.1 evidence_status, 01 §14 #12) — 스프링 · DB 의존 없는 순수 계산.
 *
 * 1. excess_rate = 종목 변동률 − 지수 변동률 (%p, HALF_UP 4자리). 지수가 없으면 NULL — 0으로 채우지 않는다 (AGENTS 5-C)
 * 2. 필터 통과 공시 · 뉴스(DISCLOSURE · NEWS) 1건 이상 → RELATED_FOUND. 거시지표(MACRO)는 관련 근거로 세지 않는다
 *    (필터는 수집 단계에서 적용된다 — 저장된 공시 · 뉴스 행은 모두 필터 통과분)
 * 3. 근거 0건 · 지수 없음 → NOT_FOUND (시장 동반이라고 말할 근거가 없다)
 * 4. 근거 0건 · |excess| ≤ 기준 그리고 |excess| ≤ |지수 변동| → MARKET_MOVE (경계 포함). 그 밖은 NOT_FOUND
 *    조건 2는 "시장이 같은 방향으로 종목 변동의 절반 이상 움직였다"는 판정의 정의라 설정으로 빼지 않는다 —
 *    낮은 사용자 임계치(예: 종목 +2.0% · 지수 +0.5%)를 시장 동반으로 잘못 판정하지 않게 한다 (05 원안에 추가 — 문서 반영 필요)
 *
 * 한계: 레버리지 · 인버스 ETF는 배율을 보정하지 않아 시장을 그대로 따라가도 NOT_FOUND가 될 수 있다 (01 §14 #13 범위).
 * 07:30 잠정 종가 보정은 이 판정을 다시 돌리지 않는다 (10주차 결정 D1).
 */
public final class EvidenceStatusCalculator {

    private static final int RATE_SCALE = 4;

    private EvidenceStatusCalculator() {
    }

    /**
     * @param changeRate       종목 변동률 (%)
     * @param marketChangeRate 같은 날 지수 변동률 (%) — 시장 구분 · 지수 값이 없으면 NULL
     * @param sourceCounts     저장된 근거의 유형별 건수 — NULL이면 0건
     * @param maxExcessRate    시장 동반 기준 (%p, assetory.analysis.market-move.max-excess-rate)
     */
    public static Result judge(BigDecimal changeRate, BigDecimal marketChangeRate,
                               Map<SourceType, Long> sourceCounts, BigDecimal maxExcessRate) {
        if (changeRate == null) {
            throw new IllegalArgumentException("종목 변동률이 없다");
        }
        if (maxExcessRate == null) {
            throw new IllegalArgumentException("시장 동반 기준이 없다");
        }
        BigDecimal excess = marketChangeRate == null ? null
                : changeRate.subtract(marketChangeRate).setScale(RATE_SCALE, RoundingMode.HALF_UP);

        if (relatedCount(sourceCounts) > 0) {
            return new Result(excess, EvidenceStatus.RELATED_FOUND);
        }
        if (excess == null) {
            return new Result(null, EvidenceStatus.NOT_FOUND);
        }
        BigDecimal absExcess = excess.abs();
        boolean marketMove = absExcess.compareTo(maxExcessRate) <= 0
                && absExcess.compareTo(marketChangeRate.abs()) <= 0;
        return new Result(excess, marketMove ? EvidenceStatus.MARKET_MOVE : EvidenceStatus.NOT_FOUND);
    }

    private static long relatedCount(Map<SourceType, Long> sourceCounts) {
        if (sourceCounts == null) {
            return 0;
        }
        return count(sourceCounts, SourceType.DISCLOSURE) + count(sourceCounts, SourceType.NEWS);
    }

    private static long count(Map<SourceType, Long> sourceCounts, SourceType type) {
        Long value = sourceCounts.get(type);
        return value == null ? 0 : Math.max(0, value);
    }

    /** excessRate는 지수가 없으면 NULL — 근거가 있어도(RELATED_FOUND) 계산되면 그대로 저장한다 */
    public record Result(BigDecimal excessRate, EvidenceStatus status) {
    }
}
