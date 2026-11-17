package com.project.assetory.analysis.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 원인 분석 설정 — application.yml assetory.analysis.*
 *
 * @param marketMove         시장 동반 판정 기준 (FR-CA-06, 01 §14 #12)
 * @param backfillCollectMax 연동 직후 소급 분석 중 한 번에 근거를 수집할 최대 건수 (Q-4). 넘는 분석은 PENDING으로 남는다
 * @param retry              PENDING 분석 재시도 (Q-6, ANL-7)
 */
@Validated
@ConfigurationProperties("assetory.analysis")
public record AnalysisProperties(@NotNull @Valid MarketMove marketMove, @Positive int backfillCollectMax,
                                 @NotNull @Valid Retry retry) {

    /**
     * @param maxExcessRate 근거 0건일 때 시장 동반(MARKET_MOVE)으로 볼 초과 변동 절댓값 상한 (%p, 경계 포함).
     *                      판정 규칙은 EvidenceStatusCalculator
     */
    public record MarketMove(@NotNull @PositiveOrZero BigDecimal maxExcessRate) {
    }

    /**
     * 재시도 횟수 컬럼이 없어 시간 창으로 상한을 둔다 — 분석 1건당 최대 window ÷ 주기(기본 48회). 창이 지난 PENDING은 그대로 둔다
     *
     * @param enabled   스케줄러 등록 여부 — 기본값은 일일 배치 설정(assetory.sync.batch.enabled)을 따른다
     * @param cron      실행 주기 (Asia/Seoul)
     * @param window    created_at 기준 재시도 창
     * @param maxPerRun 1회 실행에서 다시 수집할 최대 건수 — |변동률| 큰 순
     */
    public record Retry(boolean enabled, @NotBlank String cron, @NotNull Duration window, @Positive int maxPerRun) {
    }
}
