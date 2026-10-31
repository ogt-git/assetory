package com.project.assetory.market.service;

import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.HoldingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 최초 연동 시 보유 종목 시세 소급 (FR-MK-04, 05 §11.1 · §4.9) — 그 계좌의 현재 보유 종목마다 한투 일자별 1회 응답을 전부 저장한다.
 * - 종목 시세(stock_prices)만 저장한다. 사용자 자산 기록(asset_snapshots · asset_detail_snapshots)은 만들지 않는다 (01 §3.1)
 * - 응답을 자르지 않는다 — 장기 거래정지 종목의 직전 종가 확보(C-04). "최근 20거래일" 범위는 데이터를 쓰는 곳에서 적용한다
 * - 종목 단위 실패 격리(FR-MK-05): 한 종목 실패는 로그만 남기고 다음 종목으로. 저장은 행 단위 트랜잭션이라 다른 종목에 영향 없음
 * - 한투 호출 중에는 DB 트랜잭션을 잡지 않는다 (호출 간격 대기 동안 커넥션을 붙잡지 않게)
 */
@Slf4j
@Service
public class StockPriceBackfillService {

    private final StockPriceCollectService collectService;
    private final HoldingRepository holdingRepository;

    public StockPriceBackfillService(StockPriceCollectService collectService, HoldingRepository holdingRepository) {
        this.collectService = collectService;
        this.holdingRepository = holdingRepository;
    }

    public BackfillResult backfillHoldings(Long assetId) {
        List<Holding> holdings = holdingRepository.findAllByAssetId(assetId);
        int succeeded = 0;
        int failed = 0;
        for (Holding holding : holdings) {
            try {
                int saved = collectService.backfill(holding.getTicker(), holding.getStockName());
                succeeded++;
                log.debug("[Backfill] 종목 소급 ticker={} saved={}", holding.getTicker(), saved);
            } catch (RuntimeException e) {
                // 종목코드만 남긴다 — 계좌 정보 · 응답 원문은 로그에 남기지 않는다 (AGENTS 9)
                failed++;
                log.warn("[Backfill] 종목 소급 실패 — 다음 종목으로 ticker={} error={}",
                        holding.getTicker(), e.getClass().getSimpleName());
            }
        }
        log.info("[Backfill] 보유 종목 소급 완료 종목={} 성공={} 실패={}", holdings.size(), succeeded, failed);
        return new BackfillResult(succeeded, failed);
    }

    /** 종목 단위 결과 — 응답에 저장할 행이 없었던 종목(상장 첫날 등)도 호출이 성공했으면 succeeded */
    public record BackfillResult(int succeeded, int failed) {}
}
