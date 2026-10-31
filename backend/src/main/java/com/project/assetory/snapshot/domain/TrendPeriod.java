package com.project.assetory.snapshot.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 총자산 추이 기간 (FR-AN-05, 03 S-01 ③ 기간 탭 1M / 1Y / 전체).
 * 1M · 1Y의 목표 날짜는 전월 · 전년 대비 기준(AssetChangeCalculator)을 그대로 쓴다 — 차트 처음 → 끝 변화가 B4 비교와 같아지도록.
 * 목표 날짜는 항상 asOfDate(최근 COMPLETE)에서 계산한다 (오늘 날짜를 쓰지 않음).
 */
public enum TrendPeriod {

    ONE_MONTH("1M"),
    ONE_YEAR("1Y"),
    ALL("ALL");

    private final String code;

    TrendPeriod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    // 요청 값 → 기간. 대문자 정확히 일치만 허용 ("1m" · "3M" · 공백 · enum 이름은 실패)
    public static TrendPeriod fromCode(String code) {
        for (TrendPeriod period : values()) {
            if (period.code.equals(code)) {
                return period;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 추이 기간입니다");
    }

    /** 시작 기준을 찾을 목표 날짜 — 이 날짜 이하의 마지막 COMPLETE. ALL은 목표 날짜 없음(첫 COMPLETE부터) */
    public Optional<LocalDate> targetDate(LocalDate asOfDate) {
        return switch (this) {
            case ONE_MONTH -> Optional.of(AssetChangeCalculator.monthTarget(asOfDate));
            case ONE_YEAR -> Optional.of(AssetChangeCalculator.yearTarget(asOfDate));
            case ALL -> Optional.empty();
        };
    }
}
