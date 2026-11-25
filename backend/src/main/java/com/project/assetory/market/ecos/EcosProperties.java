package com.project.assetory.market.ecos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 한국은행 ECOS Open API 설정 — application.yml assetory.ecos.* (B4 확정 사항 9).
 * 키는 환경변수(ECOS_API_KEY)로만, 비어 있어도 기동되고 그 지표만 실패한다. 타임아웃 10초 (NFR-PF-05)
 */
@Validated
@ConfigurationProperties("assetory.ecos")
public record EcosProperties(@NotBlank String baseUrl,
                             String apiKey,
                             @NotNull Duration connectTimeout,
                             @NotNull Duration readTimeout) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    // 설정 객체가 로그 · 예외에 찍혀도 키가 나오지 않게 (AGENTS 9장)
    @Override
    public String toString() {
        return "EcosProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "****" : "(없음)")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
