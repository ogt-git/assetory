package com.project.assetory.ai.openai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * OpenAI Chat Completions 설정 — application.yml assetory.ai.openai.* (Q-5). 키 · 모델은 비어 있어도 기동되고,
 * 호출하면 HTTP 요청 없이 CONFIG_MISSING이다(DART · 네이버와 같은 방식). 모델 기본값은 두지 않는다 — 키를 발급할 때 정한다.
 *
 * @param maxOutputTokens 요청 max_completion_tokens (추론 계열 모델은 추론 토큰이 포함돼 상한이 작으면 length로 끝날 수 있다 — 실호출로 조정)
 * @param temperature     있을 때만 요청에 넣는다(일부 모델은 temperature를 받지 않는다). 기본 없음
 */
@Validated
@ConfigurationProperties("assetory.ai.openai")
public record OpenAiProperties(@NotBlank String baseUrl,
                               String apiKey,
                               String model,
                               @Min(1) int maxOutputTokens,
                               BigDecimal temperature,
                               @NotNull Duration connectTimeout,
                               @NotNull Duration readTimeout) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    // 설정 객체가 로그 · 예외에 찍혀도 키가 나오지 않게 (AGENTS 9장)
    @Override
    public String toString() {
        return "OpenAiProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "****" : "(없음)")
                + ", model=" + (hasModel() ? model : "(없음)") + ", maxOutputTokens=" + maxOutputTokens
                + ", temperature=" + temperature + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
