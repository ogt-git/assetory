package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 공유 데이터(사용자 무관) — 사용자에게 보여줄 때는 user_market_events(소유자 기준)를 거쳐 들어간다
public interface StockEventAnalysisRepository extends JpaRepository<StockEventAnalysis, Long> {

    Optional<StockEventAnalysis> findByTickerAndEventDate(String ticker, LocalDate eventDate);

    /**
     * PENDING 분석 행을 넣고, 같은 (ticker, event_date)가 이미 있으면 아무것도 바꾸지 않는다(MySQL 업서트, A4 결정 E-5).
     * 동시 생성(일일 배치 · 연동 직후 소급)이 겹쳐도 1행만 생기고 예외가 나지 않아 호출자 트랜잭션이 rollback-only가 되지 않는다.
     * native라 BaseTimeEntity 감사 필드 · PENDING · 근거 0건을 직접 넣는다(Hibernate DDL에는 DB 기본값이 없음).
     * 시각은 JVM(Asia/Seoul) 값을 넘긴다 — DB NOW()는 서버 시간대를 따른다. 넣은 뒤 findByTickerAndEventDate로 다시 읽는다
     */
    @Modifying
    @Query(value = """
            INSERT INTO stock_event_analyses (ticker, stock_name, event_date, change_rate, evidence_count, analysis_status,
                                              created_at, updated_at)
            VALUES (:ticker, :stockName, :eventDate, :changeRate, 0, 'PENDING', :now, :now)
            ON DUPLICATE KEY UPDATE id = id""", nativeQuery = true)
    int insertPendingIfAbsent(@Param("ticker") String ticker, @Param("stockName") String stockName,
                              @Param("eventDate") LocalDate eventDate, @Param("changeRate") BigDecimal changeRate,
                              @Param("now") LocalDateTime now);

    /**
     * 재시도 대상 (Q-6, ANL-7) — 상태 · created_at ≥ since, |변동률| 큰 순 → created_at 오래된 순 → id. 인덱스 (analysis_status, created_at)로
     * 범위를 좁힌 뒤 정렬한다(ABS 정렬은 인덱스를 쓰지 못하지만 창 안 행 수가 작다). created_at은 업서트로 바뀌지 않는다
     */
    @Query("""
            select a from StockEventAnalysis a
            where a.analysisStatus = :status and a.createdAt >= :since
            order by abs(a.changeRate) desc, a.createdAt, a.id""")
    List<StockEventAnalysis> findRetryTargets(@Param("status") AnalysisStatus status, @Param("since") LocalDateTime since, Limit limit);

    /**
     * 있으면 그대로 재사용(값 · 상태 불변), 없으면 PENDING 업서트 뒤 다시 읽는다 (A4 결정 E-5) — 실시간 감지(A4) · 소급(A5) 공용.
     * 업서트가 @Modifying이라 호출자 트랜잭션 안에서 불러야 한다
     */
    default StockEventAnalysis findOrCreatePending(String ticker, String stockName, LocalDate eventDate, BigDecimal changeRate) {
        return findByTickerAndEventDate(ticker, eventDate).orElseGet(() -> {
            insertPendingIfAbsent(ticker, stockName, eventDate, changeRate, LocalDateTime.now());
            return findByTickerAndEventDate(ticker, eventDate).orElseThrow();
        });
    }
}
