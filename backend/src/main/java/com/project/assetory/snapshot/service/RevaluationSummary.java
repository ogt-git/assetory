package com.project.assetory.snapshot.service;

import com.project.assetory.market.service.ClosePriceConfirmResult;

import java.time.LocalDate;

/**
 * 잠정 종가 보정 1회 결과.
 *
 * @param confirm             종가 확정 재조회 결과 (바뀐 종목 · 확인 · 실패 수)
 * @param revaluedRecords     다시 평가한 그날 기록 수
 * @param recomputedFollowing 그 기록을 비교 기준으로 쓴 뒤 기록 중 비교값을 다시 계산한 수
 */
public record RevaluationSummary(
        LocalDate tradeDate,
        ClosePriceConfirmResult confirm,
        int revaluedRecords,
        int recomputedFollowing
) {}
