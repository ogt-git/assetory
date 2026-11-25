package com.project.assetory.market.domain;

import com.project.assetory.market.exception.IndicatorSeriesException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 지표 응답 → 저장 행 (B4 확정 사항 3 · 4 · 5). 순서:
 * 1. 날짜 오름차순 정렬 — 응답 정렬 순서에 의존하지 않는다
 * 2. 같은 날짜가 두 번 있으면 그 지표 실패(어느 값을 믿을지 정하지 않는다) · 값이 없으면 실패
 * 3. change_rate = (값 − 직전 행 값) ÷ 직전 행 값 × 100, HALF_UP 4자리. 외부가 준 대비율은 쓰지 않는다.
 *    computeChangeRate=false(BASE_RATE)면 NULL
 * 4. 가장 오래된 날짜 행은 저장하지 않는다 — 직전 값이 없어 계산할 수 없다(BASE_RATE도 같은 규칙)
 * 받은 날짜만 저장한다. 빠진 날짜를 0 · 직전 값 · 임의 날짜로 채우지 않는다 (AGENTS 5-C)
 */
public final class IndicatorSeries {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 4;

    private IndicatorSeries() {
    }

    public static List<MacroIndicatorRow> toRows(IndicatorCode code, List<DatedValue> values, boolean computeChangeRate) {
        List<DatedValue> sorted = new ArrayList<>(values);
        for (DatedValue v : sorted) {
            if (v == null || v.date() == null || v.value() == null) {
                throw new IndicatorSeriesException(code, "날짜 또는 값 없음");
            }
        }
        sorted.sort(Comparator.comparing(DatedValue::date));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).date().equals(sorted.get(i - 1).date())) {
                throw new IndicatorSeriesException(code, "같은 날짜 중복 " + sorted.get(i).date());
            }
        }
        List<MacroIndicatorRow> rows = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            DatedValue previous = sorted.get(i - 1);
            DatedValue current = sorted.get(i);
            BigDecimal changeRate = computeChangeRate ? changeRate(code, previous.value(), current.value()) : null;
            rows.add(new MacroIndicatorRow(code, current.date(), current.value(), changeRate));
        }
        return List.copyOf(rows);
    }

    private static BigDecimal changeRate(IndicatorCode code, BigDecimal previous, BigDecimal current) {
        if (previous.signum() == 0) {
            throw new IndicatorSeriesException(code, "직전 값 0 — 변동률 계산 불가");
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
