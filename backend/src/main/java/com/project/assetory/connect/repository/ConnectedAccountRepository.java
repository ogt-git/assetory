package com.project.assetory.connect.repository;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, Long> {

    // 소유자 검증 조회 — 다른 사용자의 연동 id면 빈 값
    @EntityGraph(attributePaths = "institution")
    Optional<ConnectedAccount> findByIdAndUserId(Long id, Long userId);

    // UNIQUE(user_id, institution_id, business_type) 기준 조회 — 재연결 시 기존 행 재사용
    @EntityGraph(attributePaths = "institution")
    Optional<ConnectedAccount> findByUserIdAndInstitutionIdAndBusinessType(Long userId, Long institutionId,
                                                                          BusinessType businessType);

    // 동기화용 — 트랜잭션 밖에서 기관 코드를 읽으므로 기관을 함께 로딩
    @EntityGraph(attributePaths = "institution")
    Optional<ConnectedAccount> findWithInstitutionById(Long id);

    @EntityGraph(attributePaths = "institution")
    List<ConnectedAccount> findAllByUserIdAndStatusNotOrderByIdAsc(Long userId, ConnectedAccountStatus status);
}
