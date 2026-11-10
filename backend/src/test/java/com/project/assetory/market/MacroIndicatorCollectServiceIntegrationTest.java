package com.project.assetory.market;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisErrorType;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지수 수집 (B4 Phase 3, 확정 사항 2 · 6) — 조회 · 계산은 트랜잭션 밖, 지표 하나의 업서트는 독립 트랜잭션 1개.
 * 지표끼리 영향 없음. 테스트마다 다른 연도(2011~2014) — 같은 DB를 쓰는 다른 테스트 · 이 클래스의 다른 테스트와 날짜가 겹치지 않게
 */
class MacroIndicatorCollectServiceIntegrationTest extends IntegrationTestSupport {

    @MockitoBean KisApiClient kis;
    @MockitoSpyBean MacroIndicatorRepository repository;
    @Autowired MacroIndicatorCollectService service;
    @Autowired JdbcTemplate jdbc;

    /** 기준일 BASE와 직전 두 날짜 D2 · D1 (3월 8 · 9 · 10일) */
    private record Days(LocalDate d1, LocalDate d2, LocalDate base) {
        static Days of(int year) {
            return new Days(LocalDate.of(year, 3, 8), LocalDate.of(year, 3, 9), LocalDate.of(year, 3, 10));
        }
    }

    @Test
    @DisplayName("기본 범위: 기준일 − 45일 ~ 기준일, KOSPI 0001 · KOSDAQ 1001로 조회 · 가장 오래된 날짜 제외하고 저장")
    void collect_savesBoth() {
        Days d = Days.of(2011);
        stub("0001", d, "2000.00", "2020.00", "2040.40");
        stub("1001", d, "800.00", "808.00", "816.08");

        MacroIndicatorCollectResult result = service.collect(d.base());

        verify(kis).getIndexDaily("0001", d.base().minusDays(45), d.base());
        verify(kis).getIndexDaily("1001", d.base().minusDays(45), d.base());
        assertThat(result.outcome(IndicatorCode.KOSPI).success()).isTrue();
        assertThat(result.outcome(IndicatorCode.KOSPI).saved()).isEqualTo(2);
        assertThat(dates(IndicatorCode.KOSPI, d)).containsExactly(d.d2(), d.base());
        MacroIndicator latest = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSDAQ, d.base()).orElseThrow();
        assertThat(latest.getValue()).isEqualByComparingTo("816.08");
        assertThat(latest.getChangeRate()).isEqualByComparingTo("1.0000"); // 8.08 ÷ 808
    }

    @Test
    @DisplayName("1-a. KOSPI 저장 중 실패(강제) → KOSPI 전체 롤백(앞서 넣은 행도 없음), KOSDAQ는 정상 커밋")
    void saveFailure_rollsBackThatIndicatorOnly() {
        Days d = Days.of(2012);
        stub("0001", d, "2000.00", "2020.00", "2040.40");
        stub("1001", d, "800.00", "808.00", "816.08");
        // KOSPI 두 번째 행(BASE) 저장에서 실패 — 첫 행(D2)은 이미 같은 트랜잭션에서 저장된 뒤다
        doThrow(new IllegalStateException("테스트 — 저장 실패")).when(repository).save(argThat(
                (MacroIndicator m) -> m != null && m.getIndicatorCode() == IndicatorCode.KOSPI && d.base().equals(m.getBaseDate())));

        MacroIndicatorCollectResult result = service.collect(d.base());

        assertThat(result.outcome(IndicatorCode.KOSPI).success()).isFalse();
        assertThat(result.outcome(IndicatorCode.KOSDAQ).success()).isTrue();
        assertThat(dates(IndicatorCode.KOSPI, d)).isEmpty();
        assertThat(dates(IndicatorCode.KOSDAQ, d)).containsExactly(d.d2(), d.base());
    }

    @Test
    @DisplayName("1-b. KOSPI 조회 실패 · 같은 날짜 중복 → 그 지표만 실패(실패 유형 기록), 다른 지표는 저장")
    void fetchFailure_isolated() {
        Days d = Days.of(2013);
        when(kis.getIndexDaily(eq("0001"), any(), any())).thenThrow(new KisApiException(KisErrorType.API_ERROR, "OPSQ0002"));
        stub("1001", d, "800.00", "808.00", "816.08");

        MacroIndicatorCollectResult result = service.collect(d.base());

        assertThat(result.outcome(IndicatorCode.KOSPI).success()).isFalse();
        assertThat(result.outcome(IndicatorCode.KOSPI).failure()).isEqualTo("API_ERROR");
        assertThat(dates(IndicatorCode.KOSDAQ, d)).containsExactly(d.d2(), d.base());

        when(kis.getIndexDaily(eq("0001"), any(), any())).thenReturn(List.of(
                new KisIndexDaily(d.base(), new BigDecimal("2040.40"), null),
                new KisIndexDaily(d.base(), new BigDecimal("2040.00"), null),
                new KisIndexDaily(d.d2(), new BigDecimal("2020.00"), null)));
        assertThat(service.collect(d.base()).outcome(IndicatorCode.KOSPI).failure()).isEqualTo("IndicatorSeriesException");
        assertThat(dates(IndicatorCode.KOSPI, d)).isEmpty();
    }

    @Test
    @DisplayName("1-c. 같은 범위 재실행 → 변화 없음(행 수 · 값 그대로), 다음 실행 값이 바뀌면 덮어쓴다(잠정 → 확정)")
    void rerun_idempotent_thenCorrected() {
        Days d = Days.of(2014);
        stub("0001", d, "2000.00", "2020.00", "2040.40");
        stub("1001", d, "800.00", "808.00", "816.08");
        service.collect(d.base());
        service.collect(d.base());

        assertThat(dates(IndicatorCode.KOSPI, d)).containsExactly(d.d2(), d.base());
        assertThat(repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSPI, d.base()).orElseThrow().getValue())
                .isEqualByComparingTo("2040.40");

        stub("0001", d, "2000.00", "2020.00", "2042.42");
        service.collect(d.base());
        MacroIndicator corrected = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSPI, d.base()).orElseThrow();
        assertThat(corrected.getValue()).isEqualByComparingTo("2042.42");
        assertThat(corrected.getChangeRate()).isEqualByComparingTo("1.1099"); // 22.42 ÷ 2020 = 1.10990…
        assertThat(dates(IndicatorCode.KOSPI, d)).hasSize(2);
    }

    /** 최신순 3행: BASE · D2 · D1 */
    private void stub(String indexCode, Days d, String d1, String d2, String base) {
        when(kis.getIndexDaily(eq(indexCode), any(), any())).thenReturn(List.of(
                new KisIndexDaily(d.base(), new BigDecimal(base), null),
                new KisIndexDaily(d.d2(), new BigDecimal(d2), null),
                new KisIndexDaily(d.d1(), new BigDecimal(d1), null)));
    }

    private List<LocalDate> dates(IndicatorCode code, Days d) {
        return jdbc.queryForList("select base_date from macro_indicators where indicator_code = ? and base_date between ? and ? "
                + "order by base_date", LocalDate.class, code.name(), d.d1().minusDays(60), d.base());
    }
}
