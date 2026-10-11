package com.project.assetory.connect.exception;

// 중복 로그인·부하 제한 (CF-12201, CF-01006, CF-12207, CF-12106)
// 즉시 재시도 금지, 연동 유지, 해당 회차 스킵
public class CodefCooldownException extends RuntimeException {

    private final String codefCode;

    public CodefCooldownException(String codefCode) {
        super("CODEF 쿨다운 필요: " + codefCode);
        this.codefCode = codefCode;
    }

    public String getCodefCode() {
        return codefCode;
    }
}
