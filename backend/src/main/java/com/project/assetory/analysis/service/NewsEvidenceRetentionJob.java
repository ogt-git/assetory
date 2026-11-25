package com.project.assetory.analysis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 뉴스 근거 보관 기한 삭제 트리거 — assetory.evidence.news-retention.enabled=true일 때만 등록된다
// (운영 기본 켜짐 — 약관상 서버 보관 21일 이내라 끄면 안 된다. test · fake 프로필에서만 끔). 기본 cron 매일 03:30
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assetory.evidence.news-retention.enabled", havingValue = "true")
public class NewsEvidenceRetentionJob {

    private final NewsEvidenceRetentionService retentionService;

    @Scheduled(cron = "${assetory.evidence.news-retention.cron:0 30 3 * * *}", zone = "Asia/Seoul")
    public void run() {
        retentionService.purge();
    }
}
