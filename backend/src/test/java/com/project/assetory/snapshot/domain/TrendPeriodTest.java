package com.project.assetory.snapshot.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 총자산 추이 기간 (FR-AN-05) — 1M · 1Y의 목표 날짜는 B4 전월 · 전년 기준(AssetChangeCalculator)과 같다
class TrendPeriodTest {

    @Test
    @DisplayName("1M 목표 날짜: 3/31 → 2/28(평년) · 2/29(윤년), 1/15 → 전년 12/15 (B4 monthTarget과 같음)")
    void oneMonthTarget() {
        assertThat(TrendPeriod.ONE_MONTH.targetDate(LocalDate.of(2026, 3, 31))).contains(LocalDate.of(2026, 2, 28));
        assertThat(TrendPeriod.ONE_MONTH.targetDate(LocalDate.of(2028, 3, 31))).contains(LocalDate.of(2028, 2, 29));
        assertThat(TrendPeriod.ONE_MONTH.targetDate(LocalDate.of(2027, 1, 15))).contains(LocalDate.of(2026, 12, 15));
    }

    @Test
    @DisplayName("1Y 목표 날짜: 2/29 → 전년 2/28 (B4 yearTarget과 같음)")
    void oneYearTarget() {
        assertThat(TrendPeriod.ONE_YEAR.targetDate(LocalDate.of(2028, 2, 29))).contains(LocalDate.of(2027, 2, 28));
        assertThat(TrendPeriod.ONE_YEAR.targetDate(LocalDate.of(2026, 10, 1))).contains(LocalDate.of(2025, 10, 1));
    }

    @Test
    @DisplayName("ALL은 목표 날짜가 없다 (시작점 = 첫 COMPLETE)")
    void allHasNoTarget() {
        assertThat(TrendPeriod.ALL.targetDate(LocalDate.of(2026, 10, 1))).isEmpty();
    }

    @Test
    @DisplayName("문자열 매핑: 1M · 1Y · ALL ↔ enum (대문자 정확히 일치)")
    void codeMapping() {
        assertThat(TrendPeriod.fromCode("1M")).isEqualTo(TrendPeriod.ONE_MONTH);
        assertThat(TrendPeriod.fromCode("1Y")).isEqualTo(TrendPeriod.ONE_YEAR);
        assertThat(TrendPeriod.fromCode("ALL")).isEqualTo(TrendPeriod.ALL);
        assertThat(TrendPeriod.ONE_MONTH.code()).isEqualTo("1M");
        assertThat(TrendPeriod.ONE_YEAR.code()).isEqualTo("1Y");
        assertThat(TrendPeriod.ALL.code()).isEqualTo("ALL");
    }

    @ParameterizedTest(name = "\"{0}\" → 실패")
    @ValueSource(strings = {"3M", "1m", "1y", "all", " 1M", "ONE_MONTH"})
    @NullAndEmptySource
    @DisplayName("허용하지 않는 값(다른 기간 · 소문자 · 공백 · enum 이름 · 빈 문자열 · null) → IllegalArgumentException")
    void invalidCode(String code) {
        assertThatThrownBy(() -> TrendPeriod.fromCode(code)).isInstanceOf(IllegalArgumentException.class);
    }
}
