package com.project.assetory.market.kis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 한투(KIS) Open API 설정 — application.yml assetory.kis.*
 * 키 · 시크릿은 환경변수로만 받는다. 비어 있어도 기동은 되고, 호출할 때 CONFIG_MISSING으로 실패한다.
 *
 * @param baseUrl            실전 https://openapi.koreainvestment.com:9443 (기본) / 모의 https://openapivts.koreainvestment.com:29443
 * @param callIntervalMs     호출 간 최소 간격 — 공식 예제 기준 실전 50ms(약 20건/초), 모의 500ms
 * @param tokenRefreshMargin 토큰 만료 전 여유 — 이 시간만큼 일찍 Redis에서 사라져 새로 발급한다
 */
@ConfigurationProperties("assetory.kis")
public record KisProperties(
        String baseUrl,
        String appKey,
        String appSecret,
        long callIntervalMs,
        Duration connectTimeout,
        Duration readTimeout,
        Duration tokenRefreshMargin,
        RateLimit rateLimit
) {

    // EGW00201(초당 거래건수 초과) 재시도 — 그 외 오류는 재시도하지 않는다
    public record RateLimit(int maxRetries, long backoffMs) {}

    boolean hasCredentials() {
        return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
    }
}
