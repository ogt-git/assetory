package com.project.assetory.snapshot.domain;

// docs/05 §4.7 asset_snapshots.status — 비교 · 감지 · 결산 · 목표 · 추이는 COMPLETE만 사용
public enum SnapshotStatus {
    COMPLETE,  // 판정 대상 전부 반영
    PARTIAL,   // 일부 누락 — 성공분만 기록, 계산에서 제외
    FAILED     // 성공한 대상 없음 — 금액 NULL
}
