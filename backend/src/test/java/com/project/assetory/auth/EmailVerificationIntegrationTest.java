package com.project.assetory.auth;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationIntegrationTest extends IntegrationTestSupport {

    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("코드 일치 → verificationToken 발급, 가입 시 email_verified_at 기록")
    void codeMatch_issuesToken_andSignupMarksVerified() throws Exception {
        String email = uniqueEmail();
        String token = verifyEmail(email);
        assertThat(token).isNotBlank();

        signup(email, PASSWORD, token).andExpect(status().isOk());

        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("인증코드는 원문이 아닌 SHA-256 해시로 auth:email-verify: 키에 저장, 쿨다운 60초")
    void codeStoredAsHash() throws Exception {
        String email = uniqueEmail();
        String code = requestVerifyCode(email);
        String emailHash = HashUtil.sha256Base64(email);

        assertThat(redis.opsForValue().get("auth:email-verify:code:" + emailHash))
                .isEqualTo(HashUtil.sha256Hex(code))
                .isNotEqualTo(code);
        assertThat(redis.getExpire("auth:email-verify:cooldown:" + emailHash, TimeUnit.SECONDS))
                .isBetween(55L, 60L);
    }

    @Test
    @DisplayName("코드 불일치 → 400")
    void codeMismatch() throws Exception {
        String email = uniqueEmail();
        String code = requestVerifyCode(email);

        confirmEmailVerify(email, wrong(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인증코드가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("코드 만료 → 400 (만료 안내)")
    void codeExpired() throws Exception {
        String email = uniqueEmail();
        String code = requestVerifyCode(email);
        redis.delete("auth:email-verify:code:" + HashUtil.sha256Base64(email)); // TTL 만료와 같은 상태

        confirmEmailVerify(email, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인증코드가 만료되었습니다. 다시 요청해주세요."));
    }

    @Test
    @DisplayName("5번째 불일치 → 429, 코드 폐기되어 맞는 코드도 더 이상 통과하지 않음")
    void tooManyAttempts() throws Exception {
        String email = uniqueEmail();
        String code = requestVerifyCode(email);

        for (int i = 1; i <= 4; i++) {
            confirmEmailVerify(email, wrong(code)).andExpect(status().isBadRequest());
        }
        confirmEmailVerify(email, wrong(code))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("인증 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요."));

        confirmEmailVerify(email, code).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("60초 안에 재발송 요청 → 429")
    void resendCooldown() throws Exception {
        String email = uniqueEmail();
        requestVerifyCode(email);

        requestEmailVerify(email).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("코드는 1회용 — 확인에 성공한 코드로 다시 확인하면 실패")
    void codeSingleUse() throws Exception {
        String email = uniqueEmail();
        String code = requestVerifyCode(email);
        confirmEmailVerify(email, code).andExpect(status().isOk());

        confirmEmailVerify(email, code).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("verificationToken은 1회용·이메일 전용 — 재사용하거나 다른 이메일로 가입하면 400")
    void verificationTokenSingleUseAndBound() throws Exception {
        String email = uniqueEmail();
        String other = uniqueEmail();
        String token = verifyEmail(email);

        signup(other, PASSWORD, token).andExpect(status().isBadRequest()); // 다른 이메일 → 거절 (토큰도 소비됨)
        signup(email, PASSWORD, token).andExpect(status().isBadRequest());

        String fresh = verifyEmailAfterCooldownReset(email);
        signup(email, PASSWORD, fresh).andExpect(status().isOk());
        signup(email, PASSWORD, fresh).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("verificationToken 없이 가입 → 400")
    void signupWithoutToken() throws Exception {
        postJson("/api/auth/signup", Map.of("email", uniqueEmail(), "password", PASSWORD,
                "nickname", "테스터", "termsVersion", "2026-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // 같은 이메일로 다시 인증받기 위해 쿨다운만 지운다 (테스트 편의)
    private String verifyEmailAfterCooldownReset(String email) throws Exception {
        redis.delete("auth:email-verify:cooldown:" + HashUtil.sha256Base64(email));
        org.mockito.Mockito.clearInvocations(authMailSender);
        return verifyEmail(email);
    }
}
