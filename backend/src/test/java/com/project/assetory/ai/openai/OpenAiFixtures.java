package com.project.assetory.ai.openai;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// src/test/resources/openai/*.json — Chat Completions 문서 기준 더미 픽스처(테스트 전용 · 실호출 확인 전 가정 형식, AI-7에서 대조)
final class OpenAiFixtures {

    private OpenAiFixtures() {
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("openai/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
