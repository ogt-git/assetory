package com.project.assetory.analysis.service;

import com.project.assetory.analysis.config.NewsRetentionProperties;
import com.project.assetory.analysis.repository.EventSourceRetentionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 뉴스 근거 보관 기한 삭제 (B3 확정 사항 8) — NEWS AND created_at &lt; 지금 − days.
 * - evidence_count는 다시 세지 않는다(수집 시점 기록값, B1 확정 사항 3)
 * - 같은 시각에 다시 돌려도 결과가 같다(멱등). 기준 시각은 주입한 Clock(Asia/Seoul) — created_at도 JVM KST 시각이다
 * - 로그는 삭제 건수 · 기준 시각만
 */
@Slf4j
@Service
public class NewsEvidenceRetentionService {

    private final EventSourceRetentionRepository repository;
    private final NewsRetentionProperties properties;
    private final Clock clock;

    public NewsEvidenceRetentionService(EventSourceRetentionRepository repository, NewsRetentionProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public int purge() {
        return purgeAt(LocalDateTime.now(clock));
    }

    @Transactional
    public int purgeAt(LocalDateTime now) {
        LocalDateTime threshold = now.minusDays(properties.days());
        int deleted = repository.deleteNewsCreatedBefore(threshold);
        log.info("[Evidence] 뉴스 보관 기한 삭제 deleted={} threshold={}", deleted, threshold);
        return deleted;
    }
}
