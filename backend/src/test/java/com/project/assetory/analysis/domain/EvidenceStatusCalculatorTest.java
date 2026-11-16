package com.project.assetory.analysis.domain;

import com.project.assetory.analysis.domain.EvidenceStatusCalculator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 시장 동반 판정 (01 §14 #12, FR-CA-05 · 06 · 07, 05 §5.1) — 근거 0건일 때 MARKET_MOVE / NOT_FOUND를 가른다.
// MARKET_MOVE = |excess| ≤ 기준(설정값) 그리고 |excess| ≤ |지수 변동|. 경계는 모두 포함. 지수 NULL이면 excess NULL(0으로 채우지 않음)
class EvidenceStatusCalculatorTest {

    private static final BigDecimal DEFAULT_MAX = new BigDecimal("2.0");
    private static final Map<SourceType, Long> NO_EVIDENCE = Map.of();

    private static Result judge(String change, String market, Map<SourceType, Long> counts, BigDecimal max) {
        return EvidenceStatusCalculator.judge(new BigDecimal(change), market == null ? null : new BigDecimal(market), counts, max);
    }

    @ParameterizedTest(name = "[{index}] 종목 {0} · 지수 {1} → excess {2}, {3}")
    @DisplayName("근거 0건 — 시장 동반 판정 표 ①~⑥")
    @CsvSource({
            "-5.5, -4.0, -1.5000, MARKET_MOVE",  // ① 시장 급락일
            "5.0,  3.0,  2.0000,  MARKET_MOVE",  // ② 경계 2.0%p
            "2.0,  0.5,  1.5000,  NOT_FOUND",    // ③ 조건 2 위반 — 종목이 시장의 4배
            "3.0,  1.2,  1.8000,  NOT_FOUND",    // ④ 조건 2 위반
            "5.0,  -0.5, 5.5000,  NOT_FOUND",    // ⑤ 반대 방향
            "9.0,  1.0,  8.0000,  NOT_FOUND"     // ⑥ 큰 개별 급등
    })
    void table(String change, String market, String expectedExcess, EvidenceStatus expected) {
        Result result = judge(change, market, NO_EVIDENCE, DEFAULT_MAX);

        assertThat(result.excessRate()).isEqualTo(new BigDecimal(expectedExcess));
        assertThat(result.status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] 종목 {0} · 지수 {1} → {2}")
    @DisplayName("경계 — 기준 · 지수 변동과 같으면 포함, 기준을 조금이라도 넘으면 제외")
    @CsvSource({
            "-6.0,   -4.0, MARKET_MOVE",  // excess −2.0 (기준 경계, 음수)
            "7.0,    5.0,  MARKET_MOVE",  // excess +2.0 (기준 경계, 양수)
            "3.0,    1.5,  MARKET_MOVE",  // excess = 지수 변동 (조건 2 경계)
            "5.0001, 3.0,  NOT_FOUND",    // excess +2.0001 — 기준 초과
            "2.0,    0.0,  NOT_FOUND"     // 지수 0 — 조건 2 불만족 (나눗셈 없이)
    })
    void boundaries(String change, String market, EvidenceStatus expected) {
        assertThat(judge(change, market, NO_EVIDENCE, DEFAULT_MAX).status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("지수 0.0000 · 종목 0 — excess 0은 기준 · 지수 변동(0) 이하라 MARKET_MOVE")
    void zeroMarketAndZeroChange() {
        // 감지 임계치(2% 이상)가 있어 실제로는 생기지 않지만, 경계 규칙("이하")이 그대로 적용되는지 고정한다
        Result result = judge("0.0", "0.0000", NO_EVIDENCE, DEFAULT_MAX);

        assertThat(result.excessRate()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(result.status()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("지수 NULL + 근거 0건 → excess NULL, NOT_FOUND (시장 동반이라고 말할 근거 없음)")
    void missingMarketWithoutEvidence() {
        Result result = judge("-5.5", null, NO_EVIDENCE, DEFAULT_MAX);

        assertThat(result.excessRate()).isNull();
        assertThat(result.status()).isEqualTo(EvidenceStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("지수 NULL + 근거 1건 → excess NULL, RELATED_FOUND")
    void missingMarketWithEvidence() {
        Result result = judge("-5.5", null, Map.of(SourceType.DISCLOSURE, 1L), DEFAULT_MAX);

        assertThat(result.excessRate()).isNull();
        assertThat(result.status()).isEqualTo(EvidenceStatus.RELATED_FOUND);
    }

    @Test
    @DisplayName("근거 1건이면 시장 기준과 무관하게 RELATED_FOUND — excess는 계산해서 남긴다")
    void evidenceWins() {
        Result result = judge("9.0", "1.0", Map.of(SourceType.DISCLOSURE, 1L), DEFAULT_MAX);

        assertThat(result.excessRate()).isEqualTo(new BigDecimal("8.0000"));
        assertThat(result.status()).isEqualTo(EvidenceStatus.RELATED_FOUND);
    }

    @Test
    @DisplayName("뉴스만 1건이어도 RELATED_FOUND")
    void newsOnly() {
        assertThat(judge("9.0", "1.0", Map.of(SourceType.NEWS, 1L), DEFAULT_MAX).status())
                .isEqualTo(EvidenceStatus.RELATED_FOUND);
    }

    @Test
    @DisplayName("거시지표(MACRO)만 있으면 근거 0건으로 판정 — 시장 기준을 그대로 탄다")
    void macroIsNotRelatedEvidence() {
        Map<SourceType, Long> macroOnly = Map.of(SourceType.MACRO, 3L);

        assertThat(judge("9.0", "1.0", macroOnly, DEFAULT_MAX).status()).isEqualTo(EvidenceStatus.NOT_FOUND);
        assertThat(judge("-5.5", "-4.0", macroOnly, DEFAULT_MAX).status()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("건수 0 · null 값은 근거로 세지 않는다, null 맵은 0건")
    void zeroOrNullCounts() {
        Map<SourceType, Long> counts = new EnumMap<>(SourceType.class);
        counts.put(SourceType.DISCLOSURE, 0L);
        counts.put(SourceType.NEWS, null);

        assertThat(judge("9.0", "1.0", counts, DEFAULT_MAX).status()).isEqualTo(EvidenceStatus.NOT_FOUND);
        assertThat(judge("9.0", "1.0", null, DEFAULT_MAX).status()).isEqualTo(EvidenceStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("반올림 — excess는 scale 4, HALF_UP (입력 scale이 4보다 커도)")
    void rounding() {
        assertThat(judge("5.12345", "3.0", NO_EVIDENCE, DEFAULT_MAX).excessRate()).isEqualTo(new BigDecimal("2.1235"));
        assertThat(judge("-5.12345", "-3.0", NO_EVIDENCE, DEFAULT_MAX).excessRate()).isEqualTo(new BigDecimal("-2.1235"));
    }

    @Test
    @DisplayName("기준값은 인자(설정값)를 쓴다 — 3.0이면 excess 2.5가 MARKET_MOVE")
    void thresholdIsConfigurable() {
        assertThat(judge("5.5", "3.0", NO_EVIDENCE, new BigDecimal("2.0")).status()).isEqualTo(EvidenceStatus.NOT_FOUND);
        assertThat(judge("5.5", "3.0", NO_EVIDENCE, new BigDecimal("3.0")).status()).isEqualTo(EvidenceStatus.MARKET_MOVE);
    }

    @Test
    @DisplayName("종목 변동률 · 기준값이 없으면 호출부 버그 — 예외")
    void requiredArguments() {
        assertThatThrownBy(() -> EvidenceStatusCalculator.judge(null, BigDecimal.ONE, NO_EVIDENCE, DEFAULT_MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceStatusCalculator.judge(BigDecimal.ONE, BigDecimal.ONE, NO_EVIDENCE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
