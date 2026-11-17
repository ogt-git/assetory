package com.project.assetory.ai.openai;

import com.project.assetory.ai.exception.OpenAiApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.project.assetory.ai.openai.OpenAiFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Chat Completions 응답 해석 (Q-5) — choices[0].message.content · finish_reason · usage. 실호출(AI-7) 전 가정 형식
class OpenAiResponseParserTest {

    @Test
    @DisplayName("정상 — 내용 · 토큰 수 · finish_reason=stop")
    void success() {
        OpenAiCompletion completion = OpenAiResponseParser.parse(fixture("chat-success.json"));

        assertThat(completion.text()).isEqualTo("같은 시점에 데모 공시가 있었습니다.");
        assertThat(completion.promptTokens()).isEqualTo(120);
        assertThat(completion.completionTokens()).isEqualTo(18);
        assertThat(completion.finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("토큰 필드가 없어도 성공 — 토큰은 null (0으로 채우지 않음)")
    void missingUsage() {
        OpenAiCompletion completion = OpenAiResponseParser.parse(fixture("chat-no-usage.json"));

        assertThat(completion.text()).isNotBlank();
        assertThat(completion.promptTokens()).isNull();
        assertThat(completion.completionTokens()).isNull();
    }

    @Test
    @DisplayName("finish_reason=length → TRUNCATED, 잘린 텍스트는 돌려주지 않는다")
    void truncated() {
        assertThatThrownBy(() -> OpenAiResponseParser.parse(fixture("chat-length.json")))
                .isInstanceOfSatisfying(OpenAiApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(OpenAiErrorType.TRUNCATED);
                    assertThat(e.getMessage()).doesNotContain("같은 시점");
                });
    }

    @ParameterizedTest(name = "[{index}] {0} → INVALID_RESPONSE")
    @DisplayName("내용 없음 · 공백 · 거절 · content_filter · choices 비어 있음 → INVALID_RESPONSE")
    @ValueSource(strings = {"chat-content-null.json", "chat-content-blank.json", "chat-refusal.json",
            "chat-content-filter.json", "chat-no-choices.json"})
    void invalid(String name) {
        assertThatThrownBy(() -> OpenAiResponseParser.parse(fixture(name)))
                .isInstanceOfSatisfying(OpenAiApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(OpenAiErrorType.INVALID_RESPONSE));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → INVALID_RESPONSE")
    @DisplayName("깨진 JSON · 객체가 아님 · 빈 본문 → INVALID_RESPONSE (파서 메시지를 넘기지 않는다)")
    @NullAndEmptySource
    @ValueSource(strings = {"{\"choices\": [", "[]", "not json"})
    void malformed(String body) {
        assertThatThrownBy(() -> OpenAiResponseParser.parse(body))
                .isInstanceOfSatisfying(OpenAiApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(OpenAiErrorType.INVALID_RESPONSE);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("실패 본문의 error.code만 꺼낸다 — 없거나 JSON이 아니면 null")
    void errorCode() {
        assertThat(OpenAiResponseParser.errorCode(fixture("error-insufficient-quota.json"))).isEqualTo("insufficient_quota");
        assertThat(OpenAiResponseParser.errorCode(fixture("error-rate-limit.json"))).isEqualTo("rate_limit_exceeded");
        assertThat(OpenAiResponseParser.errorCode("{\"error\": {\"message\": \"x\"}}")).isNull();
        assertThat(OpenAiResponseParser.errorCode("<html>")).isNull();
        assertThat(OpenAiResponseParser.errorCode(null)).isNull();
    }
}
