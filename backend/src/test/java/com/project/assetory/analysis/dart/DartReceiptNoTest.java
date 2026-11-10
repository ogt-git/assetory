package com.project.assetory.analysis.dart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 접수번호 앞 8자리 = 접수일 (01 §14 #8 — published_at NULL인 공시의 날짜)
class DartReceiptNoTest {

    @Test
    @DisplayName("접수번호 앞 8자리 → 날짜")
    void dateOf() {
        assertThat(DartReceiptNo.dateOf("20261105900001")).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    @DisplayName("14자리 숫자만 유효")
    void isValid() {
        assertThat(DartReceiptNo.isValid("20261105900001")).isTrue();
        assertThat(DartReceiptNo.isValid("2026110590000")).isFalse();
        assertThat(DartReceiptNo.isValid("2026110590000A")).isFalse();
        assertThat(DartReceiptNo.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026", "20261332900001", "abcdefgh900001"})
    @DisplayName("형식이 틀리거나 없는 날짜면 예외 — 날짜를 추측하지 않는다")
    void invalid(String receiptNo) {
        assertThatThrownBy(() -> DartReceiptNo.dateOf(receiptNo)).isInstanceOf(IllegalArgumentException.class);
    }
}
