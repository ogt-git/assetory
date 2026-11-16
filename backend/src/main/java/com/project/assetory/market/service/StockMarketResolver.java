package com.project.assetory.market.service;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisMarketCode;
import com.project.assetory.market.kis.KisStockInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 종목 → 근거 상태 판정의 비교 지수 (Q-2, 01 §14 #12). 한투 종목정보(search-stock-info) mket_id_cd를 KisMarketCode로 바꾼다.
 * 조회 실패(오류 응답 · 타임아웃 · 해석 불가 · 응답 없음)는 "비교 지수 없음"이다 — 예외를 밖으로 던지지 않는다.
 * 로그는 종목코드 · 예외 클래스 이름만 (응답 원문 · 키 금지). 캐시하지 않는다 — 분석 1건당 1회 호출 (StockInfoLookup은 시세 배치 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMarketResolver {

    private final KisApiClient kis;

    public Optional<IndicatorCode> resolve(String ticker) {
        KisStockInfo info;
        try {
            info = kis.getStockInfo(ticker);
        } catch (RuntimeException e) {
            log.warn("[Market] 시장 구분 조회 실패 — 비교 지수 없음 ticker={} error={}", ticker, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (info == null) {
            return Optional.empty();
        }
        Optional<IndicatorCode> code = KisMarketCode.toIndexCode(info.marketIdCode());
        if (code.isEmpty()) {
            log.info("[Market] 비교 지수 없는 시장 ticker={} marketIdCode={}", ticker, info.marketIdCode());
        }
        return code;
    }
}
