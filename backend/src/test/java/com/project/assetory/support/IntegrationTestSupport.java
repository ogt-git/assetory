package com.project.assetory.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.auth.mail.AuthMailSender;
import com.project.assetory.connect.codef.CodefApiClient;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MySQL·Redis 컨테이너를 테스트 JVM 전체에서 한 번만 띄워 공유한다 (Docker 필요). 메일·CODEF는 목으로 대체한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String PASSWORD = "Passw0rd!";

    // 부하가 큰 PC에서는 MySQL 기동이 2분(기본값)을 넘기기도 해서 대기 시간을 늘린다
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withStartupTimeoutSeconds(300);

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @MockitoBean protected AuthMailSender authMailSender;
    @MockitoBean protected CodefApiClient codefApiClient;
    @Autowired @Qualifier("codefSyncExecutor") private ThreadPoolTaskExecutor codefSyncExecutor;

    // 증권 연결 성공 뒤 비동기 최초 수집(InitialSyncListener)이 테스트 메서드보다 오래 돌면, 다음 테스트의 스텁 설정과
    // 같은 CodefApiClient 목을 동시에 호출해 스텁이 다른 메서드에 붙는다(Mockito 스텁은 스레드 안전하지 않음) → 간헐 실패.
    // 목 초기화(@MockitoBean reset — @AfterEach 뒤에 실행)보다 먼저 수집 executor가 빌 때까지 기다린다
    @AfterEach
    void awaitCodefSyncIdle() throws InterruptedException {
        ThreadPoolExecutor pool = codefSyncExecutor.getThreadPoolExecutor();
        long deadline = System.nanoTime() + 10_000_000_000L;
        int idleChecks = 0;
        // 큐에서 꺼낸 직후 · 실행 표시 전의 짧은 틈을 놓치지 않게 연속 2회 빈 상태를 확인한다
        while (idleChecks < 2) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("CODEF 수집 비동기 작업이 10초 안에 끝나지 않았습니다 active="
                        + pool.getActiveCount() + " queued=" + pool.getQueue().size());
            }
            boolean idle = pool.getActiveCount() == 0 && pool.getQueue().isEmpty();
            idleChecks = idle ? idleChecks + 1 : 0;
            Thread.sleep(20);
        }
    }

    protected String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected ResultActions postJson(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions requestEmailVerify(String email) throws Exception {
        return postJson("/api/auth/email-verify/request", Map.of("email", email));
    }

    // 인증코드 요청 → 목 메일에서 코드 가로채기
    protected String requestVerifyCode(String email) throws Exception {
        requestEmailVerify(email).andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(authMailSender).sendVerificationCode(eq(email.toLowerCase()), code.capture());
        return code.getValue();
    }

    protected ResultActions confirmEmailVerify(String email, String otp) throws Exception {
        return postJson("/api/auth/email-verify/confirm", Map.of("email", email, "otp", otp));
    }

    // 이메일 인증을 끝내고 verificationToken을 받는다
    protected String verifyEmail(String email) throws Exception {
        String code = requestVerifyCode(email);
        String body = confirmEmailVerify(email, code).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("verificationToken").asText();
    }

    protected ResultActions signup(String email, String password, String verificationToken) throws Exception {
        return postJson("/api/auth/signup", Map.of(
                "email", email, "password", password, "nickname", "테스터",
                "termsVersion", "2026-10", "verificationToken", verificationToken));
    }

    // 이메일 인증 + 가입까지 끝낸 새 계정
    protected String signupNew() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, verifyEmail(email)).andExpect(status().isOk());
        return email;
    }

    protected ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", Map.of("email", email, "password", password));
    }

    // 비동기 작업 대기 — 조건이 참이 될 때까지 최대 timeout 동안 확인
    protected static void awaitUntil(java.util.function.BooleanSupplier condition, java.time.Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("시간 안에 조건을 만족하지 못했습니다: " + timeout);
    }

    // 새 계정을 만들고 Access Token을 받는다
    protected String signupAndGetAccessToken() throws Exception {
        String body = login(signupNew(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
