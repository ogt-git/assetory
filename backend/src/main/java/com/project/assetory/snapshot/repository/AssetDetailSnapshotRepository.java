package com.project.assetory.snapshot.repository;

import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AssetDetailSnapshotRepository extends JpaRepository<AssetDetailSnapshot, Long> {

    List<AssetDetailSnapshot> findAllByAssetUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);

    // 같은 (asset_id, ticker)의 기준일(직전 COMPLETE 날짜) 행 — ticker NULL은 IS NULL로 따로 찾는다
    Optional<AssetDetailSnapshot> findByAssetIdAndTickerAndSnapshotDate(Long assetId, String ticker, LocalDate snapshotDate);

    Optional<AssetDetailSnapshot> findByAssetIdAndTickerIsNullAndSnapshotDate(Long assetId, LocalDate snapshotDate);
}
