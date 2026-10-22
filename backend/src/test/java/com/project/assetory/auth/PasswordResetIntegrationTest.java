package com.project.assetory.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetIntegrationTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "NewPassw0rd!";

    @Test
    @DisplayName("미가입 이메일로 요청해도 가입된 경우와 같은 응답, 메일은 가입된 경우에만 발송")
    void sameResponseRegardlessOfAccount() throws Exception {
        String registered = signupNew();
        String unknown = uniqueEmail();

        JsonNode forRegistered = body(requestReset(registered).andExpect(status().isOk()));
        JsonNode forUnknown = body(requestReset(unknown).andExpect(status().isOk()));

        for (String field : new String[]{"success", "data", "message", "errorCode"}) {
            assertThat(forUnknown.get(field)).isEqualTo(forRegistered.get(field));
        }
        verify(authMailSender, timeout(3000)).sendPasswordResetCode(eq(registered), anyString());
        verify(authMailSender, after(500).never()).sendPasswordResetCode(eq(unknown), anyString());
    }

    @Test
    @DisplayName("재발송 쿨다운은 가입 여부와 관계없이 똑같이 429")
    void cooldownAppliesToUnknownEmailToo() throws Exception {
        String registered = signupNew();
        String unknown = uniqueEmail();
        requestReset(registered).andExpect(status().isOk());
        requestReset(unknown).andExpect(status().isOk());

        requestReset(registered).andExpect(status().isTooManyRequests());
        requestReset(unknown).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("재설정 성공 → 새 비밀번호로 로그인, 이전 비밀번호 불가, 기존 RT 전부 사용 불가")
    void resetRevokesSessions() throws Exception {
        String email = signupNew();
        String rtDeviceA = loginRt(email, PASSWORD);
        String rtDeviceB = loginRt(email, PASSWORD);

        String code = requestResetCode(email);
        confirmReset(email, code, NEW_PASSWORD).andExpect(status().isOk());

        refresh(rtDeviceA).andExpect(status().isUnauthorized());
        refresh(rtDeviceB).andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("재설정 코드는 1회용 — 성공한 코드를 다시 쓰면 실패")
    void codeSingleUse() throws Exception {
        String email = signupNew();
        String code = requestResetCode(email);
        confirmReset(email, code, NEW_PASSWORD).andExpect(status().isOk());

        confirmReset(email, code, "Another0ne!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인증코드가 만료되었습니다. 다시 요청해주세요."));
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("새 비밀번호도 FR-AU-01 정책 적용 → 위반 시 400, 코드는 소비되지 않음")
    void newPasswordPolicy() throws Exception {
        String email = signupNew();
        String code = requestResetCode(email);

        confirmReset(email, code, "password1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        confirmReset(email, code, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("코드 불일치 5회 → 429, 이후 맞는 코드도 실패")
    void tooManyAttempts() throws Exception {
        String email = signupNew();
        String code = requestResetCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            confirmReset(email, wrong, NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        confirmReset(email, wrong, NEW_PASSWORD).andExpect(status().isTooManyRequests());
        confirmReset(email, code, NEW_PASSWORD).andExpect(status().isBadRequest());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private ResultActions requestReset(String email) throws Exception {
        return postJson("/api/auth/password-reset/request", Map.of("email", email));
    }

    private String requestResetCode(String email) throws Exception {
        requestReset(email).andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(authMailSender, timeout(3000)).sendPasswordResetCode(eq(email), code.capture());
        return code.getValue();
    }

    private ResultActions confirmReset(String email, String otp, String newPassword) throws Exception {
        return postJson("/api/auth/password-reset/confirm",
                Map.of("email", email, "otp", otp, "newPassword", newPassword));
    }

    private String loginRt(String email, String password) throws Exception {
        return login(email, password).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refreshToken").getValue();
    }

    private ResultActions refresh(String rt) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", rt)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
