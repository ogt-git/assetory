package com.project.assetory.analysis.naver;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// src/test/resources/naver/*.json — NAVER API HUB 뉴스 검색 문서 기준 더미 픽스처(테스트 전용 · 실응답 미대조)
final class NaverFixtures {

    private NaverFixtures() {
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("naver/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
