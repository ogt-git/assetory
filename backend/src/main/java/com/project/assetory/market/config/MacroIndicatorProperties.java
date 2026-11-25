package com.project.assetory.market.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 지수 · 거시지표 수집 설정 — application.yml assetory.market.indicator-lookback-days (B4 확정 사항 2).
 * MarketProperties와 같은 접두사지만 따로 둔다 — 생성자를 바꾸면 기존 테스트 · 호출부가 깨진다.
 *
 * @param indicatorLookbackDays 매 실행 [기준일 − N일, 기준일]을 받아 업서트. 잠정값은 다음 실행에서 고쳐진다.
 *                              가장 오래된 날짜는 직전 값이 없어 저장하지 않으므로 최소 2일
 */
@Validated
@ConfigurationProperties("assetory.market")
public record MacroIndicatorProperties(@Min(2) int indicatorLookbackDays) {
}
