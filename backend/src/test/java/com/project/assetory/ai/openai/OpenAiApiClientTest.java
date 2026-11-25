package com.project.assetory.ai.openai;

import com.project.assetory.ai.LlmPurpose;
import com.project.assetory.ai.exception.OpenAiApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;

import static com.project.assetory.ai.openai.OpenAiFixtures.fixture;
import static com.project.assetory.ai.openai.OpenAiTestSupport.BASE_URL;
import static com.project.assetory.ai.openai.OpenAiTestSupport.TEST_KEY;
import static com.project.assetory.ai.openai.OpenAiTestSupport.TEST_MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// OpenAI Chat Completions HTTP 호출 (Q-5) — MockRestServiceServer(네이버 테스트와 같은 방식). 실호출 없음
@ExtendWith(OutputCaptureExtension.class)
class OpenAiApiClientTest {

    private static final String CHAT = BASE_URL + "/chat/completions";
    // 테스트 전용 고정 프롬프트 — 로그에 이 문자열이 나오면 유출이다
    private static final String SYSTEM = "TEST-ONLY-SYSTEM-PROMPT";
    private static final String USER = "TEST-ONLY-USER-PROMPT";

    private MockRestServiceServer server;
    private SimpleMeterRegistry registry;

    private OpenAiApiClient client(String key, String model, BigDecimal temperature) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        registry = new SimpleMeterRegistry();
        return new OpenAiApiClient(builder.build(), OpenAiTestSupport.properties(key, model, temperature), new OpenAiMetrics(registry));
    }

    private OpenAiApiClient client() {
        return client(TEST_KEY, TEST_MODEL, null);
    }

    private double calls(String result) {
        var counter = registry.find("llm.calls").tag("purpose", "cause").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double tokens(String kind) {
        var counter = registry.find("llm.tokens").tag("purpose", "cause").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("1. 정상 200 — POST /chat/completions, Bearer 헤더 · model · messages(system · user) · max_completion_tokens, 결과 · 토큰 · 메트릭")
    void success() {
        OpenAiApiClient client = client();
        server.expect(ExpectedCount.once(), requestTo(CHAT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TEST_KEY))
                .andExpect(requestTo(not(containsString(TEST_KEY))))
                .andExpect(jsonPath("$.model").value(TEST_MODEL))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(SYSTEM))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(USER))
                .andExpect(jsonPath("$.max_completion_tokens").value(300))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(fixture("chat-success.json"), MediaType.APPLICATION_JSON));

        OpenAiCompletion completion = client.complete(LlmPurpose.CAUSE, SYSTEM, USER);

        assertThat(completion.text()).isEqualTo("같은 시점에 데모 공시가 있었습니다.");
        assertThat(completion.promptTokens()).isEqualTo(120);
        assertThat(calls("success")).isEqualTo(1);
        assertThat(tokens("prompt")).isEqualTo(120);
        assertThat(tokens("completion")).isEqualTo(18);
        assertThat(registry.find("external.api.latency").tag("provider", "openai").tag("api", "chat").timer()).isNotNull();
        assertThat(registry.find("external.api.calls").counter()).isNull(); // 호출 수는 llm.calls로만 센다(중복 집계 방지)
        server.verify();
    }

    @Test
    @DisplayName("1-b. temperature 설정이 있으면 요청에 넣는다")
    void temperatureSent() {
        OpenAiApiClient client = client(TEST_KEY, TEST_MODEL, new BigDecimal("0.2"));
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.temperature").value(0.2))
                .andRespond(withSuccess(fixture("chat-success.json"), MediaType.APPLICATION_JSON));

        client.complete(LlmPurpose.CAUSE, SYSTEM, USER);

        server.verify();
    }

    @Test
    @DisplayName("1-c. 토큰 필드가 없으면 llm.tokens를 기록하지 않는다 (0으로 채우지 않음)")
    void noUsage_noTokenMetric() {
        OpenAiApiClient client = client();
        server.expect(requestTo(CHAT)).andRespond(withSuccess(fixture("chat-no-usage.json"), MediaType.APPLICATION_JSON));

        client.complete(LlmPurpose.CAUSE, SYSTEM, USER);

        assertThat(registry.find("llm.tokens").counter()).isNull();
        assertThat(calls("success")).isEqualTo(1);
    }

    @ParameterizedTest(name = "[{index}] http {0} · {1} → {2}")
    @DisplayName("2. 실패 상태 → 유형 · llm.calls{result} · 재시도 없음(요청 1회)")
    @CsvSource({
            "401, error-invalid-key.json, AUTH, auth",
            "403, error-invalid-key.json, AUTH, auth",
            "429, error-rate-limit.json, RATE_LIMIT, rate_limit",
            "429, error-insufficient-quota.json, QUOTA_EXCEEDED, quota_exceeded",
            "500, error-rate-limit.json, SERVER_ERROR, server_error",
            "503, error-rate-limit.json, SERVER_ERROR, server_error",
            "400, error-invalid-key.json, BAD_REQUEST, bad_request"
    })
    void failureStatus(int status, String body, OpenAiErrorType expected, String result) {
        OpenAiApiClient client = client();
        server.expect(ExpectedCount.once(), requestTo(CHAT))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(fixture(body)));

        Throwable thrown = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(thrown).isInstanceOfSatisfying(OpenAiApiException.class, e -> {
            assertThat(e.getErrorType()).isEqualTo(expected);
            assertThat(e.getHttpStatus()).isEqualTo(status);
        });
        assertThat(calls(result)).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("2-b. 401은 ERROR(재시도 안 함), 429 속도 초과는 WARN")
    void logLevels(CapturedOutput output) {
        OpenAiApiClient client = client();
        server.expect(requestTo(CHAT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("error-invalid-key.json")));
        server.expect(requestTo(CHAT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("error-rate-limit.json")));

        catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));
        catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(output.getAll()).containsPattern("ERROR.*\\[OPENAI\\] 실패 응답\\(재시도 안 함\\).*type=AUTH")
                .containsPattern("WARN.*\\[OPENAI\\] 실패 응답 .*type=RATE_LIMIT");
    }

    @Test
    @DisplayName("3. 읽기 타임아웃 → NETWORK_ERROR (원인 예외를 붙이지 않는다)")
    void timeout() {
        OpenAiApiClient client = client();
        server.expect(ExpectedCount.once(), requestTo(CHAT)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        Throwable thrown = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(thrown).isInstanceOfSatisfying(OpenAiApiException.class, e -> {
            assertThat(e.getErrorType()).isEqualTo(OpenAiErrorType.NETWORK_ERROR);
            assertThat(e.getCause()).isNull();
        });
        assertThat(calls("network_error")).isEqualTo(1);
        server.verify();
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @DisplayName("4. 200이지만 쓸 수 없는 응답 — 빈 내용 INVALID_RESPONSE · length TRUNCATED")
    @CsvSource({
            "chat-content-blank.json, INVALID_RESPONSE, invalid_response",
            "chat-length.json, TRUNCATED, truncated"
    })
    void unusable200(String body, OpenAiErrorType expected, String result) {
        OpenAiApiClient client = client();
        server.expect(ExpectedCount.once(), requestTo(CHAT)).andRespond(withSuccess(fixture(body), MediaType.APPLICATION_JSON));

        Throwable thrown = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(thrown).isInstanceOfSatisfying(OpenAiApiException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(expected));
        assertThat(calls(result)).isEqualTo(1);
        server.verify();
    }

    @ParameterizedTest(name = "[{index}] 키 \"{0}\" · 모델 \"{1}\" → 요청 0회")
    @DisplayName("5. 키 · 모델이 없으면 HTTP 요청 없이 CONFIG_MISSING")
    @CsvSource(nullValues = "null", value = {"null, test-model", "'', test-model", "TEST-ONLY-OPENAI-KEY-7f3a, null",
            "TEST-ONLY-OPENAI-KEY-7f3a, '  '"})
    void configMissing(String key, String model) {
        OpenAiApiClient client = client(key, model, null);
        server.expect(ExpectedCount.never(), requestToUriTemplate(CHAT));

        Throwable thrown = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(thrown).isInstanceOfSatisfying(OpenAiApiException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(OpenAiErrorType.CONFIG_MISSING));
        assertThat(calls("config_missing")).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("6. 로그 · 예외에 키 · 프롬프트 · 응답 내용이 남지 않는다 (성공 · 실패 모두)")
    void noSecretsInLogs(CapturedOutput output) {
        OpenAiApiClient client = client();
        server.expect(requestTo(CHAT)).andRespond(withSuccess(fixture("chat-success.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHAT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("error-invalid-key.json")));
        server.expect(requestTo(CHAT)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        client.complete(LlmPurpose.CAUSE, SYSTEM, USER);
        Throwable auth = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));
        Throwable timeout = catchThrowable(() -> client.complete(LlmPurpose.CAUSE, SYSTEM, USER));

        assertThat(output.getAll()).doesNotContain(TEST_KEY).doesNotContain(SYSTEM).doesNotContain(USER)
                .doesNotContain("같은 시점에 데모 공시가 있었습니다").doesNotContain("테스트 전용 — 잘못된 키");
        assertThat(auth.getMessage() + timeout.getMessage()).doesNotContain(TEST_KEY).doesNotContain(SYSTEM);
    }
}
