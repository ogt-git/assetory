package com.project.assetory.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestSupport {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ---------- 회원가입 ----------

    @Test
    @DisplayName("회원가입 성공")
    void signup_success() throws Exception {
        signup(uniqueEmail(), PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."));
    }

    @Test
    @DisplayName("회원가입: 이미 가입한 이메일 → 409 (대소문자만 다른 경우 포함)")
    void signup_duplicateEmail() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD).andExpect(status().isOk());

        signup(email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_EMAIL"));
        signup(email.toUpperCase(), PASSWORD)
                .andExpect(status().isConflict());

        // 대문자로 로그인해도 같은 계정
        login(email.toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "비밀번호 \"{0}\" → 400")
    @ValueSource(strings = {"Pa0!", "password1", "password!", "12345678!", "Passw0rd"})
    @DisplayName("회원가입: 비밀번호 정책 위반 (8자 미만, 특수문자·숫자·영문 누락) → 400")
    void signup_passwordPolicy(String password) throws Exception {
        signup(uniqueEmail(), password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ---------- 로그인 ----------

    @Test
    @DisplayName("로그인 성공 → ApiResponse.data에 AT, RT는 HttpOnly 쿠키 (본문에는 RT 없음)")
    void login_success() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);

        MockHttpServletResponse response = login(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.nickname").value("테스터"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();

        Cookie rt = response.getCookie("refreshToken");
        assertThat(rt).isNotNull();
        assertThat(rt.isHttpOnly()).isTrue();
        assertThat(rt.getPath()).isEqualTo("/api/auth");
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
    }

    @Test
    @DisplayName("로그인 실패: 비밀번호 불일치·미가입 이메일 → 401 (같은 메시지)")
    void login_failure() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);

        login(email, "Wrong0rd!").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"));
        login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    // ---------- 토큰 갱신 (RTR) ----------

    @Test
    @DisplayName("토큰 갱신 → 새 RT 발급, 이전 RT는 사용 불가")
    void refresh_rotates() throws Exception {
        String rt1 = loginAndGetRt(signupNew());

        String rt2 = refresh(rt1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse().getCookie("refreshToken").getValue();

        assertThat(rt2).isNotEqualTo(rt1);
        refresh(rt1).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("회전된 RT 재사용 → 해당 사용자의 모든 RT 무효화 (다른 기기 세션 포함)")
    void refresh_reuseRevokesAll() throws Exception {
        String email = signupNew();
        String deviceA = loginAndGetRt(email);
        String deviceB = loginAndGetRt(email);

        String deviceA2 = refresh(deviceA).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refreshToken").getValue();

        // 이미 회전된 deviceA 토큰이 다시 들어옴 → 재사용 탐지
        refresh(deviceA).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("리프레시 토큰이 재사용되었습니다. 다시 로그인해주세요."));

        refresh(deviceA2).andExpect(status().isUnauthorized());
        refresh(deviceB).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("갱신: 쿠키 없음·AT를 RT 자리에 넣음 → 401")
    void refresh_invalidInputs() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());

        String accessToken = loginAndGetAt(signupNew());
        refresh(accessToken).andExpect(status().isUnauthorized());
    }

    // ---------- 로그아웃 ----------

    @Test
    @DisplayName("로그아웃 → 쿠키 삭제, 그 RT는 사용 불가. 다른 기기 세션은 유지")
    void logout_revokesCurrentRt() throws Exception {
        String email = signupNew();
        MockHttpServletResponse loginResponse = login(email, PASSWORD).andReturn().getResponse();
        String accessToken = json(loginResponse).get("data").get("accessToken").asText();
        String rt = loginResponse.getCookie("refreshToken").getValue();
        String otherDevice = loginAndGetRt(email);

        MockHttpServletResponse logout = mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refreshToken", rt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse();
        assertThat(logout.getCookie("refreshToken").getMaxAge()).isZero();

        refresh(rt).andExpect(status().isUnauthorized());
        refresh(otherDevice).andExpect(status().isOk());
    }

    // ---------- 보안 설정 ----------

    @Test
    @DisplayName("인증 없이 보호 경로 접근 → 401 공통 응답 JSON")
    void unauthenticated_returnsJson401() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"));
    }

    @Test
    @DisplayName("RT를 Authorization 헤더에 넣으면 인증되지 않음 → 401")
    void refreshTokenAsBearer_rejected() throws Exception {
        String rt = loginAndGetRt(signupNew());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + rt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("헬스체크는 인증 없이 200")
    void health_permitted() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private String signupNew() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD).andExpect(status().isOk());
        return email;
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email, "password", password,
                        "nickname", "테스터", "termsVersion", "2026-10"))));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", refreshToken)));
    }

    private String loginAndGetRt(String email) throws Exception {
        return login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refreshToken").getValue();
    }

    private String loginAndGetAt(String email) throws Exception {
        return json(login(email, PASSWORD).andReturn().getResponse()).get("data").get("accessToken").asText();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }
}
