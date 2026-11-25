package com.project.assetory.detection.repository;

import com.project.assetory.detection.domain.UserDetectionSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 소유자 기준 조회만 둔다 (AGENTS 9장) — findById 후 소유자 비교로 쓰지 않는다
public interface UserDetectionSettingRepository extends JpaRepository<UserDetectionSetting, Long> {

    List<UserDetectionSetting> findAllByUserId(Long userId);

    Optional<UserDetectionSetting> findByUserIdAndDetectionRuleId(Long userId, Long detectionRuleId);
}
