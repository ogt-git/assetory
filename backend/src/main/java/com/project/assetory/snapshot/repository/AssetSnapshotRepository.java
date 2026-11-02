package com.project.assetory.snapshot.repository;

import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 모든 조회는 user_id 조건으로 한다 — UNIQUE (user_id, snapshot_date) 인덱스의 user_id 등치 + snapshot_date 범위 · 정렬
public interface AssetSnapshotRepository extends JpaRepository<AssetSnapshot, Long> {

    Optional<AssetSnapshot> findByUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);

    // 비교 기준 — 기준일 이전 직전 COMPLETE (PARTIAL · FAILED는 건너뜀)
    Optional<AssetSnapshot> findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(
            Long userId, SnapshotStatus status, LocalDate snapshotDate);

    // 최근 기록 (상태 무관 — 누락 표시용)
    Optional<AssetSnapshot> findFirstByUserIdOrderBySnapshotDateDesc(Long userId);

    // 최근 COMPLETE (현재 기준) · 첫 COMPLETE (누적 변화 기준)
    Optional<AssetSnapshot> findFirstByUserIdAndStatusOrderBySnapshotDateDesc(Long userId, SnapshotStatus status);

    Optional<AssetSnapshot> findFirstByUserIdAndStatusOrderBySnapshotDateAsc(Long userId, SnapshotStatus status);

    // 전월 · 전년 기준 — 목표 날짜 이하 마지막 COMPLETE
    Optional<AssetSnapshot> findFirstByUserIdAndStatusAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
            Long userId, SnapshotStatus status, LocalDate target);

    /**
     * 추이 점 목록 (FR-AN-05) — COMPLETE 행을 날짜 오름차순으로. from이 null이면 처음부터(첫 COMPLETE ~ to).
     * 시작 기준이 없는 경우(ALL · 기간 미달)에 첫 COMPLETE를 따로 조회하지 않도록 하한을 선택으로 둔다.
     */
    @Query("""
            SELECT s FROM AssetSnapshot s
            WHERE s.user.id = :userId AND s.status = com.project.assetory.snapshot.domain.SnapshotStatus.COMPLETE
              AND (:from IS NULL OR s.snapshotDate >= :from) AND s.snapshotDate <= :to
            ORDER BY s.snapshotDate ASC
            """)
    List<AssetSnapshot> findCompleteRange(@Param("userId") Long userId, @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    // 자산 기록 월별 목록 (FR-AS-06) — 그 기간 행 전체(상태 무관), 날짜 오름차순
    List<AssetSnapshot> findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(Long userId, LocalDate from, LocalDate to);

    // 기록이 있는 첫 · 최근 날짜(상태 무관) — 월 이동 범위 · 월 생략 시 기본 달. 기록이 없으면 둘 다 null
    @Query("SELECT MIN(s.snapshotDate) AS firstDate, MAX(s.snapshotDate) AS latestDate FROM AssetSnapshot s WHERE s.user.id = :userId")
    RecordRange findRecordRange(@Param("userId") Long userId);

    interface RecordRange {
        LocalDate getFirstDate();

        LocalDate getLatestDate();
    }

    /**
     * 기간 시장가격 영향 집계 (05 §4.7) — 끝(to)이 같은 세 구간 (monthFrom, to] · (yearFrom, to] · (firstFrom, to]의 COMPLETE 행을 한 번에.
     * 구간마다 market_change_amount 합 · 행 수 · market 값이 있는 행 수. 두 개수가 다르면 그 구간에 NULL이 있다.
     * 쓰지 않는 구간은 from = to로 넘기면 비어 있다.
     */
    @Query("""
            SELECT SUM(CASE WHEN s.snapshotDate > :monthFrom THEN s.marketChangeAmount END) AS monthSum,
                   COUNT(CASE WHEN s.snapshotDate > :monthFrom THEN 1 END) AS monthRows,
                   COUNT(CASE WHEN s.snapshotDate > :monthFrom THEN s.marketChangeAmount END) AS monthMarketRows,
                   SUM(CASE WHEN s.snapshotDate > :yearFrom THEN s.marketChangeAmount END) AS yearSum,
                   COUNT(CASE WHEN s.snapshotDate > :yearFrom THEN 1 END) AS yearRows,
                   COUNT(CASE WHEN s.snapshotDate > :yearFrom THEN s.marketChangeAmount END) AS yearMarketRows,
                   SUM(CASE WHEN s.snapshotDate > :firstFrom THEN s.marketChangeAmount END) AS firstSum,
                   COUNT(CASE WHEN s.snapshotDate > :firstFrom THEN 1 END) AS firstRows,
                   COUNT(CASE WHEN s.snapshotDate > :firstFrom THEN s.marketChangeAmount END) AS firstMarketRows
            FROM AssetSnapshot s
            WHERE s.user.id = :userId AND s.status = com.project.assetory.snapshot.domain.SnapshotStatus.COMPLETE
              AND s.snapshotDate > :lowestFrom AND s.snapshotDate <= :to
            """)
    MarketAggregate aggregateMarket(@Param("userId") Long userId, @Param("monthFrom") LocalDate monthFrom,
                                    @Param("yearFrom") LocalDate yearFrom, @Param("firstFrom") LocalDate firstFrom,
                                    @Param("lowestFrom") LocalDate lowestFrom, @Param("to") LocalDate to);

    interface MarketAggregate {
        BigDecimal getMonthSum();

        Long getMonthRows();

        Long getMonthMarketRows();

        BigDecimal getYearSum();

        Long getYearRows();

        Long getYearMarketRows();

        BigDecimal getFirstSum();

        Long getFirstRows();

        Long getFirstMarketRows();
    }
}
