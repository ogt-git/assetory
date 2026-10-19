package com.project.assetory.asset.repository;

import com.project.assetory.asset.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findAllByAssetId(Long assetId);

    List<Holding> findAllByAssetIdInOrderByIdAsc(List<Long> assetIds);

    // 활성 자산의 보유 종목 — 시세 수집 대상 합집합(FR-MK-07). 같은 종목이 여러 사용자에 있으면 행이 여러 개 온다
    @Query("SELECT h.ticker AS ticker, h.stockName AS stockName FROM Holding h WHERE h.asset.active = true ORDER BY h.ticker, h.id")
    List<TickerName> findActiveTickerNames();

    // STOCK/ETF 판별 대상 — security_type이 아직 NULL인 활성 보유 종목 (FR-CN-03)
    @Query("SELECT DISTINCT h.ticker FROM Holding h WHERE h.asset.active = true AND h.securityType IS NULL ORDER BY h.ticker")
    List<String> findActiveTickersWithoutSecurityType();

    List<Holding> findAllByTickerAndSecurityTypeIsNull(String ticker);

    interface TickerName {
        String getTicker();

        String getStockName();
    }

    @Modifying
    @Query("DELETE FROM Holding h WHERE h.asset.id = :assetId")
    void deleteAllByAssetId(@Param("assetId") Long assetId);
}
