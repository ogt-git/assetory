package com.project.assetory.analysis.evidence;

import java.time.LocalDate;

/**
 * 근거 수집 대상 — 공유 분석(stock_event_analyses) 1건의 종목 · 날짜 사실.
 *
 * @param etf ETF면 공시 · 뉴스를 수집하지 않는다 (FR-CA-01 · 02). 판별 출처는 holdings.security_type(한투 종목 정보로 채움, 01 §14 #24)
 */
public record EvidenceTarget(String ticker, String stockName, LocalDate eventDate, boolean etf) {
}
