package com.project.assetory.market.service;

import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisStockInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// 한 번의 수집 실행 안에서만 쓰는 종목정보 캐시 — 거래정지 확인과 STOCK/ETF 판별이 같은 종목을 보면 1회만 호출 (Redis 키 없음)
@Slf4j
final class StockInfoLookup {

    private final KisApiClient kis;
    private final Map<String, Optional<KisStockInfo>> cache = new HashMap<>();

    StockInfoLookup(KisApiClient kis) {
        this.kis = kis;
    }

    // 실패도 캐시한다 — 같은 실행에서 같은 종목을 다시 부르지 않는다
    Optional<KisStockInfo> get(String ticker) {
        return cache.computeIfAbsent(ticker, t -> {
            try {
                return Optional.of(kis.getStockInfo(t));
            } catch (RuntimeException e) {
                log.warn("[Market] 종목정보 조회 실패 ticker={} error={}", t, e.getClass().getSimpleName());
                return Optional.empty();
            }
        });
    }
}
