package com.project.assetory.asset.repository;

import com.project.assetory.asset.domain.DepositDetail;
import org.springframework.data.jpa.repository.JpaRepository;

// PK = asset_id
public interface DepositDetailRepository extends JpaRepository<DepositDetail, Long> {
}
