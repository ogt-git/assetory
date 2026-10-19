package com.project.assetory.market.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties(MarketProperties.class)
public class MarketConfig {

    // 거래일 · 장 마감 가드 판정용 현재 시각 (Asia/Seoul) — 테스트에서 고정 시각으로 바꾼다
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
