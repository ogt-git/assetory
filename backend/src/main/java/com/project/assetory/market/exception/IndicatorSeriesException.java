package com.project.assetory.market.exception;

import com.project.assetory.market.domain.IndicatorCode;

// 지표 응답을 저장 행으로 만들 수 없음(같은 날짜 중복 · 값 누락 · 직전 값 0) — 그 지표 전체 실패 (B4 확정 사항 3 · 5)
public class IndicatorSeriesException extends RuntimeException {

    public IndicatorSeriesException(IndicatorCode code, String reason) {
        super(code + " 지표 처리 불가: " + reason);
    }
}
