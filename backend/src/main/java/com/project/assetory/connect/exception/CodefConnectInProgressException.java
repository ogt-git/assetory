package com.project.assetory.connect.exception;

// 동일 기관·방식 연동이 이미 진행 중일 때 발생 — Redis connect lock 획득 실패 시 사용
public class CodefConnectInProgressException extends RuntimeException {

    public CodefConnectInProgressException() {
        super("해당 금융기관 연동이 진행 중입니다. 잠시 후 다시 시도해주세요.");
    }
}
