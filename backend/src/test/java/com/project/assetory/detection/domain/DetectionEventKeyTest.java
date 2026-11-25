package com.project.assetory.detection.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §8.3 event_key 규칙 — 급등락 STOCK:{ticker}:{yyyy-MM-dd} (FR-DT-06 감지 멱등성)
class DetectionEventKeyTest {

    @Test
    @DisplayName("급등락 키는 STOCK:{종목코드}:{yyyy-MM-dd}")
    void stockKey() {
        assertThat(DetectionEventKey.stock("005930", LocalDate.of(2026, 11, 5))).isEqualTo("STOCK:005930:2026-11-05");
    }

    @Test
    @DisplayName("한 자리 월 · 일도 0을 채운다")
    void zeroPadded() {
        assertThat(DetectionEventKey.stock("000660", LocalDate.of(2026, 1, 5))).isEqualTo("STOCK:000660:2026-01-05");
    }

    @Test
    @DisplayName("같은 종목 · 날짜면 항상 같은 키, 날짜가 다르면 다른 키")
    void deterministic() {
        LocalDate date = LocalDate.of(2026, 11, 5);
        assertThat(DetectionEventKey.stock("005930", date)).isEqualTo(DetectionEventKey.stock("005930", date));
        assertThat(DetectionEventKey.stock("005930", date)).isNotEqualTo(DetectionEventKey.stock("005930", date.plusDays(1)));
    }

    @Test
    @DisplayName("종목코드나 날짜가 없으면 거부한다")
    void nullRejected() {
        assertThatThrownBy(() -> DetectionEventKey.stock(null, LocalDate.of(2026, 11, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectionEventKey.stock("  ", LocalDate.of(2026, 11, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectionEventKey.stock("005930", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
