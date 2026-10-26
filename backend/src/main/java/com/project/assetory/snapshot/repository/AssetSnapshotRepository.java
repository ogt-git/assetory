package com.project.assetory.snapshot.repository;

import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AssetSnapshotRepository extends JpaRepository<AssetSnapshot, Long> {

    Optional<AssetSnapshot> findByUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);

    // 비교 기준 — 기준일 이전 직전 COMPLETE (PARTIAL · FAILED는 건너뜀)
    Optional<AssetSnapshot> findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(
            Long userId, SnapshotStatus status, LocalDate snapshotDate);
}
