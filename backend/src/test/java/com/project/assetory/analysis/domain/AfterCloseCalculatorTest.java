package com.project.assetory.analysis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ANL-8 장 마감 후 판정 (FR-CA-08, Q-9 D2) — true / false / null(판정 불가). 15:30 정각은 장 마감 이후(true)로 본다
class AfterCloseCalculatorTest {

    private static final LocalDate EVENT = LocalDate.of(2026, 11, 16);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    @Test
    @DisplayName("같은 날 시각 있음 — 15:29는 장 마감 전, 15:30 정각 · 15:31은 장 마감 이후")
    void sameDayWithTime() {
        assertThat(judge(EVENT.atTime(15, 29))).isFalse();
        assertThat(judge(EVENT.atTime(15, 30))).isTrue();
        assertThat(judge(EVENT.atTime(15, 31))).isTrue();
    }

    @Test
    @DisplayName("시각 없음 — 같은 날은 판정 불가(null), 다음 날은 이후, 전날은 이전")
    void withoutTime() {
        assertThat(AfterCloseCalculator.afterClose(EVENT, null, EVENT, CLOSE)).isNull();
        assertThat(AfterCloseCalculator.afterClose(EVENT.plusDays(1), null, EVENT, CLOSE)).isTrue();
        assertThat(AfterCloseCalculator.afterClose(EVENT.minusDays(1), null, EVENT, CLOSE)).isFalse();
    }

    @Test
    @DisplayName("시각 있음 · 다른 날 — 다음 날 09:00은 이후, 전날 16:00은 이전(당일 장 시작 전 공개)")
    void otherDayWithTime() {
        assertThat(judge(EVENT.plusDays(1).atTime(9, 0))).isTrue();
        assertThat(judge(EVENT.minusDays(1).atTime(16, 0))).isFalse();
    }

    @Test
    @DisplayName("시각이 있으면 시각의 날짜를 쓴다 — 날짜 인자가 달라도 시각 기준")
    void timeWinsOverDate() {
        assertThat(AfterCloseCalculator.afterClose(EVENT.minusDays(3), EVENT.atTime(16, 0), EVENT, CLOSE)).isTrue();
    }

    @Test
    @DisplayName("장 마감 시각을 바꾸면 결과도 바뀐다 — 지연일 16:30이면 같은 날 16:00은 장 마감 전")
    void closeTimeChanges() {
        LocalTime lateClose = LocalTime.of(16, 30);
        assertThat(AfterCloseCalculator.afterClose(EVENT, EVENT.atTime(16, 0), EVENT, lateClose)).isFalse();
        assertThat(AfterCloseCalculator.afterClose(EVENT, EVENT.atTime(16, 30), EVENT, lateClose)).isTrue();
    }

    @Test
    @DisplayName("날짜 · 시각이 모두 없으면 호출 버그 — 예외")
    void noDateNoTime() {
        assertThatThrownBy(() -> AfterCloseCalculator.afterClose(null, null, EVENT, CLOSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Boolean judge(LocalDateTime publishedAt) {
        return AfterCloseCalculator.afterClose(publishedAt.toLocalDate(), publishedAt, EVENT, CLOSE);
    }
}
