package com.project.assetory.analysis.domain;

// docs/05 §5.3 user_market_events.notify_status — 생성 시 NONE, 발송 결과는 알림 파이프라인(12주차)이 바꾼다
public enum NotifyStatus {
    NONE,
    INDIVIDUAL,
    BUNDLED,
    SUPPRESSED
}
