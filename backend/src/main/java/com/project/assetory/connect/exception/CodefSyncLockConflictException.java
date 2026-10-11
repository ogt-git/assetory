package com.project.assetory.connect.exception;

// 동일 connectedId에 대한 동기화가 이미 진행 중일 때 발생
// 배치: 비활성화 없이 이번 사이클 스킵 / 수동: 사용자에게 안내
public class CodefSyncLockConflictException extends RuntimeException {

    public CodefSyncLockConflictException() {
        super("동기화가 이미 진행 중입니다. 잠시 후 다시 시도해 주세요.");
    }
}
