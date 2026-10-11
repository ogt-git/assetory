package com.project.assetory.asset.repository;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    // 증권 계좌는 연동 계정당 1행 (D-14)
    Optional<Asset> findByConnectedAccountIdAndAssetType(Long connectedAccountId, AssetType assetType);
}
