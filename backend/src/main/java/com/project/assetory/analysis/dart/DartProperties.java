package com.project.assetory.analysis.dart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * OpenDART 설정 — application.yml assetory.dart.*. 키는 환경변수(DART_API_KEY)로만, 비어 있어도 기동되고 호출 시 실패한다.
 * 공시 목록 호출 타임아웃은 assetory.evidence.call-timeout(10초)을 쓴다.
 *
 * @param disclosureTypes 수집할 공시 유형(pblntf_ty, A~J) — 목록 응답에 유형 필드가 없어 유형마다 1회 호출. TODO(01-§14 #11) 임시값
 * @param maxPages        유형 1개당 최대 페이지(100건/페이지). 넘으면 수집기 실패
 * @param corpCodeTimeout 고유번호 ZIP(수 MB) 전용 읽기 타임아웃
 */
@Validated
@ConfigurationProperties("assetory.dart")
public record DartProperties(@NotBlank String baseUrl,
                             String apiKey,
                             @NotEmpty List<@Pattern(regexp = "[A-J]") String> disclosureTypes,
                             @Min(1) int maxPages,
                             @NotNull Duration corpCodeTimeout) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    // 설정 객체가 로그 · 예외에 찍혀도 키가 나오지 않게 (AGENTS 9장)
    @Override
    public String toString() {
        return "DartProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "****" : "(없음)")
                + ", disclosureTypes=" + disclosureTypes + ", maxPages=" + maxPages + ", corpCodeTimeout=" + corpCodeTimeout + "]";
    }
}
