package com.project.assetory.analysis.repository;

import com.project.assetory.analysis.domain.EventSource;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 근거 보관 기한 삭제 전용 (B3) — B1 EventSourceRepository는 바꾸지 않고 따로 둔다.
 * 엔티티 단위로 읽어 지우지 않고 쿼리 1회로 지운다. EventSource는 @Immutable이라 native로 지운다.
 * 인덱스는 추가하지 않는다 — 현재 규모(종목 · 날짜당 수십 행)에서는 전체 스캔으로 충분하다(필요하면 (source_type, created_at) 제안)
 */
public interface EventSourceRetentionRepository extends Repository<EventSource, Long> {

    /** source_type = NEWS AND created_at &lt; threshold — 정확히 기준 시각인 행은 남긴다 */
    @Modifying
    @Query(value = "DELETE FROM event_sources WHERE source_type = 'NEWS' AND created_at < :threshold", nativeQuery = true)
    int deleteNewsCreatedBefore(@Param("threshold") LocalDateTime threshold);
}
