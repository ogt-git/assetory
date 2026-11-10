package com.project.assetory.analysis.naver;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * NAVER API HUB 뉴스 검색 설정 — application.yml assetory.naver.*. 키(Client ID · Secret)는 환경변수로만,
 * 비어 있어도 기동되고 호출 시 실패한다(B3 확정 사항 1). 호출 타임아웃은 assetory.evidence.call-timeout(10초).
 *
 * @param maxPages 최신순 최대 페이지(100건/페이지). 도달하면 실패가 아니라 그때까지 받은 것으로 끝낸다(확정 사항 6)
 */
@Validated
@ConfigurationProperties("assetory.naver")
public record NaverProperties(@NotBlank String baseUrl,
                              String clientId,
                              String clientSecret,
                              @Min(1) int maxPages) {

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    // 설정 객체가 로그 · 예외에 찍혀도 키가 나오지 않게 (AGENTS 9장)
    @Override
    public String toString() {
        return "NaverProperties[baseUrl=" + baseUrl + ", credentials=" + (hasCredentials() ? "****" : "(없음)")
                + ", maxPages=" + maxPages + "]";
    }
}
