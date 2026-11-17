package com.project.assetory.analysis.domain;

import com.project.assetory.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// 생성 시 summary_provided (Q-8 D3-② · D5) — 분석이 ANALYZED(요약 있음)일 때만 true. 테스트 전용 더미 값
class UserMarketEventTest {

    @Test
    @DisplayName("분석이 ANALYZED면 처음부터 summary_provided = true")
    void analyzed_true() {
        assertThat(create(AnalysisStatus.ANALYZED).isSummaryProvided()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}이면 false")
    @DisplayName("ANALYZED가 아니면 false")
    @EnumSource(value = AnalysisStatus.class, names = "ANALYZED", mode = EnumSource.Mode.EXCLUDE)
    void notAnalyzed_false(AnalysisStatus status) {
        assertThat(create(status).isSummaryProvided()).isFalse();
    }

    private static UserMarketEvent create(AnalysisStatus status) {
        StockEventAnalysis analysis = StockEventAnalysis.create("000660", "테스트 종목", LocalDate.of(2026, 11, 5),
                new BigDecimal("-6.1"));
        // 테스트 전용 — 전이 메서드가 없는 상태(ANALYZED 등)를 직접 만든다
        ReflectionTestUtils.setField(analysis, "analysisStatus", status);
        User user = User.create("dummy@example.test", "dummy-hash-of-email", "dummy-hash", "테스터", "2026-10");
        return UserMarketEvent.create(user, analysis, Direction.DOWN, new BigDecimal("5"), null, null, null, false);
    }
}
