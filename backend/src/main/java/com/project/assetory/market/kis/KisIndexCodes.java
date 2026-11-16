package com.project.assetory.market.kis;

import com.project.assetory.market.domain.IndicatorCode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 한투 업종코드 — application.yml assetory.kis.index-codes.* (B4 확정 사항 8).
 * KOSPI 0001 · KOSDAQ 1001 — B0 K1 실호출로 확인(2026-11-16, 응답 이름 종합 · KOSDAQ)
 */
@Validated
@ConfigurationProperties("assetory.kis.index-codes")
public record KisIndexCodes(@NotBlank String kospi, @NotBlank String kosdaq) {

    /** KIS 지표의 업종코드. ECOS 지표면 IllegalArgumentException */
    public String codeOf(IndicatorCode code) {
        return switch (code) {
            case KOSPI -> kospi;
            case KOSDAQ -> kosdaq;
            default -> throw new IllegalArgumentException("한투 업종지수가 아니다: " + code);
        };
    }
}
