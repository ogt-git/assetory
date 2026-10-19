package com.project.assetory.connect.repository;

import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    // 수동 새로고침 대상 — 사용자의 정상 증권 연동
    @EntityGraph(attributePaths = "institution")
    List<ConnectedAccount> findAllByUserIdAndBusinessTypeAndStatusOrderByIdAsc(Long userId, BusinessType businessType,
                                                                               ConnectedAccountStatus status);

    // 일별 자산 기록 배치 대상 — 기준 시각 T 이전에 만든 증권 연동 중 해지(DISCONNECTED)가 아닌 것 (05 §4.7 판정 분모)
    @Query("select c.id as id, c.user.id as userId, c.status as status from ConnectedAccount c "
            + "where c.businessType = :businessType and c.status <> :excluded and c.createdAt < :before order by c.id")
    List<SnapshotTarget> findSnapshotTargets(@Param("businessType") BusinessType businessType,
                                             @Param("excluded") ConnectedAccountStatus excluded,
                                             @Param("before") LocalDateTime before);

    interface SnapshotTarget {
        Long getId();

        Long getUserId();

        ConnectedAccountStatus getStatus();
    }

    // 배치 대상 — 계정마다 동기화 서비스가 다시 조회하므로 id만 가져온다
    @Query("select c.id from ConnectedAccount c where c.businessType = :businessType and c.status = :status order by c.id")
    List<Long> findIdsByBusinessTypeAndStatus(@Param("businessType") BusinessType businessType,
                                              @Param("status") ConnectedAccountStatus status);
}
