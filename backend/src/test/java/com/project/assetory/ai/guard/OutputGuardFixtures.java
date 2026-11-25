package com.project.assetory.ai.guard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 테스트 전용 — 출력 검사기 픽스처 (Q-7). 패턴은 application.yml assetory.ai.output-guard.*와 같아야 한다(OutputGuardConfigTest가 대조).
 * 공시 제목 · 종목명은 지어낸 더미 값이다(실제 회사 공시 아님)
 */
final class OutputGuardFixtures {

    static final List<String> CAUSAL = List.of(
            "때문",
            "덕분",
            "탓(에|으로|이)",
            "(으)?로\\s*인(해|하여|한)",
            "여파",
            "영향으로",
            "견인");

    static final List<String> SOLICIT = List.of(
            "추천",
            "(매수|매도|매입|매각|처분|정리)(하세요|하십시오|해\\s*보세요|를\\s*(권|고려)|할\\s*(만|때|시점)|\\s*(기회|시점|타이밍))",
            "(사|파|담으)(세요|십시오)",
            "권(유|장)(합니다|드립니다|해)",
            "비중을\\s*(늘|줄|확대|축소)");

    static final List<String> FORECAST = List.of(
            "(오를|내릴|오르|상승할|하락할|반등할|회복할|떨어질|올라갈|이어질|지속될)\\s*(것|전망|가능성|수\\s*있)",
            "(예상|전망|기대)(됩니다|된다|돼|되며|입니다|이다|됨)",
            "가능성이\\s*(높|크|있)",
            "향후\\s*(주가|전망|흐름)");

    // 더미 종목명 — 이름 안의 숫자 9는 입력 숫자 어디에도 없다(가리지 않으면 UNGROUNDED_NUMBER)
    static final String STOCK_NAME = "더미에너지9";

    // 더미 공시 제목 — "추천"이 들어간 제목(가리기 확인용), 정정 접두어 · 공백 포함, 회차 숫자 12 포함
    static final String TITLE_RECOMMEND = "[기재정정]사외이사후보추천위원회 설치 결정";
    static final String TITLE_BUYBACK = "[기재정정]주요사항보고서(자기주식취득결정)";
    static final String TITLE_OPTION = "주식매수선택권부여에관한신고";
    static final String TITLE_BOND = "제12회 무보증 사채 발행 결정";

    // 입력 숫자: 종목 −7.23% · KOSPI −1.2% · 초과 −6.03%p · 원/달러 1,384.5 · 기준금리 3.25 · 공시 2건
    static final List<BigDecimal> NUMBERS = List.of(new BigDecimal("-7.2300"), new BigDecimal("-1.2000"),
            new BigDecimal("-6.0300"), new BigDecimal("1384.5"), new BigDecimal("3.25"), new BigDecimal("2"));

    // 이벤트 날짜 · 공시 접수일
    static final List<LocalDate> DATES = List.of(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 13));

    private OutputGuardFixtures() {
    }

    static OutputGuardProperties properties() {
        return new OutputGuardProperties(CAUSAL, SOLICIT, FORECAST);
    }

    static LlmOutputGuard guard() {
        return new LlmOutputGuard(properties());
    }

    static OutputGuardContext context() {
        return context(List.of());
    }

    static OutputGuardContext context(List<LocalTime> times) {
        return new OutputGuardContext(List.of(STOCK_NAME, TITLE_RECOMMEND, TITLE_BUYBACK, TITLE_OPTION, TITLE_BOND),
                NUMBERS, DATES, times);
    }
}
