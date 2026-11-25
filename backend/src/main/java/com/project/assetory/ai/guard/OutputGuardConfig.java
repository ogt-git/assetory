package com.project.assetory.ai.guard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutputGuardProperties.class)
public class OutputGuardConfig {

    // 패턴은 검사기 생성자에서 한 번만 컴파일한다 — 잘못된 정규식이면 여기서 기동이 실패한다
    @Bean
    public LlmOutputGuard llmOutputGuard(OutputGuardProperties properties) {
        return new LlmOutputGuard(properties);
    }
}
