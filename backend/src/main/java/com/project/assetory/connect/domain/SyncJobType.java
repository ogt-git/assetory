package com.project.assetory.connect.domain;

// docs/05 §4.3 job_type. 증권은 CODEF 종합자산 1회 호출로 예수금·종목을 함께 받으므로 HOLDING 1행으로 기록한다 (BALANCE는 쓰지 않음)
public enum SyncJobType {
    BALANCE,
    HOLDING,
    DEPOSIT,
    BACKFILL  // 최초 연동 수집
}
