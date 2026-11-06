package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.UserMarketEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 소유자 기준 조회만 둔다 (AGENTS 9장)
public interface UserMarketEventRepository extends JpaRepository<UserMarketEvent, Long> {

    Optional<UserMarketEvent> findByUserIdAndAnalysisId(Long userId, Long analysisId);

    // S-02 날짜 펼침의 이벤트 줄 (A6)
    List<UserMarketEvent> findAllByUserIdAndEventDate(Long userId, LocalDate eventDate);

    // S-02 날짜 상세 — 공유 분석(종목명 · 변동률)을 함께 읽는다(N+1 없음). 정렬은 서비스에서 |변동률| 큰 순
    @Query("""
            SELECT e FROM UserMarketEvent e JOIN FETCH e.analysis
            WHERE e.user.id = :userId AND e.eventDate = :date
            """)
    List<UserMarketEvent> findAllWithAnalysisByUserIdAndEventDate(@Param("userId") Long userId,
                                                                   @Param("date") LocalDate date);
}
