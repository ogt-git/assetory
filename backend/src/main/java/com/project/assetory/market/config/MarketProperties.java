package com.project.assetory.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 시장 데이터 수집 설정 — application.yml assetory.market.*
 *
 * @param referenceTicker         거래일 판정 기준 종목 — 최신 거래일자가 오늘이면 거래일 (01 §14 #23 (c)안)
 * @param closeGuardTime          이 시각 전에는 오늘 행을 종가로 저장하지 않는다 — 장중에는 종가 칸에 현재가가 온다 (V4)
 * @param maxConsecutiveFailures  호출 실패가 이 횟수만큼 연속되면 이번 실행의 남은 호출을 멈춘다 (서킷 브레이커 대신)
 * @param lateCloseDates          장 마감이 늦어지는 날(수능일 — 정규장 1시간 순연). 이 날은 lateCloseGuardTime을 가드로 쓴다
 * @param lateCloseGuardTime      장 마감 지연일의 가드 시각
 */
@ConfigurationProperties("assetory.market")
public record MarketProperties(
        String referenceTicker,
        LocalTime closeGuardTime,
        int maxConsecutiveFailures,
        List<LocalDate> lateCloseDates,
        LocalTime lateCloseGuardTime
) {

    public MarketProperties {
        lateCloseDates = lateCloseDates == null ? List.of() : List.copyOf(lateCloseDates);
    }

    public boolean isLateCloseDate(LocalDate date) {
        return lateCloseDates.contains(date);
    }

    // 그날의 장 마감 가드 시각 — 지연일이면 지연 가드, 아니면 기본 가드
    public LocalTime closeGuardTime(LocalDate date) {
        return isLateCloseDate(date) && lateCloseGuardTime != null ? lateCloseGuardTime : closeGuardTime;
    }
}
