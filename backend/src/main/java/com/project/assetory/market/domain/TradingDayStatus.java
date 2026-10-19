package com.project.assetory.market.domain;

// 거래일 판정 결과 (FR-MK-06). 판정할 수 없으면 UNKNOWN — 장애 난 날을 휴장일로 바꾸지 않는다
public enum TradingDayStatus {
    TRADING_DAY,
    HOLIDAY,
    UNKNOWN
}
