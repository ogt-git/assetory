package com.project.assetory.ai.guard;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * LLM 출력 금지 패턴 — application.yml assetory.ai.output-guard.* (AI-3, Q-7). 규칙마다 정규식 목록이고, 하나라도 찾으면 그 규칙에 걸린다.
 * 문맥이 아니라 형태로 잡는다(SOLICIT은 "매수" 단어가 아니라 권유형만). 잘못된 정규식이면 검사기 빈 생성에서 기동이 실패한다
 */
@Validated
@ConfigurationProperties("assetory.ai.output-guard")
public record OutputGuardProperties(@NotEmpty List<String> causal,
                                    @NotEmpty List<String> solicit,
                                    @NotEmpty List<String> forecast) {
}
