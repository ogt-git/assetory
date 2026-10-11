package com.project.assetory.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// 메인 클래스가 아닌 별도 설정으로 둔다 — @WebMvcTest 같은 슬라이스 테스트에서 JPA 메타모델 없이도 뜨게 하기 위함
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
