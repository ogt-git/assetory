package com.project.assetory.snapshot.service;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.snapshot.domain.SnapshotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 자산 기록 날짜별 상세 결과 (FR-AS-06, FR-AN-01 종목 단위). API 응답 변환은 컨트롤러 단계에서 한다.
 * missing=true(PARTIAL · FAILED)면 totalAsset · referenceDate만 직전 COMPLETE 값이고 baseDate · 증감 · assetClasses는 null, assets는 빈 목록
 * — 그날의 부분 금액 · 자산 상세는 담지 않는다 (01 §14 #19). events도 누락 날은 빈 목록.
 *
 * @param baseDate      COMPLETE면 저장된 base_snapshot_date (첫 기록이면 null)
 * @param referenceDate 화면에 보여줄 금액의 기록 날짜 — COMPLETE면 그날, 누락이면 직전 COMPLETE(없으면 null)
 */
public record SnapshotDetailResult(LocalDate date, SnapshotStatus status, boolean missing, boolean hasEvent,
                                   LocalDate baseDate, BigDecimal totalAsset, LocalDate referenceDate,
                                   BigDecimal dayChangeAmount, BigDecimal dayChangeRate,
                                   BigDecimal marketChangeAmount, BigDecimal otherChangeAmount,
                                   AssetClasses assetClasses, List<AssetItem> assets, List<EventItem> events) {

    public record AssetClasses(ClassAmount securities, ClassAmount deposit, ClassAmount cash) {}

    /** 자산군 금액과 기준일 대비 증감 — 기준일이 없거나 어느 한쪽 금액이 없으면 증감 null */
    public record ClassAmount(BigDecimal amount, BigDecimal changeAmount) {}

    /**
     * 자산 1개의 그날 기록 — amount · dayChangeAmount는 ticker NULL 행(증권 예수금 · 수기 잔액)의 저장값.
     *
     * @param accountNumberMasked 증권 계좌만, 저장된 마스킹 값 그대로 (원문 계좌번호는 담지 않는다)
     */
    public record AssetItem(Long assetId, AssetType assetType, String name, String accountNumberMasked,
                            BigDecimal amount, BigDecimal dayChangeAmount, List<HoldingItem> holdings) {}

    /** 종목 1개의 그날 기록 — 저장값. dayChangeAmount = 직전 COMPLETE 같은 (자산, 종목) 대비 (FR-AN-01) */
    public record HoldingItem(String ticker, String stockName, BigDecimal quantity, BigDecimal price,
                              BigDecimal amount, BigDecimal dayChangeAmount) {}

    /**
     * 그날 내 급등락 이벤트 1개 (A6) — user_market_events(소유자 기준) + 공유 분석의 종목 사실. 원인 · 근거는 담지 않는다.
     *
     * @param changeAmount  내 평가금액 변동 — 소급 이벤트는 null
     * @param positionRatio 내 총자산 대비 비중(퍼센트) — 소급 이벤트는 null
     * @param backfilled    연동 전 이벤트(소급) 여부
     */
    public record EventItem(String ticker, String stockName, BigDecimal changeRate, Direction direction,
                            BigDecimal changeAmount, BigDecimal positionRatio, boolean backfilled) {}
}
