package com.project.assetory.detection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 변화 감지 사용자 설정 — application.yml assetory.detection.*
 *
 * @param maxExcludedTickers  종목 급등락 알림에서 제외할 수 있는 종목 수 상한 (user_detection_settings.target_scope)
 * @param backfillTradingDays 과거 이벤트 소급(FR-DT-08) 범위 — 종목마다 오늘 이하 최신 N개 시세 행 ("최근 30일" = 20거래일, 04 FR-DT-08)
 */
@ConfigurationProperties("assetory.detection")
public record DetectionProperties(int maxExcludedTickers, int backfillTradingDays) {
}
