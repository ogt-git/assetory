package com.project.assetory.market.kis;

import com.project.assetory.market.domain.IndicatorCode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 한투 업종코드 — application.yml assetory.kis.index-codes.* (B4 확정 사항 8).
 * KOSPI 0001은 공식 예제 기준, KOSDAQ 1001은 미확인 — TODO(B0 K1): kisLiveTest K1 결과로 확인한다
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
