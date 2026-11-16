package com.project.assetory.analysis.dart;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// src/test/resources/dart/*.json — OpenDART 개발가이드 기준 더미 픽스처(B0 실호출로 필드 대조 완료(2026-11-16), 테스트 전용)
final class DartFixtures {

    private DartFixtures() {
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("dart/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
