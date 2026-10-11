package com.project.assetory.auth.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 로그 출력 발송기는 local 프로필 + log-only=true일 때만 선택돼야 한다
class AuthMailConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuthMailConfig.class)
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
            .withPropertyValues("assetory.mail.from=noreply@example.com");

    @Test
    @DisplayName("local + log-only=true → 로그 출력 구현")
    void localLogOnly_usesLogging() {
        runner.withPropertyValues("spring.profiles.active=local", "assetory.mail.log-only=true")
                .run(context -> assertThat(context.getBean(AuthMailSender.class))
                        .isInstanceOf(LoggingAuthMailSender.class));
    }

    @Test
    @DisplayName("prod에서 log-only=true를 켜도 SMTP 구현")
    void prodIgnoresLogOnly() {
        runner.withPropertyValues("spring.profiles.active=prod", "assetory.mail.log-only=true")
                .run(context -> assertThat(context.getBean(AuthMailSender.class))
                        .isInstanceOf(SmtpAuthMailSender.class));
    }

    @Test
    @DisplayName("local이어도 log-only가 꺼져 있으면 SMTP 구현")
    void localWithoutLogOnly_usesSmtp() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context.getBean(AuthMailSender.class))
                        .isInstanceOf(SmtpAuthMailSender.class));
    }

    @Test
    @DisplayName("SMTP 발신 주소가 없으면 기동 실패")
    void smtpWithoutFrom_fails() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthMailConfig.class)
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .run(context -> assertThat(context).hasFailed());
    }
}
