package com.project.assetory.snapshot.exception;

// 그 날짜에 사용자의 자산 기록이 없음 — 휴장일 · 연동 전 · 미래 날짜, 다른 사용자의 날짜도 같은 예외(존재 여부를 드러내지 않음)
public class SnapshotNotFoundException extends RuntimeException {

    public SnapshotNotFoundException() {
        super("자산 기록 없음");
    }
}
