package com.project.assetory.auth.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpAuthMailSender implements AuthMailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpAuthMailSender(JavaMailSender mailSender, String fromAddress) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("메일 발신 주소(MAIL_FROM 또는 MAIL_USERNAME)가 설정되지 않았습니다.");
        }
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendVerificationCode(String to, String code) {
        send(to, "[Assetory] 이메일 인증코드",
                "안녕하세요, Assetory입니다.\n\n" +
                "이메일 인증코드: " + code + "\n\n" +
                "이 코드는 5분간 유효합니다.\n" +
                "본인이 요청하지 않으셨다면 이 이메일을 무시해주세요.");
    }

    @Override
    public void sendPasswordResetCode(String to, String code) {
        send(to, "[Assetory] 비밀번호 재설정 인증코드",
                "안녕하세요, Assetory입니다.\n\n" +
                "비밀번호 재설정 인증코드: " + code + "\n\n" +
                "이 코드는 5분간 유효합니다.\n" +
                "본인이 요청하지 않으셨다면 이 이메일을 무시해주세요.");
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }
}
