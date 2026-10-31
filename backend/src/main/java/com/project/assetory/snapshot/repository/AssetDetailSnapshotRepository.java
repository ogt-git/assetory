package com.project.assetory.snapshot.repository;

import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AssetDetailSnapshotRepository extends JpaRepository<AssetDetailSnapshot, Long> {

    // 그 사용자 자산의 그날 행 전체 — 오늘 행 upsert와 기준일(직전 COMPLETE) 행 조회에 쓴다 (소유자 범위: asset.user)
    List<AssetDetailSnapshot> findAllByAssetUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);

    // 날짜별 상세 (FR-AS-06) — 자산을 함께 읽는다(자산별 묶음 · 이름 · 마스킹 계좌번호, N+1 없음). 비활성 자산 행도 포함
    @Query("""
            SELECT d FROM AssetDetailSnapshot d JOIN FETCH d.asset a
            WHERE a.user.id = :userId AND d.snapshotDate = :date
            ORDER BY a.id, d.ticker
            """)
    List<AssetDetailSnapshot> findAllWithAssetByUserIdAndSnapshotDate(@Param("userId") Long userId,
                                                                      @Param("date") LocalDate date);
}
