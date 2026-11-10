package com.project.assetory.analysis.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 뉴스 근거 보관 기한 삭제 — application.yml assetory.evidence.news-retention.* (B3 확정 사항 8)
 * NAVER API HUB 검색 결과는 서버 보관 21일 이내(B0 A안). 하루 1회 실행이라 실제 최대 보관 = days + 1일 미만 → days 상한 20.
 *
 * @param enabled 기본 true(운영 켜짐), test · fake 프로필에서만 false
 * @param days    created_at 기준 보관 일수 — created_at &lt; 지금 − days인 NEWS 행을 지운다
 * @param cron    기본 매일 03:30 (Asia/Seoul) — 일일 배치 16:00 · 보정 07:30과 겹치지 않게
 */
@Validated
@ConfigurationProperties("assetory.evidence.news-retention")
public record NewsRetentionProperties(boolean enabled,
                                      @Min(1) @Max(20) int days,
                                      @NotBlank String cron) {
}
