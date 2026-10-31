package com.project.assetory.fake;

import com.project.assetory.auth.mail.AuthMailSender;
import com.project.assetory.auth.mail.LoggingAuthMailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * fake 프로필 MySQL · Redis · 메일 — 기동할 때마다 새 컨테이너(매번 초기화). docker-compose 로컬 DB는 건드리지 않는다.
 * 앱이 종료되면 컨테이너도 정리된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeInfraConfig {

    @Bean
    @ServiceConnection
    MySQLContainer<?> fakeMysql() {
        // 부하가 큰 PC에서는 MySQL 기동이 2분(기본값)을 넘기기도 해서 대기 시간을 늘린다 (IntegrationTestSupport와 같은 값)
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withStartupTimeoutSeconds(300);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> fakeRedis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    // 메일은 보내지 않고 인증코드를 로그로 출력한다 — 화면에서 가입 · 비밀번호 재설정을 시험할 때 로그에서 코드를 본다
    @Bean
    @Primary
    AuthMailSender fakeAuthMailSender() {
        return new LoggingAuthMailSender();
    }
}
