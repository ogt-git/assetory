package com.project.assetory.auth.mail;

// 인증 메일 발송 — 메일은 인증 용도로만 쓴다. 구현은 AuthMailConfig에서 프로필·설정에 따라 고른다
public interface AuthMailSender {

    void sendVerificationCode(String to, String code);

    void sendPasswordResetCode(String to, String code);
}
