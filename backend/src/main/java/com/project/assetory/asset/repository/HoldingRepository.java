package com.project.assetory.asset.repository;

import com.project.assetory.asset.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findAllByAssetId(Long assetId);

    @Modifying
    @Query("DELETE FROM Holding h WHERE h.asset.id = :assetId")
    void deleteAllByAssetId(@Param("assetId") Long assetId);
}
