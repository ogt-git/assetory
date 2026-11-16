package com.project.assetory.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 잠정 종가 보정 트리거 — assetory.sync.confirm.enabled=true일 때만 등록된다 (기본값은 assetory.sync.batch.enabled를 따른다 — application.yml)
// 기본 cron은 영업일 07:30 — 한투 거래일 행이 확정되는 다음 거래일 아침(07:15 이전 관측, kis-verification V4-f) 뒤, 장 시작(09:00) 전
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assetory.sync.confirm.enabled", havingValue = "true")
public class SnapshotRevaluationScheduler {

    private final SnapshotRevaluationService revaluationService;

    @Scheduled(cron = "${assetory.sync.confirm.cron:0 30 7 * * MON-FRI}", zone = "Asia/Seoul")
    public void run() {
        revaluationService.revalueLatest();
    }
}
