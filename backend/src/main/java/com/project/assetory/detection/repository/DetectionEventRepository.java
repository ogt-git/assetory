package com.project.assetory.detection.repository;

import com.project.assetory.detection.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 소유자 기준 조회만 둔다 (AGENTS 9장) — 키는 사용자마다 따로 유일하다
public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {

    boolean existsByUserIdAndEventKey(Long userId, String eventKey);

    Optional<DetectionEvent> findByUserIdAndEventKey(Long userId, String eventKey);
}
