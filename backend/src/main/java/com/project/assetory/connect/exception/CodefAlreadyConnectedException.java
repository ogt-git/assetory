package com.project.assetory.connect.exception;

// 이미 활성화된 연동이 존재할 때 발생 — CODEF createAccount 호출 전 차단
public class CodefAlreadyConnectedException extends RuntimeException {

    public CodefAlreadyConnectedException() {
        super("이미 연동된 금융기관입니다.");
    }
}
