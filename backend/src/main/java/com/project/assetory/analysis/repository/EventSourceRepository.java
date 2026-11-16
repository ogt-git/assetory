package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

// 공유 데이터(사용자 무관) — 사용자에게 보여줄 때는 user_market_events(소유자 기준)를 거쳐 들어간다
public interface EventSourceRepository extends JpaRepository<EventSource, Long> {

    /**
     * 근거 1건을 넣고, 같은 (analysis_id, external_id)가 이미 있으면 아무것도 바꾸지 않는다(MySQL 업서트 — A3 insertPendingIfAbsent와 같은 방식).
     * 두 수집기 · 재실행이 같은 근거를 다시 넣어도 예외가 나지 않아 호출자 트랜잭션이 rollback-only가 되지 않는다.
     * native라 감사 필드를 직접 넣는다. 시각은 JVM(Asia/Seoul) 값을 넘긴다. @Modifying이라 트랜잭션 안에서 부른다
     */
    @Modifying
    @Query(value = """
            INSERT INTO event_sources (analysis_id, source_type, title, source_name, published_at, url, snippet,
                                       relevance_rank, external_id, created_at, updated_at)
            VALUES (:analysisId, :sourceType, :title, :sourceName, :publishedAt, :url, :snippet,
                    :relevanceRank, :externalId, :now, :now)
            ON DUPLICATE KEY UPDATE id = id""", nativeQuery = true)
    int insertIfAbsent(@Param("analysisId") Long analysisId, @Param("sourceType") String sourceType,
                       @Param("title") String title, @Param("sourceName") String sourceName,
                       @Param("publishedAt") LocalDateTime publishedAt, @Param("url") String url,
                       @Param("snippet") String snippet, @Param("relevanceRank") Integer relevanceRank,
                       @Param("externalId") String externalId, @Param("now") LocalDateTime now);

    /**
     * 분석별 근거 — published_at 오름차순(타임라인), 시각 미제공(NULL)은 뒤, 같으면 저장 순(id).
     * 공시의 날짜(접수번호 앞 8자리)로 끼워 넣는 정렬은 표시 단계 몫이다
     */
    @Query("""
            select e from EventSource e
            where e.analysis.id = :analysisId
            order by case when e.publishedAt is null then 1 else 0 end, e.publishedAt, e.id""")
    List<EventSource> findAllByAnalysisIdOrderByPublishedAt(@Param("analysisId") Long analysisId);

    long countByAnalysisId(Long analysisId);

    /** 분석별 근거 유형별 건수 — 근거 상태 판정(공시 · 뉴스만 관련 근거로 센다)에 쓴다. 없는 유형은 행이 없다 */
    @Query("""
            select e.sourceType as sourceType, count(e) as count from EventSource e
            where e.analysis.id = :analysisId
            group by e.sourceType""")
    List<SourceTypeCount> countBySourceType(@Param("analysisId") Long analysisId);

    interface SourceTypeCount {
        SourceType getSourceType();

        long getCount();
    }
}
