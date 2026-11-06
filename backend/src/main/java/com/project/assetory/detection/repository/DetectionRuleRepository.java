package com.project.assetory.detection.repository;

import com.project.assetory.detection.domain.DetectionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DetectionRuleRepository extends JpaRepository<DetectionRule, Long> {

    Optional<DetectionRule> findByRuleCode(String ruleCode);

    List<DetectionRule> findAllByActiveTrueOrderById();
}
