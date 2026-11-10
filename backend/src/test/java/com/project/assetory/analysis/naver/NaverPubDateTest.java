package com.project.assetory.analysis.naver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// pubDate(RFC 1123, 영문 요일 · 월) → KST LocalDateTime
class NaverPubDateTest {

    @Test
    @DisplayName("+0900 → 그대로 KST 시각")
    void kstOffset() {
        assertThat(NaverPubDate.toKst("Thu, 05 Nov 2026 16:20:00 +0900")).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 20));
    }

    @Test
    @DisplayName("다른 오프셋도 KST로 바꾼다 — +0000 07:20 = KST 16:20, 날짜가 넘어가는 경우 포함")
    void otherOffset() {
        assertThat(NaverPubDate.toKst("Thu, 05 Nov 2026 07:20:00 +0000")).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 20));
        assertThat(NaverPubDate.toKst("Thu, 05 Nov 2026 20:00:00 +0000")).isEqualTo(LocalDateTime.of(2026, 11, 6, 5, 0));
    }

    @Test
    @DisplayName("해석할 수 없으면 IllegalArgumentException — 시각을 추측하지 않는다")
    void invalid() {
        assertThatThrownBy(() -> NaverPubDate.toKst("2026-11-05T16:20:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NaverPubDate.toKst(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NaverPubDate.toKst("목, 05 11월 2026 16:20:00 +0900")).isInstanceOf(IllegalArgumentException.class);
    }
}
