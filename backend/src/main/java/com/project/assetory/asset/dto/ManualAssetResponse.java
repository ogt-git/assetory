package com.project.assetory.asset.dto;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.DepositDetail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// depositDetail은 DEPOSIT·SAVINGS만 (CASH는 null)
public record ManualAssetResponse(
        Long id,
        AssetType assetType,
        AssetClass assetClass,
        String name,
        BigDecimal balance,
        DepositDetailResponse depositDetail,
        LocalDateTime updatedAt
) {
    public record DepositDetailResponse(BigDecimal interestRate, LocalDate startDate, LocalDate maturityDate,
                                        BigDecimal principal, Integer saveTerm, boolean autoRenew) {
        static DepositDetailResponse from(DepositDetail d) {
            return d == null ? null : new DepositDetailResponse(d.getInterestRate(), d.getStartDate(),
                    d.getMaturityDate(), d.getPrincipal(), d.getSaveTerm(), d.isAutoRenew());
        }
    }

    public static ManualAssetResponse from(Asset asset, DepositDetail detail) {
        return new ManualAssetResponse(asset.getId(), asset.getAssetType(), asset.getAssetClass(),
                asset.getName(), asset.getBalance(), DepositDetailResponse.from(detail), asset.getUpdatedAt());
    }
}
