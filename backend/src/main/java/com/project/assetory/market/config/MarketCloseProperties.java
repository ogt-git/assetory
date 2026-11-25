package com.project.assetory.market.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 장 마감 시각 — application.yml assetory.market.close-time · late-close-time (Q-9, ANL-8 장 마감 후 판정 기준).
 * 수집 가드(close-guard-time 15:40 — 종가 여유 포함)와 다른 값이다. 장 마감 지연일은 MarketProperties와 같은 late-close-dates를 읽는다.
 * MarketProperties와 같은 접두사지만 따로 둔다 — 생성자를 바꾸면 기존 테스트 · 호출부가 깨진다(MacroIndicatorProperties와 같은 방식)
 *
 * @param closeTime      정규장 마감 (15:30)
 * @param lateCloseTime  장 마감 지연일(수능일 — 정규장 1시간 순연) 마감 (16:30)
 * @param lateCloseDates 장 마감 지연일
 */
@Validated
@ConfigurationProperties("assetory.market")
public record MarketCloseProperties(@NotNull LocalTime closeTime,
                                    @NotNull LocalTime lateCloseTime,
                                    List<LocalDate> lateCloseDates) {

    public MarketCloseProperties {
        lateCloseDates = lateCloseDates == null ? List.of() : List.copyOf(lateCloseDates);
    }

    // 그날의 장 마감 시각 — 지연일이면 지연 마감, 아니면 정규 마감
    public LocalTime closeTime(LocalDate date) {
        return lateCloseDates.contains(date) ? lateCloseTime : closeTime;
    }
}
