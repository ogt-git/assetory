package com.project.assetory.analysis.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 원인 분석 설정 — application.yml assetory.analysis.*
 *
 * @param marketMove         시장 동반 판정 기준 (FR-CA-06, 01 §14 #12)
 * @param backfillCollectMax 연동 직후 소급 분석 중 한 번에 근거를 수집할 최대 건수 (Q-4). 넘는 분석은 PENDING으로 남는다
 */
@Validated
@ConfigurationProperties("assetory.analysis")
public record AnalysisProperties(@NotNull @Valid MarketMove marketMove, @Positive int backfillCollectMax) {

    /**
     * @param maxExcessRate 근거 0건일 때 시장 동반(MARKET_MOVE)으로 볼 초과 변동 절댓값 상한 (%p, 경계 포함).
     *                      판정 규칙은 EvidenceStatusCalculator
     */
    public record MarketMove(@NotNull @PositiveOrZero BigDecimal maxExcessRate) {
    }
}
