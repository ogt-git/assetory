package com.project.assetory.analysis.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ANL-8 장 마감 후 판정 (FR-CA-08, AGENTS 5-C, Q-9 D2) — 근거 자료가 이벤트 날 장 마감 이후 공개됐는지. 스프링 없는 순수 계산이라
 * 조회 시점에 저장값(published_at · 접수일)으로 정한다(5-C "조회는 저장값"의 예외 — 계산 입력이 모두 저장값).
 * - 시각이 있으면 그 시각으로: 이벤트 날보다 뒤 → true, 앞 → false, 같은 날 → 장 마감 시각 이상이면 true(정각 포함 — 종가 확정 시점과 같거나 뒤)
 * - 시각이 없으면 날짜만(DART 공시는 시각을 주지 않는다): 뒤 → true, 앞 → false, 같은 날 → null(판정 불가 — 장 마감 후 자료를
 *   당일 원인처럼 보이게 하지 않으려고 false로 두지 않는다). 화면은 "시각 미제공"으로 보여준다
 * 장 마감 시각은 호출하는 쪽이 이벤트 날 기준으로 넘긴다(MarketCloseProperties.closeTime(date) — 장 마감 지연일 반영)
 */
public final class AfterCloseCalculator {

    private AfterCloseCalculator() {
    }

    public static Boolean afterClose(LocalDate publishedDate, LocalDateTime publishedAt, LocalDate eventDate, LocalTime closeTime) {
        if (publishedAt != null) {
            LocalDate date = publishedAt.toLocalDate();
            if (!date.isEqual(eventDate)) {
                return date.isAfter(eventDate);
            }
            return !publishedAt.toLocalTime().isBefore(closeTime);
        }
        if (publishedDate == null) {
            throw new IllegalArgumentException("공개 날짜 · 시각이 모두 없다");
        }
        if (publishedDate.isEqual(eventDate)) {
            return null;
        }
        return publishedDate.isAfter(eventDate);
    }
}
