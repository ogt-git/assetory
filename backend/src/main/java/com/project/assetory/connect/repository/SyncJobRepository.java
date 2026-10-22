package com.project.assetory.connect.repository;

import com.project.assetory.connect.domain.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findAllByConnectedAccountIdOrderByIdAsc(Long connectedAccountId);
}
