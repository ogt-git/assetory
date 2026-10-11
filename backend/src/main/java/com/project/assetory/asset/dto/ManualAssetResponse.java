package com.project.assetory.asset.dto;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.asset.domain.AssetType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ManualAssetResponse(
        Long id,
        AssetType assetType,
        AssetClass assetClass,
        String name,
        BigDecimal balance,
        LocalDateTime updatedAt
) {
    public static ManualAssetResponse from(Asset asset) {
        return new ManualAssetResponse(asset.getId(), asset.getAssetType(), asset.getAssetClass(),
                asset.getName(), asset.getBalance(), asset.getUpdatedAt());
    }
}
