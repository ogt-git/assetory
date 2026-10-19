package com.project.assetory.asset.repository;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    // 증권 계좌는 연동 계정당 1행 (D-14)
    Optional<Asset> findByConnectedAccountIdAndAssetType(Long connectedAccountId, AssetType assetType);

    // 일별 자산 기록 배치 — 삭제되지 않은 수기 자산 전체 (배치 시작 시점의 balance)
    @Query("select a.id as id, a.user.id as userId, a.assetType as assetType, a.balance as balance from Asset a "
            + "where a.connectedAccount is null and a.active = true order by a.id")
    List<ManualSnapshotTarget> findActiveManualSnapshotTargets();

    interface ManualSnapshotTarget {
        Long getId();

        Long getUserId();

        AssetType getAssetType();

        BigDecimal getBalance();
    }

    // 소유자 검증 조회 — 다른 사용자의 자산 id면 빈 값
    Optional<Asset> findByIdAndUserId(Long id, Long userId);

    // 활성 수기 자산 (connected_account_id NULL)
    List<Asset> findAllByUserIdAndConnectedAccountIsNullAndActiveTrueOrderByIdAsc(Long userId);

    // 자산 조회 — 연동 계정·기관을 함께 로딩 (트랜잭션 안에서 상태·이름을 읽음)
    @EntityGraph(attributePaths = {"connectedAccount", "institution"})
    List<Asset> findAllByUserIdAndActiveTrueOrderByIdAsc(Long userId);
}
