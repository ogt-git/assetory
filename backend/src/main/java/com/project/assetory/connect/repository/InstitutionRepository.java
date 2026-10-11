package com.project.assetory.connect.repository;

import com.project.assetory.connect.domain.Institution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    Optional<Institution> findByCodefOrgCode(String codefOrgCode);
}
