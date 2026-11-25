package com.project.assetory.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.ai.LlmPurpose;
import com.project.assetory.ai.exception.OpenAiApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q-5 · AI-7 OpenAI Chat Completions 실호출 검증 — 기본 `./gradlew test`에서는 제외되고 `./gradlew llmLiveTest`로만 돈다.
 * OPENAI_API_KEY가 있을 때만 실행(없으면 skip). 비용이 드는 호출은 L1 1회뿐이다(짧은 고정 프롬프트, 사용자 정보 · 실제 종목 데이터 없음).
 * 결과는 build/llm-live/에만 — 필드 이름 · 토큰 수 · finish_reason · 지연 · 해석 결과만 남기고, 응답 문장은 길이만 적는다.
 * 키 · 요청 헤더 · 응답 원문은 출력하지 않는다.
 */
@Tag("llm-live")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenAiLiveVerificationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Path OUT_DIR = Path.of("build", "llm-live");
    private static final StringBuilder REPORT = new StringBuilder();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 테스트 전용 고정 프롬프트 — 사용자 정보 · 실제 종목 · 공시 내용 없음
    private static final String SYSTEM = "너는 한국어로 한 문장만 답한다. 숫자를 지어내지 않는다.";
    private static final String USER = "'테스트' 라는 단어를 넣어 한 문장으로 인사해 줘.";
    /** 테스트 전용 — 실제 키가 아닌 고정 문자열. 인증 실패 응답(L2) 형식을 보려고 쓴다(과금 없음) */
    private static final String INVALID_KEY = "invalid-key-for-test";

    private static OpenAiProperties properties;
    private static RestClient restClient;

    @BeforeAll
    static void setUp() {
        properties = new OpenAiProperties(env("ASSETORY_AI_OPENAI_BASE_URL", "https://api.openai.com/v1"),
                System.getenv("OPENAI_API_KEY"), System.getenv("ASSETORY_AI_OPENAI_MODEL"),
                Integer.parseInt(env("ASSETORY_AI_OPENAI_MAX_OUTPUT_TOKENS", "300")),
                System.getenv("ASSETORY_AI_OPENAI_TEMPERATURE") == null || System.getenv("ASSETORY_AI_OPENAI_TEMPERATURE").isBlank()
                        ? null : new BigDecimal(System.getenv("ASSETORY_AI_OPENAI_TEMPERATURE")),
                Duration.ofSeconds(10), Duration.ofSeconds(10));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        line("# OpenAI 실호출 검증 결과 — " + LocalDateTime.now(KST).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        line("- base-url: " + properties.baseUrl());
        line("- model: " + (properties.hasModel() ? properties.model() : "(없음 — ASSETORY_AI_OPENAI_MODEL 미설정, L1 미실행)"));
        line("- max_completion_tokens: " + properties.maxOutputTokens() + " · temperature: "
                + (properties.temperature() == null ? "(보내지 않음)" : properties.temperature()));
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(OUT_DIR);
        Path file = OUT_DIR.resolve("verification-" + LocalDateTime.now(KST).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println("\n[llm-live] 결과 파일: " + file.toAbsolutePath());
    }

    @Test
    @Order(1)
    @DisplayName("L1 — 정상 호출 1회(과금): 응답 필드 구조 · 토큰 · finish_reason · 지연, 우리 파서 · 클라이언트 요청 형식과 대조")
    void l1_success() {
        line("\n## L1 정상 호출");
        if (!properties.hasModel()) {
            line("- 미실행 — 모델 미설정");
            return;
        }
        long start = System.nanoTime();
        ResponseEntity<String> response = post(properties.apiKey(), requestBody());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        line("- HTTP " + response.getStatusCode().value() + " · 지연 " + elapsedMs + "ms");
        JsonNode root = read(response.getBody());
        if (!response.getStatusCode().is2xxSuccessful()) {
            line("- 실패 — error.code=" + OpenAiResponseParser.errorCode(response.getBody()) + " · 분류="
                    + OpenAiErrorType.fromHttpStatus(response.getStatusCode().value(), OpenAiResponseParser.errorCode(response.getBody())));
            line("- error 필드: " + keys(root.path("error")));
            return;
        }
        line("- 최상위 필드: " + keys(root));
        JsonNode choice = root.path("choices").path(0);
        line("- choices 개수: " + root.path("choices").size() + " · choices[0] 필드: " + keys(choice));
        line("- choices[0].message 필드: " + keys(choice.path("message")));
        line("- finish_reason: " + choice.path("finish_reason").asText(null));
        line("- message.refusal: " + (choice.path("message").path("refusal").isNull() || choice.path("message").path("refusal").isMissingNode()
                ? "없음" : "있음"));
        line("- message.content 길이: " + choice.path("message").path("content").asText("").length() + "자 (내용은 남기지 않음)");
        line("- usage 필드: " + keys(root.path("usage")) + " · prompt_tokens=" + root.path("usage").path("prompt_tokens").asText(null)
                + " · completion_tokens=" + root.path("usage").path("completion_tokens").asText(null));
        JsonNode details = root.path("usage").path("completion_tokens_details");
        if (!details.isMissingNode()) {
            line("- usage.completion_tokens_details: " + keys(details) + " · reasoning_tokens=" + details.path("reasoning_tokens").asText(null));
        }
        try {
            OpenAiCompletion completion = OpenAiResponseParser.parse(response.getBody());
            line("- 우리 파서: 성공 · promptTokens=" + completion.promptTokens() + " · completionTokens=" + completion.completionTokens());
        } catch (OpenAiApiException e) {
            line("- 우리 파서: 실패 " + e.getErrorType() + " — 픽스처 · 파서를 고치기 전에 보고한다");
        }
    }

    @Test
    @Order(2)
    @DisplayName("L2 — 잘못된 키(과금 없음): 인증 실패 HTTP 상태 · error 필드 · error.code, 우리 분류(AUTH)와 대조")
    void l2_invalidKey() {
        line("\n## L2 잘못된 키 (과금 없음)");
        ResponseEntity<String> response = post(INVALID_KEY, requestBody());
        JsonNode root = read(response.getBody());
        String code = OpenAiResponseParser.errorCode(response.getBody());
        line("- HTTP " + response.getStatusCode().value() + " · error 필드: " + keys(root.path("error")) + " · error.code=" + code
                + " · error.type=" + root.path("error").path("type").asText(null));
        line("- 우리 분류: " + OpenAiErrorType.fromHttpStatus(response.getStatusCode().value(), code));
    }

    @Test
    @Order(3)
    @DisplayName("L3 — 우리 클라이언트로 같은 호출 1회(과금): 성공 여부 · 결과 유형만 (요청 형식 그대로 통하는지)")
    void l3_client() {
        line("\n## L3 OpenAiApiClient 경로");
        if (!properties.hasModel()) {
            line("- 미실행 — 모델 미설정");
            return;
        }
        OpenAiApiClient client = new OpenAiApiClient(restClient, properties, new OpenAiMetrics(new SimpleMeterRegistry()));
        try {
            OpenAiCompletion completion = client.complete(LlmPurpose.CAUSE, SYSTEM, USER);
            line("- 성공 · finish_reason=" + completion.finishReason() + " · 토큰 " + completion.promptTokens() + "/" + completion.completionTokens()
                    + " · 내용 " + completion.text().length() + "자");
        } catch (OpenAiApiException e) {
            line("- 실패 " + e.getErrorType() + " http=" + e.getHttpStatus() + " code=" + e.getErrorCode());
        }
    }

    private static ResponseEntity<String> post(String key, Map<String, Object> body) {
        return restClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(key)).body(body).retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(String.class);
    }

    // OpenAiApiClient와 같은 요청 형식
    private static Map<String, Object> requestBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.hasModel() ? properties.model() : "unset");
        body.put("messages", List.of(Map.of("role", "system", "content", SYSTEM), Map.of("role", "user", "content", USER)));
        body.put("max_completion_tokens", properties.maxOutputTokens());
        if (properties.temperature() != null) body.put("temperature", properties.temperature());
        return body;
    }

    private static JsonNode read(String body) {
        try {
            return body == null ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names.isEmpty() ? "(없음)" : String.join(", ", names);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void line(String text) {
        REPORT.append(text).append('\n');
        System.out.println(text);
    }
}
