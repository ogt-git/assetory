package com.project.assetory.market;

import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.ecos.EcosApiClient;
import com.project.assetory.market.ecos.EcosErrorType;
import com.project.assetory.market.exception.EcosApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisIndexDaily;
import com.project.assetory.market.repository.MacroIndicatorRepository;
import com.project.assetory.market.service.MacroIndicatorCollectResult;
import com.project.assetory.market.service.MacroIndicatorCollectService;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ECOS 거시지표 수집 (B4 Phase 4, 확정 사항 4 · 6 · 9) — 기준금리는 값만(change_rate NULL), 원/달러는 직접 계산.
 * 가장 오래된 날짜 제외, 원본에 없는 날짜는 만들지 않는다. ECOS와 지수는 서로 영향 없음.
 * 테스트마다 다른 연도(2015~2018) — 다른 테스트와 날짜가 겹치지 않게
 */
class MacroIndicatorEcosCollectIntegrationTest extends IntegrationTestSupport {

    @MockitoBean KisApiClient kis;
    @MockitoBean EcosApiClient ecos;
    @Autowired MacroIndicatorCollectService service;
    @Autowired MacroIndicatorRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("3. 기준금리 change_rate NULL · 원/달러 직접 계산 · 가장 오래된 날짜 제외 · 원본에 없는 날짜(주말) 미생성")
    void baseRateAndUsdKrw() {
        LocalDate base = LocalDate.of(2015, 3, 13); // 금
        when(ecos.search(eq("722Y001"), eq("0101000"), any(), any())).thenReturn(List.of(
                v(2015, 3, 12, "2.5"), v(2015, 3, 11, "2.5"), v(2015, 3, 13, "2.25")));
        when(ecos.search(eq("731Y001"), eq("0000001"), any(), any())).thenReturn(List.of(
                v(2015, 3, 9, "1380.5"), v(2015, 3, 13, "1407"), v(2015, 3, 12, "1400"), v(2015, 3, 10, "1393.3")));

        MacroIndicatorCollectResult result = service.collect(base);

        verify(ecos).search("722Y001", "0101000", base.minusDays(45), base);
        verify(ecos).search("731Y001", "0000001", base.minusDays(45), base);
        assertThat(result.outcome(IndicatorCode.BASE_RATE).saved()).isEqualTo(2);
        assertThat(rows(IndicatorCode.BASE_RATE, 2015)).containsExactly(
                "2015-03-12:2.5000:null:ECOS", "2015-03-13:2.2500:null:ECOS");
        // 3/11(수)은 응답에 없으므로 만들지 않는다 · 계산은 받은 직전 행 기준
        assertThat(rows(IndicatorCode.USD_KRW, 2015)).containsExactly(
                "2015-03-10:1393.3000:0.9272:ECOS",   // 12.8 ÷ 1380.5
                "2015-03-12:1400.0000:0.4809:ECOS",   // 6.7 ÷ 1393.3
                "2015-03-13:1407.0000:0.5000:ECOS");  // 7 ÷ 1400
    }

    @Test
    @DisplayName("5. ECOS 키 오류 → ECOS 지표만 실패, 지수는 정상 저장")
    void ecosKeyError_indexesSaved() {
        LocalDate base = LocalDate.of(2016, 3, 10);
        when(ecos.search(anyString(), anyString(), any(), any())).thenThrow(new EcosApiException(EcosErrorType.CONFIG_MISSING, null));
        when(kis.getIndexDaily(anyString(), any(), any())).thenReturn(List.of(
                new KisIndexDaily(base, new BigDecimal("2040.40"), null), new KisIndexDaily(base.minusDays(1), new BigDecimal("2020.00"), null)));

        MacroIndicatorCollectResult result = service.collect(base);

        assertThat(result.outcome(IndicatorCode.BASE_RATE).failure()).isEqualTo("CONFIG_MISSING");
        assertThat(result.outcome(IndicatorCode.USD_KRW).failure()).isEqualTo("CONFIG_MISSING");
        assertThat(result.outcome(IndicatorCode.KOSPI).success()).isTrue();
        assertThat(result.outcome(IndicatorCode.KOSDAQ).success()).isTrue();
        assertThat(repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSPI, base)).isPresent();
        assertThat(rows(IndicatorCode.BASE_RATE, 2016)).isEmpty();
    }

    @Test
    @DisplayName("6. 한 ECOS 지표 실패 → 다른 ECOS 지표 · 지수 저장 유지, 다음 실행에서 성공하면 저장")
    void oneEcosFails_othersKept() {
        LocalDate base = LocalDate.of(2017, 3, 10);
        when(ecos.search(eq("722Y001"), eq("0101000"), any(), any())).thenThrow(new EcosApiException(EcosErrorType.RATE_LIMIT, "ERROR-602"));
        when(ecos.search(eq("731Y001"), eq("0000001"), any(), any())).thenReturn(List.of(v(2017, 3, 9, "1100"), v(2017, 3, 10, "1111")));
        when(kis.getIndexDaily(anyString(), any(), any())).thenReturn(List.of(
                new KisIndexDaily(base, new BigDecimal("2040.40"), null), new KisIndexDaily(base.minusDays(1), new BigDecimal("2020.00"), null)));

        MacroIndicatorCollectResult result = service.collect(base);

        assertThat(result.outcome(IndicatorCode.BASE_RATE).failure()).isEqualTo("RATE_LIMIT");
        assertThat(rows(IndicatorCode.USD_KRW, 2017)).containsExactly("2017-03-10:1111.0000:1.0000:ECOS");
        assertThat(repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSPI, base)).isPresent();

        when(ecos.search(eq("722Y001"), eq("0101000"), any(), any())).thenReturn(List.of(v(2017, 3, 9, "1.25"), v(2017, 3, 10, "1.25")));
        service.collect(base);
        MacroIndicator rate = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.BASE_RATE, base).orElseThrow();
        assertThat(rate.getValue()).isEqualByComparingTo("1.25");
        assertThat(rate.getChangeRate()).isNull();
    }

    @Test
    @DisplayName("ECOS 같은 날짜 중복 → 그 지표만 실패(IndicatorSeriesException)")
    void duplicateDate_fails() {
        LocalDate base = LocalDate.of(2018, 3, 9);
        when(ecos.search(eq("731Y001"), eq("0000001"), any(), any())).thenReturn(List.of(
                v(2018, 3, 8, "1070"), v(2018, 3, 9, "1071"), v(2018, 3, 9, "1072")));
        when(ecos.search(eq("722Y001"), eq("0101000"), any(), any())).thenReturn(List.of(v(2018, 3, 8, "1.5"), v(2018, 3, 9, "1.5")));

        MacroIndicatorCollectResult result = service.collect(base);

        assertThat(result.outcome(IndicatorCode.USD_KRW).failure()).isEqualTo("IndicatorSeriesException");
        assertThat(rows(IndicatorCode.USD_KRW, 2018)).isEmpty();
        assertThat(rows(IndicatorCode.BASE_RATE, 2018)).hasSize(1);
    }

    private static DatedValue v(int y, int m, int d, String value) {
        return new DatedValue(LocalDate.of(y, m, d), new BigDecimal(value));
    }

    private List<String> rows(IndicatorCode code, int year) {
        return jdbc.queryForList("""
                select concat(base_date, ':', value, ':', coalesce(change_rate, 'null'), ':', source) from macro_indicators
                where indicator_code = ? and year(base_date) = ? order by base_date""", String.class, code.name(), year);
    }
}
