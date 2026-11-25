package com.project.assetory.market;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.IndicatorSource;
import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.domain.MacroIndicatorRow;
import com.project.assetory.market.repository.MacroIndicatorRepository;
import com.project.assetory.market.service.MacroIndicatorWriter;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/05 §4.10 macro_indicators — 지수 · 거시지표 일별 값. UNIQUE (indicator_code, base_date) 기준 업서트.
 * 날짜는 2000년대로 둔다 — 같은 DB를 쓰는 다른 테스트(지수 수집)와 겹치지 않게
 */
class MacroIndicatorRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired MacroIndicatorWriter writer;
    @Autowired MacroIndicatorRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("1. 같은 (지표, 날짜) 업서트 → 값 · 변동률만 갱신(잠정 → 확정), 행 수 그대로")
    void upsert_updatesValue() {
        LocalDate date = LocalDate.of(2001, 3, 5);
        writer.upsertAll(List.of(new MacroIndicatorRow(IndicatorCode.KOSPI, date, new BigDecimal("2600.1200"), new BigDecimal("1.2300"))));

        int saved = writer.upsertAll(List.of(new MacroIndicatorRow(IndicatorCode.KOSPI, date, new BigDecimal("2601.5000"), new BigDecimal("1.2843"))));

        assertThat(saved).isEqualTo(1);
        assertThat(count(IndicatorCode.KOSPI, date)).isEqualTo(1);
        MacroIndicator found = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSPI, date).orElseThrow();
        assertThat(found.getValue()).isEqualByComparingTo("2601.5000");
        assertThat(found.getChangeRate()).isEqualByComparingTo("1.2843");
        assertThat(found.getSource()).isEqualTo(IndicatorSource.KIS);
    }

    @Test
    @DisplayName("2. change_rate NULL 저장 · 조회 — 기준금리는 값만 저장한다(B4 확정 사항 4)")
    void changeRateNull() {
        LocalDate date = LocalDate.of(2001, 3, 6);

        writer.upsertAll(List.of(new MacroIndicatorRow(IndicatorCode.BASE_RATE, date, new BigDecimal("2.5000"), null)));

        MacroIndicator found = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.BASE_RATE, date).orElseThrow();
        assertThat(found.getChangeRate()).isNull();
        assertThat(found.getSource()).isEqualTo(IndicatorSource.ECOS);
        assertThat(jdbc.queryForObject("select change_rate from macro_indicators where id = ?", BigDecimal.class, found.getId())).isNull();
    }

    @Test
    @DisplayName("3. 같은 목록을 두 번 업서트 → 변화 없음(행 수 · 값 · updated_at 그대로)")
    void sameListTwice_noChange() {
        LocalDate d1 = LocalDate.of(2001, 3, 7);
        LocalDate d2 = LocalDate.of(2001, 3, 8);
        List<MacroIndicatorRow> rows = List.of(
                new MacroIndicatorRow(IndicatorCode.KOSDAQ, d1, new BigDecimal("850.1000"), new BigDecimal("-0.5000")),
                new MacroIndicatorRow(IndicatorCode.KOSDAQ, d2, new BigDecimal("855.2000"), new BigDecimal("0.5999")));
        writer.upsertAll(rows);
        LocalDateTime updatedAt = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSDAQ, d2).orElseThrow().getUpdatedAt();

        writer.upsertAll(rows);

        assertThat(count(IndicatorCode.KOSDAQ, d1) + count(IndicatorCode.KOSDAQ, d2)).isEqualTo(2);
        MacroIndicator again = repository.findByIndicatorCodeAndBaseDate(IndicatorCode.KOSDAQ, d2).orElseThrow();
        assertThat(again.getValue()).isEqualByComparingTo("855.2000");
        assertThat(again.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("지표 코드는 VARCHAR(30) · source는 ENUM(KIS · ECOS) · change_rate만 NULL 허용, UNIQUE (indicator_code, base_date) — 05 §4.10 · §10")
    void columnsMatchErd() {
        Map<String, Map<String, Object>> columns = new java.util.HashMap<>();
        jdbc.queryForList("""
                select column_name, data_type, column_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
                from information_schema.columns where table_schema = database() and table_name = 'macro_indicators'""")
                .forEach(c -> columns.put(((String) c.get("column_name")).toLowerCase(), c));

        assertThat(columns.get("indicator_code")).containsEntry("data_type", "varchar").containsEntry("is_nullable", "NO");
        assertThat(((Number) columns.get("indicator_code").get("character_maximum_length")).intValue()).isEqualTo(30);
        assertThat(columns.get("base_date")).containsEntry("data_type", "date").containsEntry("is_nullable", "NO");
        assertThat(columns.get("value")).containsEntry("data_type", "decimal").containsEntry("is_nullable", "NO");
        assertThat(((Number) columns.get("value").get("numeric_precision")).intValue()).isEqualTo(18);
        assertThat(((Number) columns.get("value").get("numeric_scale")).intValue()).isEqualTo(4);
        assertThat(columns.get("change_rate")).containsEntry("data_type", "decimal").containsEntry("is_nullable", "YES");
        assertThat(((Number) columns.get("change_rate").get("numeric_precision")).intValue()).isEqualTo(8);
        assertThat(((Number) columns.get("change_rate").get("numeric_scale")).intValue()).isEqualTo(4);
        assertThat(columns.get("source")).containsEntry("column_type", "enum('ECOS','KIS')").containsEntry("is_nullable", "NO");
        assertThat(columns).containsKeys("created_at", "updated_at");

        assertThat(jdbc.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'macro_indicators' and index_name = 'uk_macro_indicators_code_date'
                order by seq_in_index""", String.class)).containsExactly("indicator_code", "base_date");
    }

    private int count(IndicatorCode code, LocalDate date) {
        return jdbc.queryForObject("select count(*) from macro_indicators where indicator_code = ? and base_date = ?",
                Integer.class, code.name(), date);
    }
}
