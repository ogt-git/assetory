package com.project.assetory.ai.openai;

import com.project.assetory.ai.LlmPurpose;
import com.project.assetory.ai.exception.OpenAiApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 (Q-5, FR-AI-01) — 요청 1회 → 해석된 결과 또는 분류된 예외. 원인 요약 · 목표 코멘트 · 월간 총평이 함께 쓴다.
 *
 * - 키 · 모델이 없으면 HTTP 요청 없이 CONFIG_MISSING
 * - 키는 Authorization: Bearer 헤더로만 보낸다(URL · 쿼리 · 로그 금지). 요청: model · messages(system · user) ·
 *   max_completion_tokens, temperature는 설정이 있을 때만 (실호출 AI-7로 대조)
 * - 호출 안에서 재시도하지 않는다 — 실패는 상위(요약 실패 → FAILED → 재시도 스케줄러) 몫. retryable이 아니면 ERROR 로그
 * - 로그: purpose · 결과 유형 · HTTP 상태 · error.code · 토큰 수 · 지연만. 프롬프트 · 응답 본문 · 헤더는 남기지 않는다
 * - RestClientException은 원인 예외를 붙이지 않고 NETWORK_ERROR로 바꾼다(메시지에 요청 정보가 들어갈 수 있다)
 */
@Slf4j
@Component
public class OpenAiApiClient {

    private static final String CHAT_URI = "/chat/completions";

    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final OpenAiMetrics metrics;

    public OpenAiApiClient(@Qualifier("openAiRestClient") RestClient restClient, OpenAiProperties properties, OpenAiMetrics metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    public OpenAiCompletion complete(LlmPurpose purpose, String system, String user) {
        if (!properties.hasApiKey() || !properties.hasModel()) {
            metrics.recordFailure(purpose, OpenAiErrorType.CONFIG_MISSING);
            log.error("[OPENAI] 설정 없음(재시도 안 함) purpose={} — OPENAI_API_KEY · ASSETORY_AI_OPENAI_MODEL key={} model={}",
                    purpose.tag(), properties.hasApiKey() ? "있음" : "없음", properties.hasModel() ? "있음" : "없음");
            throw new OpenAiApiException(OpenAiErrorType.CONFIG_MISSING, null, null);
        }
        long startNanos = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = restClient.post().uri(CHAT_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(properties.apiKey()))
                    .body(requestBody(system, user))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            metrics.recordLatency(startNanos);
            throw failure(purpose, new OpenAiApiException(OpenAiErrorType.NETWORK_ERROR, null, null), startNanos,
                    e.getClass().getSimpleName());
        }
        metrics.recordLatency(startNanos);

        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            String errorCode = OpenAiResponseParser.errorCode(response.getBody());
            throw failure(purpose, new OpenAiApiException(OpenAiErrorType.fromHttpStatus(status, errorCode), status, errorCode),
                    startNanos, null);
        }
        OpenAiCompletion completion;
        try {
            completion = OpenAiResponseParser.parse(response.getBody());
        } catch (OpenAiApiException e) {
            throw failure(purpose, new OpenAiApiException(e.getErrorType(), status, null), startNanos, null);
        }
        metrics.recordCall(purpose, OpenAiMetrics.RESULT_SUCCESS);
        metrics.recordTokens(purpose, completion.promptTokens(), completion.completionTokens());
        log.info("[OPENAI] 호출 완료 purpose={} promptTokens={} completionTokens={} elapsedMs={}", purpose.tag(),
                completion.promptTokens(), completion.completionTokens(), elapsedMs(startNanos));
        return completion;
    }

    private Map<String, Object> requestBody(String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("max_completion_tokens", properties.maxOutputTokens());
        if (properties.temperature() != null) {
            body.put("temperature", properties.temperature());
        }
        return body;
    }

    private OpenAiApiException failure(LlmPurpose purpose, OpenAiApiException e, long startNanos, String cause) {
        metrics.recordFailure(purpose, e.getErrorType());
        if (e.getErrorType().isRetryable()) {
            log.warn("[OPENAI] 실패 응답 purpose={} type={} http={} code={} error={} elapsedMs={}", purpose.tag(), e.getErrorType(),
                    e.getHttpStatus(), e.getErrorCode(), cause, elapsedMs(startNanos));
        } else {
            log.error("[OPENAI] 실패 응답(재시도 안 함) purpose={} type={} http={} code={} error={} elapsedMs={}", purpose.tag(),
                    e.getErrorType(), e.getHttpStatus(), e.getErrorCode(), cause, elapsedMs(startNanos));
        }
        return e;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
