package com.project.assetory.ai.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.List;

import static com.project.assetory.ai.guard.OutputGuardFixtures.TITLE_BUYBACK;
import static com.project.assetory.ai.guard.OutputGuardFixtures.TITLE_OPTION;
import static com.project.assetory.ai.guard.OutputGuardFixtures.TITLE_RECOMMEND;
import static com.project.assetory.ai.guard.OutputGuardFixtures.context;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 원인 요약 출력 검사기 (AI-3, Q-7) — Phase 0에서 승인된 패턴 · 숫자 규칙이 기대값이다.
 * 테스트 전용 — 모든 요약 문장은 지어낸 예시다(실제 LLM 응답 원문 아님). 종목 · 공시 제목은 더미 값(OutputGuardFixtures)
 */
class LlmOutputGuardTest {

    private final LlmOutputGuard guard = OutputGuardFixtures.guard();

    private OutputGuardResult check(String summary) {
        return guard.check(summary, context());
    }

    @Nested
    @DisplayName("CAUSAL — 인과 단정 (AGENTS 5-D, FR-CA-08)")
    class Causal {

        @ParameterizedTest
        @ValueSource(strings = {
                "신제품 발표 때문에 7.2% 올랐습니다.",
                "이로 인해 주가가 크게 움직였습니다.",
                "같은 시점에 공시가 있었으나, 이 공시 때문이라고 단정할 수는 없습니다.",
                "실적 개선 덕분에 상승했습니다.",
                "대규모 수주가 주가 상승을 견인했습니다."})
        @DisplayName("잡힌다 — 면책 문장 속 '때문'도 형태로 잡는다(안전한 쪽)")
        void caught(String summary) {
            assertThat(check(summary).violations()).containsExactly(OutputRule.CAUSAL);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "같은 시점에 자기주식 취득 결정 공시가 있었습니다.",
                "같은 날 시장 전체가 1.2% 움직였습니다."})
        @DisplayName("통과한다")
        void passes(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("SOLICIT — 투자 권유 (5-D, FR-CA-09): 단어가 아니라 권유형만")
    class Solicit {

        @ParameterizedTest
        @ValueSource(strings = {
                "지금이 매수 기회입니다.",
                "추가 하락 전에 매도를 고려해 보세요.",
                "이 종목을 추천합니다.",
                "지금 사세요."})
        @DisplayName("잡힌다")
        void caught(String summary) {
            assertThat(check(summary).violations()).containsExactly(OutputRule.SOLICIT);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "같은 시점에 자기주식 매수 결정 공시가 있었습니다.",
                "같은 시점에 공개매수신고서가 제출됐습니다."})
        @DisplayName("통과한다 — 공시 이름 속 '매수'는 권유형이 아니다")
        void passes(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("FORECAST — 전망 (01 §9 해석)")
    class Forecast {

        @ParameterizedTest
        @ValueSource(strings = {
                "주가는 당분간 오를 것으로 보입니다.",
                "하락할 전망입니다.",
                "추가 반등이 기대됩니다.",
                "상승세가 이어질 가능성이 높습니다."})
        @DisplayName("잡힌다")
        void caught(String summary) {
            assertThat(check(summary).violations()).containsExactly(OutputRule.FORECAST);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "같은 날 영업실적 전망 공시가 있었습니다.",
                "장 마감 이후 공개된 공시는 당일 변동과 시점이 다릅니다."})
        @DisplayName("통과한다 — '전망' 단어 자체는 잡지 않는다")
        void passes(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("입력 문자열 가리기 (D1)")
    class Masking {

        @ParameterizedTest
        @ValueSource(strings = {
                "같은 날 " + TITLE_RECOMMEND + " 공시가 있었습니다.",
                "같은 날 사외이사후보추천위원회 설치 결정 공시가 있었습니다.",
                "같은 날 사외이사후보추천위원회설치결정 공시가 있었습니다.",
                "같은 날 사외이사 후보 추천 위원회 설치 결정 공시가 있었습니다."})
        @DisplayName("입력 제목을 인용하면 그 안의 금지어('추천')는 가려진다 — 완전 일치 · 정정 접두어 생략 · 공백 차이")
        void quotedTitlePasses(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }

        @Test
        @DisplayName("제목을 인용해도 따로 쓴 권유 문장은 잡힌다")
        void quotedTitleWithSolicit() {
            assertThat(check("같은 날 " + TITLE_OPTION + " 공시가 있었습니다. 지금 매수하세요.").violations())
                    .containsExactly(OutputRule.SOLICIT);
        }

        @Test
        @DisplayName("입력에 없는 제목 · 제목 일부 인용은 가리지 않는다")
        void notMasked() {
            assertThat(check("같은 날 자사주 추천 공시가 있었습니다.").violations()).containsExactly(OutputRule.SOLICIT);
            assertThat(check("같은 날 후보추천위원회 공시가 있었습니다.").violations()).containsExactly(OutputRule.SOLICIT);
        }

        @Test
        @DisplayName("종목명 안의 숫자는 숫자로 보지 않는다")
        void stockNameDigits() {
            assertThat(check("더미에너지9는 7.23% 하락했습니다.").passed()).isTrue();
        }

        @Test
        @DisplayName("가린 제목 안의 숫자는 입력 숫자다 — 제목의 12회와 같은 회차는 통과")
        void titleNumbersAreInput() {
            assertThat(check("같은 날 12회차 사채 발행 공시가 있었습니다.").passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("숫자 정규화 · 반올림 (D4) — 입력: 7.2300 · 1.2000 · 6.0300(모두 음수) · 1384.5 · 3.25 · 2")
    class Numbers {

        @ParameterizedTest
        @ValueSource(strings = {
                "종목은 7.23% 하락했습니다.",
                "종목은 7.230% 하락했습니다.",
                "KOSPI는 1.20% 하락했습니다.",
                "종목은 -7.23% 움직였습니다.",
                "종목은 −7.2% 움직였습니다.",
                "종목은 +7.2% 움직였습니다.",
                "종목은 7.2% 하락했습니다.",
                "종목은 7% 가까이 하락했습니다.",
                "시장보다 6.0%p 더 하락했습니다.",
                "원/달러 환율은 1,384.5원이었습니다.",
                "원/달러 환율은 1,385원이었습니다.",
                "기준금리는 3.25%였습니다."})
        @DisplayName("입력 값 · 반올림 값은 통과 — 부호 무시, 쉼표 · 뒤쪽 0 제거")
        void grounded(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "종목은 7.3% 하락했습니다.",
                "종목 7.23%와 지수 1.2%를 합하면 8.43입니다.",
                "거래량은 9.9% 늘었습니다.",
                "11.16에 공시가 있었습니다."})
        @DisplayName("반올림이 아닌 값 · 입력끼리 계산한 값 · 입력에 없는 값은 UNGROUNDED_NUMBER — 11.16은 날짜로 보지 않는다")
        void ungrounded(String summary) {
            assertThat(check(summary).violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-11-16에 공시가 있었습니다.",
                "2026.11.13 공시가 있었습니다.",
                "2026년 11월 16일 공시가 있었습니다.",
                "11월 16일 공시가 있었습니다.",
                "11/16 공시가 있었습니다."})
        @DisplayName("입력 날짜는 형식이 달라도 통과")
        void dates(String summary) {
            assertThat(check(summary).passed()).isTrue();
        }

        @Test
        @DisplayName("입력에 없는 날짜는 UNGROUNDED_NUMBER")
        void unknownDate() {
            assertThat(check("11월 17일 공시가 있었습니다.").violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
        }

        @Test
        @DisplayName("시각은 입력 시각 목록에 있을 때만 통과")
        void times() {
            String summary = "장 마감(15:30) 이후 공개된 공시가 있었습니다.";
            assertThat(guard.check(summary, context(List.of(LocalTime.of(15, 30)))).passed()).isTrue();
            assertThat(guard.check(summary, context()).violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
        }

        @Test
        @DisplayName("연도는 입력 날짜의 연도와 같을 때만 통과")
        void years() {
            assertThat(check("2026년 공시가 있었습니다.").passed()).isTrue();
            assertThat(check("2025년 공시가 있었습니다.").violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
        }

        @Test
        @DisplayName("건수 · 서수는 입력 정수와 정확히 같아야 한다 — 3.25의 반올림 3 · 7.23의 반올림 7로는 통과하지 않는다")
        void countsAndOrdinals() {
            assertThat(check("같은 날 공시 2건이 있었습니다.").passed()).isTrue();
            assertThat(check("같은 날 공시 3건이 있었습니다.").violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
            assertThat(check("같은 날 공시 7건이 있었습니다.").violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
            assertThat(check("같은 날 1차 공시가 있었습니다.").violations()).containsExactly(OutputRule.UNGROUNDED_NUMBER);
        }
    }

    @Test
    @DisplayName("여러 규칙에 동시에 걸리면 모두 담는다 (D5)")
    void multipleRules() {
        assertThat(check("실적 덕분에 오를 것이니 매수하세요. 목표 수익은 9.9%입니다.").violations())
                .containsExactlyInAnyOrder(OutputRule.CAUSAL, OutputRule.FORECAST, OutputRule.SOLICIT,
                        OutputRule.UNGROUNDED_NUMBER);
    }

    @Test
    @DisplayName("정상 문장 — '같은 시점에 ~ 공시가 있었습니다' + 입력 숫자 · 날짜")
    void normalSummary() {
        String summary = "11월 16일 같은 시점에 " + TITLE_BUYBACK + " 공시가 있었습니다. "
                + "더미에너지9는 7.23% 하락했고 KOSPI는 1.2% 하락해, 시장보다 6.0%p 더 하락했습니다.";
        OutputGuardResult result = check(summary);
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", " \n\t "})
    @DisplayName("빈 요약 · 공백 요약은 EMPTY 하나만")
    void empty(String summary) {
        OutputGuardResult result = check(summary);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).containsExactly(OutputRule.EMPTY);
    }

    @Test
    @DisplayName("원문 비노출 — 결과 · 컨텍스트의 toString에 요약 · 제목 문자열이 없다 (AGENTS 9장)")
    void noRawText() {
        String summary = "같은 날 " + TITLE_OPTION + " 공시가 있었습니다. 지금 매수하세요.";
        OutputGuardResult result = check(summary);
        assertThat(result.toString()).contains("SOLICIT").doesNotContain("매수하세요").doesNotContain(TITLE_OPTION);
        assertThat(context().toString()).doesNotContain(TITLE_OPTION).doesNotContain(OutputGuardFixtures.STOCK_NAME)
                .contains("5");
    }
}
