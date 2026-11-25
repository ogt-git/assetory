package com.project.assetory.market.service;

import com.project.assetory.market.config.MacroIndicatorProperties;
import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.IndicatorSeries;
import com.project.assetory.market.domain.MacroIndicatorRow;
import com.project.assetory.market.ecos.EcosApiClient;
import com.project.assetory.market.exception.EcosApiException;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisIndexCodes;
import com.project.assetory.market.kis.KisIndexDaily;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 지수 · 거시지표 수집 (FR-MK-02 · 03 · 05, 05 §11.2 "지수 · 지표 → macro_indicators").
 * KOSPI · KOSDAQ(한투 업종지수) · BASE_RATE · USD_KRW(ECOS).
 * - 매 실행 [기준일 − N일, 기준일]을 받아 업서트 — 16:00 잠정값은 다음 실행에서 덮어써 고쳐진다 (B4 확정 사항 2)
 * - 조회 · 계산은 트랜잭션 밖. 지표 하나의 업서트 목록은 MacroIndicatorWriter(별도 빈 — @Transactional 프록시 적용)의
 *   독립된 짧은 트랜잭션 1개로 저장하고, 실패하면 그 지표만 전부 롤백한다. 지표끼리 영향 없음 (확정 사항 6)
 * - 이 클래스는 @Transactional이 없다 — 호출자(일일 배치)의 트랜잭션과 섞이지 않는다
 * - 로그: 지표 코드 · 저장 건수 · 실패 유형만
 */
@Slf4j
@Service
public class MacroIndicatorCollectService {

    private static final List<IndicatorCode> KIS_INDEXES = List.of(IndicatorCode.KOSPI, IndicatorCode.KOSDAQ);

    /**
     * ECOS 통계 — 통계표 · 항목코드는 B0 Phase 1에서 ECOS 메타(StatisticItemList)로 확인한 값(일 주기 D 제공).
     * 기준금리는 값만 저장(change_rate NULL), 원/달러는 직접 계산 (B4 확정 사항 4 · 9)
     */
    private record EcosSeries(IndicatorCode code, String statCode, String itemCode, boolean computeChangeRate) {
    }

    private static final List<EcosSeries> ECOS_SERIES = List.of(
            new EcosSeries(IndicatorCode.BASE_RATE, "722Y001", "0101000", false),
            new EcosSeries(IndicatorCode.USD_KRW, "731Y001", "0000001", true));

    private final KisApiClient kis;
    private final KisIndexCodes indexCodes;
    private final EcosApiClient ecos;
    private final MacroIndicatorWriter writer;
    private final MacroIndicatorProperties properties;

    public MacroIndicatorCollectService(KisApiClient kis, KisIndexCodes indexCodes, EcosApiClient ecos,
                                        MacroIndicatorWriter writer, MacroIndicatorProperties properties) {
        this.kis = kis;
        this.indexCodes = indexCodes;
        this.ecos = ecos;
        this.writer = writer;
        this.properties = properties;
    }

    public MacroIndicatorCollectResult collect(LocalDate baseDate) {
        LocalDate from = baseDate.minusDays(properties.indicatorLookbackDays());
        Map<IndicatorCode, MacroIndicatorCollectResult.Outcome> outcomes = new EnumMap<>(IndicatorCode.class);
        for (IndicatorCode code : KIS_INDEXES) {
            outcomes.put(code, collectKisIndex(code, from, baseDate));
        }
        for (EcosSeries series : ECOS_SERIES) {
            outcomes.put(series.code(), collectEcos(series, from, baseDate));
        }
        log.info("[Indicator] 지수 · 지표 수집 baseDate={} 결과={}", baseDate, outcomes);
        return new MacroIndicatorCollectResult(Map.copyOf(outcomes));
    }

    // ECOS도 지표마다 독립 — 실패하면 그 지표만 롤백 · 실패 기록, 지수와 다른 ECOS 지표는 그대로 (확정 사항 6)
    private MacroIndicatorCollectResult.Outcome collectEcos(EcosSeries series, LocalDate from, LocalDate to) {
        try {
            List<DatedValue> values = ecos.search(series.statCode(), series.itemCode(), from, to);
            List<MacroIndicatorRow> rows = IndicatorSeries.toRows(series.code(), values, series.computeChangeRate());
            return MacroIndicatorCollectResult.Outcome.ok(writer.upsertAll(rows));
        } catch (EcosApiException e) {
            log.warn("[Indicator] ECOS 수집 실패 code={} type={} ecosCode={}", series.code(), e.getErrorType(), e.getEcosCode());
            return MacroIndicatorCollectResult.Outcome.failed(e.getErrorType().name());
        } catch (RuntimeException e) {
            log.warn("[Indicator] ECOS 처리 · 저장 실패 — 그 지표 롤백 code={} error={}", series.code(), e.getClass().getSimpleName());
            return MacroIndicatorCollectResult.Outcome.failed(e.getClass().getSimpleName());
        }
    }

    private MacroIndicatorCollectResult.Outcome collectKisIndex(IndicatorCode code, LocalDate from, LocalDate to) {
        try {
            List<KisIndexDaily> raw = kis.getIndexDaily(indexCodes.codeOf(code), from, to);
            List<MacroIndicatorRow> rows = IndicatorSeries.toRows(code,
                    raw.stream().map(r -> new DatedValue(r.tradeDate(), r.value())).toList(), true);
            logRateMismatch(code, raw, rows);
            return MacroIndicatorCollectResult.Outcome.ok(writer.upsertAll(rows));
        } catch (KisApiException e) {
            log.warn("[Indicator] 지수 수집 실패 code={} type={} kisCode={}", code, e.getErrorType(), e.getKisCode());
            return MacroIndicatorCollectResult.Outcome.failed(e.getErrorType().name());
        } catch (RuntimeException e) {
            log.warn("[Indicator] 지수 처리 · 저장 실패 — 그 지표 롤백 code={} error={}", code, e.getClass().getSimpleName());
            return MacroIndicatorCollectResult.Outcome.failed(e.getClass().getSimpleName());
        }
    }

    // 한투 대비율은 저장에 쓰지 않는다 — 직접 계산값과 소수 2자리에서 다른 행 수만 남긴다(B0 K1 대조용, 확정 사항 4)
    private static void logRateMismatch(IndicatorCode code, List<KisIndexDaily> raw, List<MacroIndicatorRow> rows) {
        long compared = 0;
        long mismatched = 0;
        for (MacroIndicatorRow row : rows) {
            KisIndexDaily kisRow = raw.stream().filter(r -> r.tradeDate().equals(row.baseDate())).findFirst().orElse(null);
            if (kisRow == null || kisRow.kisChangeRate() == null) continue;
            compared++;
            if (row.changeRate().setScale(2, RoundingMode.HALF_UP).compareTo(kisRow.kisChangeRate()) != 0) mismatched++;
        }
        if (compared > 0 && mismatched > 0) {
            log.info("[Indicator] 한투 대비율과 계산값 차이 code={} 비교={} 불일치={}", code, compared, mismatched);
        }
    }
}
