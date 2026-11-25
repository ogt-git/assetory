package com.project.assetory.market.ecos;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// ECOS 테스트 공용 — 테스트 전용 더미 키 · 설정 · 픽스처(ECOS 개발 명세서 기준, B0 실호출로 필드 대조 완료(2026-11-16))
final class EcosTestSupport {

    static final String BASE_URL = "https://ecos.test/api";
    /** 테스트 전용 가짜 키 — 실제 키가 아니다. 예외 · 로그 · 메트릭 · toString에 나오면 유출이다 */
    static final String TEST_KEY = "TEST-ONLY-ECOS-KEY-5e8d";

    private EcosTestSupport() {
    }

    static EcosProperties properties(String apiKey) {
        return new EcosProperties(BASE_URL, apiKey, Duration.ofSeconds(10), Duration.ofSeconds(10));
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("ecos/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
