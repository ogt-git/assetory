package com.project.assetory.auth.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class AuthMailConfig {

    // local 프로필 + assetory.mail.log-only=true일 때만 로그 출력 구현을 쓴다. 다른 프로필에서는 이 빈이 생기지 않는다
    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "assetory.mail.log-only", havingValue = "true")
    public AuthMailSender loggingAuthMailSender() {
        return new LoggingAuthMailSender();
    }

    // 위 빈이 없으면 실제 SMTP 발송 (같은 설정 클래스 안에서 선언 순서대로 평가된다)
    @Bean
    @ConditionalOnMissingBean(AuthMailSender.class)
    public AuthMailSender smtpAuthMailSender(JavaMailSender mailSender,
                                             @Value("${assetory.mail.from:}") String fromAddress) {
        return new SmtpAuthMailSender(mailSender, fromAddress);
    }
}
