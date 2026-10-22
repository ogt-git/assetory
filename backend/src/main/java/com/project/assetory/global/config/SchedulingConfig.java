package com.project.assetory.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 활성화 — 개별 배치는 각자 설정값으로 켜고 끈다 (예: assetory.sync.batch.enabled)
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
