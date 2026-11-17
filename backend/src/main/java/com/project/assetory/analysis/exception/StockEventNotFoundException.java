package com.project.assetory.analysis.exception;

// 원인 분석 상세 — 그 사용자 · 종목 · 날짜의 이벤트가 없음(남의 이벤트 포함, Q-9). 응답은 STOCK_EVENT_NOT_FOUND(404)
public class StockEventNotFoundException extends RuntimeException {

    public StockEventNotFoundException() {
        super("종목 이벤트 없음");
    }
}
