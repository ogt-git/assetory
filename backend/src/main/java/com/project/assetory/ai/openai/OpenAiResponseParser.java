package com.project.assetory.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.ai.exception.OpenAiApiException;

/**
 * Chat Completions 응답 해석 (Q-5) — 필드명은 OpenAI 문서 기준 가정 형식이고 실호출(AI-7)로 대조한다.
 * - 2xx 본문: choices[0].message.content · choices[0].finish_reason · usage.prompt_tokens · usage.completion_tokens
 * - finish_reason=length → TRUNCATED (잘린 문장은 돌려주지 않는다)
 * - 내용 없음 · 공백 · message.refusal 있음 · finish_reason이 stop이 아님 · choices 없음 · 형식 오류 → INVALID_RESPONSE
 * - 토큰 필드가 없으면 null (0으로 채우지 않음)
 * - 실패 본문은 error.code만 꺼낸다(원문 메시지는 버림). 분류는 HTTP 상태로 한다
 * 예외 메시지 · 원인에 본문 내용을 넣지 않는다 — 공시 제목 등 입력 내용이 응답에 섞여 있을 수 있다
 */
public final class OpenAiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FINISH_STOP = "stop";
    private static final String FINISH_LENGTH = "length";

    private OpenAiResponseParser() {
    }

    public static OpenAiCompletion parse(String body) {
        JsonNode root = readTree(body);
        if (root == null) {
            throw error(OpenAiErrorType.INVALID_RESPONSE);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw error(OpenAiErrorType.INVALID_RESPONSE);
        }
        JsonNode choice = choices.get(0);
        String finishReason = text(choice, "finish_reason");
        if (FINISH_LENGTH.equals(finishReason)) {
            throw error(OpenAiErrorType.TRUNCATED);
        }
        JsonNode message = choice.path("message");
        String refusal = text(message, "refusal");
        String content = text(message, "content");
        if (!FINISH_STOP.equals(finishReason) || (refusal != null && !refusal.isBlank())
                || content == null || content.isBlank()) {
            throw error(OpenAiErrorType.INVALID_RESPONSE);
        }
        JsonNode usage = root.path("usage");
        return new OpenAiCompletion(content.strip(), integer(usage, "prompt_tokens"), integer(usage, "completion_tokens"),
                finishReason);
    }

    /** 실패 본문의 error.code — 없거나 JSON이 아니면 null */
    public static String errorCode(String body) {
        JsonNode root = readTree(body);
        if (root == null) return null;
        return text(root.path("error"), "code");
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException e) {
            // 파서 메시지에 본문 일부가 들어갈 수 있어 넘기지 않는다
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private static OpenAiApiException error(OpenAiErrorType type) {
        return new OpenAiApiException(type, null, null);
    }
}
