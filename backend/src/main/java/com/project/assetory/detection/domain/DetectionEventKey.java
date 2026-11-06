package com.project.assetory.detection.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * docs/05 §8.3 event_key 규칙 — UNIQUE (user_id, event_key)로 같은 조건의 중복 감지를 막는다 (FR-DT-06).
 * 지금은 급등락 키만 둔다. 총자산 · 만기 · 브리핑 · 목표 · 결산 키는 각 기능을 만들 때 추가한다.
 */
public final class DetectionEventKey {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DetectionEventKey() {
    }

    /** 급등락 — STOCK:{ticker}:{yyyy-MM-dd} */
    public static String stock(String ticker, LocalDate date) {
        if (ticker == null || ticker.isBlank() || date == null) {
            throw new IllegalArgumentException("종목코드와 날짜가 있어야 감지 키를 만들 수 있습니다.");
        }
        return "STOCK:" + ticker + ":" + date.format(DATE);
    }
}
