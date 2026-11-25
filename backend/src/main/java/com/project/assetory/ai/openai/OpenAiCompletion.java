package com.project.assetory.ai.openai;

/**
 * Chat Completions 응답 1건 — 해석이 끝난 결과만 담는다(원문 없음).
 *
 * @param text             choices[0].message.content (공백 아님, finish_reason=stop)
 * @param promptTokens     usage.prompt_tokens — 없으면 null (0으로 채우지 않음)
 * @param completionTokens usage.completion_tokens — 없으면 null
 * @param finishReason     choices[0].finish_reason
 */
public record OpenAiCompletion(String text, Integer promptTokens, Integer completionTokens, String finishReason) {
}
