package com.project.assetory.auth.mail;

import com.project.assetory.global.crypto.MaskingUtil;
import lombok.extern.slf4j.Slf4j;

// local 프로필 전용 — 실제 발송 대신 인증코드를 로그로 출력한다 (AuthMailConfig에서 local 프로필일 때만 등록)
@Slf4j
public class LoggingAuthMailSender implements AuthMailSender {

    @Override
    public void sendVerificationCode(String to, String code) {
        log.info("[LocalMail] 이메일 인증코드 to={} code={}", MaskingUtil.maskEmail(to), code);
    }

    @Override
    public void sendPasswordResetCode(String to, String code) {
        log.info("[LocalMail] 비밀번호 재설정 인증코드 to={} code={}", MaskingUtil.maskEmail(to), code);
    }
}
