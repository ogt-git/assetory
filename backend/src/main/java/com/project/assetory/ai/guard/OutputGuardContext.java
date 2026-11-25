package com.project.assetory.ai.guard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 출력 검사 컨텍스트 (Q-7) — 프롬프트를 만든 같은 입력에서 호출하는 쪽(AI-2 · AI-5)이 만든다. 검사기는 값의 출처를 모른다.
 *
 * @param quotedTexts 요약에서 가릴 입력 문자열 — 공시 제목 · 종목명. 이 안의 숫자도 입력 숫자로 본다
 * @param numbers     입력 숫자 — 변동률 · 지수 변동률 · 초과 변동 · 금리 · 환율 · 공시 건수 등 (부호 무시, 반올림 허용)
 * @param dates       입력 날짜 — 이벤트 날짜 · 공시 접수일
 * @param times       입력 시각 — 프롬프트에 넣은 시각만 (예: 장 마감 15:30)
 */
public record OutputGuardContext(List<String> quotedTexts, List<BigDecimal> numbers, List<LocalDate> dates,
                                 List<LocalTime> times) {

    public OutputGuardContext {
        quotedTexts = quotedTexts == null ? List.of() : List.copyOf(quotedTexts);
        numbers = numbers == null ? List.of() : List.copyOf(numbers);
        dates = dates == null ? List.of() : List.copyOf(dates);
        times = times == null ? List.of() : List.copyOf(times);
    }

    // 입력 제목 원문이 로그 · 예외에 찍히지 않게 개수만 (AGENTS 9장)
    @Override
    public String toString() {
        return "OutputGuardContext[quotedTexts=" + quotedTexts.size() + ", numbers=" + numbers.size()
                + ", dates=" + dates.size() + ", times=" + times.size() + "]";
    }
}
