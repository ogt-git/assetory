package com.project.assetory.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.auth.mail.AuthMailSender;
import com.project.assetory.connect.codef.CodefApiClient;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
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

    // 새 계정을 만들고 Access Token을 받는다
    protected String signupAndGetAccessToken() throws Exception {
        String body = login(signupNew(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
