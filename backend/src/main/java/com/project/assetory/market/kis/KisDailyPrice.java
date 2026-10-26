package com.project.assetory.market.kis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일자별 시세 1행 (inquire-daily-price output[]). 값은 한투가 준 그대로 — 변동률을 직접 계산하지 않는다 (01 §14 #5).
 *
 * @param change              prdy_vrss 전일 대비 (원)
 * @param changeSign          prdy_vrss_sign 원문 코드
 * @param changeRate          prdy_ctrt 전일 대비율(%) — 기준(전일 종가 / 기준가)은 실호출 검증 V1
 * @param lockCode            flng_cls_code 락 구분 (없으면 null)
 * @param cumulativeSplitRate acml_prtt_rate 누적 분할 비율 (없으면 null)
 */
public record KisDailyPrice(
        LocalDate tradeDate,
        BigDecimal closePrice,
        BigDecimal change,
        String changeSign,
        BigDecimal changeRate,
        long volume,
        String lockCode,
        BigDecimal cumulativeSplitRate
) {}
