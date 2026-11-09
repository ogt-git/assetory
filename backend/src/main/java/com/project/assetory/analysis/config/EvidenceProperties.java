package com.project.assetory.analysis.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 근거 수집 설정 — application.yml assetory.evidence.*
 *
 * @param callTimeout 수집기의 외부 호출 1회 연결 · 읽기 타임아웃 (NFR-PF-05, 외부 API 10초)
 * @param disclosure  공시 수집 범위 (B2)
 * @param news        뉴스 수집 범위 (B3)
 */
@Validated
@ConfigurationProperties("assetory.evidence")
public record EvidenceProperties(@NotNull Duration callTimeout,
                                 @NotNull @Valid Window disclosure,
                                 @NotNull @Valid Window news) {

    /**
     * 이벤트일 기준 수집 범위(달력일) — [eventDate − daysBefore, eventDate + daysAfter].
     * 게시 시각으로 자르지 않는다(01 §14 #7). 값은 B0 실호출(N6 게시 시각 분포 · D4 공시 분포)로 확정한다
     */
    public record Window(@PositiveOrZero int daysBefore, @PositiveOrZero int daysAfter) {
    }
}
