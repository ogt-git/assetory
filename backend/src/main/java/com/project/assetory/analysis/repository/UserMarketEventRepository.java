package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.user.domain.UserStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 소유자 기준 조회만 둔다 (AGENTS 9장)
public interface UserMarketEventRepository extends JpaRepository<UserMarketEvent, Long> {

    Optional<UserMarketEvent> findByUserIdAndAnalysisId(Long userId, Long analysisId);

    // 공유 분석에 실시간(소급 아닌) 사용자 이벤트가 하나라도 붙었는지 — 소급 전용 분석 판단 (Q-4). 사용자 정보는 돌려주지 않는다
    boolean existsByAnalysisIdAndBackfilledFalse(Long analysisId);

    boolean existsByAnalysisId(Long analysisId);

    // S-02 날짜 펼침의 이벤트 줄 (A6)
    List<UserMarketEvent> findAllByUserIdAndEventDate(Long userId, LocalDate eventDate);

    // S-02 날짜 상세 — 공유 분석(종목명 · 변동률)을 함께 읽는다(N+1 없음). 정렬은 서비스에서 |변동률| 큰 순
    @Query("""
            SELECT e FROM UserMarketEvent e JOIN FETCH e.analysis
            WHERE e.user.id = :userId AND e.eventDate = :date
            """)
    List<UserMarketEvent> findAllWithAnalysisByUserIdAndEventDate(@Param("userId") Long userId,
                                                                   @Param("date") LocalDate date);

    /**
     * 지난 이벤트 (ANL-10, FR-CA-13, Q-9 D4 · D6) — 같은 사용자 · 같은 종목에서 before보다 앞선 날짜, 최신순 최대 limit건. 소급 이벤트 포함.
     * 사용자 id를 조건에 넣은 조회(AGENTS 9장). 인덱스 (user_id, event_date)를 범위로 읽고 종목은 분석 PK 조인 뒤 거른다
     */
    @Query("""
            select new com.project.assetory.analysis.repository.PastEventRow(e.eventDate, e.direction, a.changeRate)
            from UserMarketEvent e join e.analysis a
            where e.user.id = :userId and a.ticker = :ticker and e.eventDate < :before
            order by e.eventDate desc""")
    List<PastEventRow> findPastEvents(@Param("userId") Long userId, @Param("ticker") String ticker,
                                      @Param("before") LocalDate before, Limit limit);

    /**
     * 요약 한도 차감 후보 (Q-8 D2, FR-AI-02) — 분석에 연결된 소급 아닌 이벤트의 활성(ACTIVE) 사용자 id, 이벤트 id 오름차순(먼저 감지된 순).
     * 한도가 남은 첫 사용자 1명만 차감한다 — 판단 · 차감은 AI-4(Redis). 인덱스는 analysis_id FK 인덱스(정렬 없이 id 순으로 읽힘)
     */
    default List<Long> findQuotaCandidateUserIds(Long analysisId) {
        return findLiveUserIdsByAnalysisId(analysisId, UserStatus.ACTIVE);
    }

    @Query("""
            select e.user.id from UserMarketEvent e
            where e.analysis.id = :analysisId and e.backfilled = false and e.user.status = :status
            order by e.id""")
    List<Long> findLiveUserIdsByAnalysisId(@Param("analysisId") Long analysisId, @Param("status") UserStatus status);

    /**
     * 요약 제공 반영 (ANL-6, Q-8 D3-①) — 요약이 생긴 분석에 연결된 이벤트 전체(소급 포함)를 summary_provided = true로.
     * 아직 false인 행만 바꿔 멱등이다(반환 = 새로 바꾼 행 수, 다시 부르면 0). 벌크 UPDATE는 @LastModifiedDate를 거치지 않아
     * updated_at을 직접 넣는다(JVM Asia/Seoul 시각 — native 업서트와 같은 관례). 같은 영속성 컨텍스트에 남은 이전 값이 보이지 않게 비운다.
     * 요약 서비스(AI-5)는 ANALYZED UPDATE 뒤 같은 트랜잭션에서 부른다 — StockEventAnalysisRepository.findOrCreatePending 공유 락과 짝(Q-8 d)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserMarketEvent e set e.summaryProvided = true, e.updatedAt = :now
            where e.analysis.id = :analysisId and e.summaryProvided = false""")
    int markSummaryProvided(@Param("analysisId") Long analysisId, @Param("now") LocalDateTime now);
}
