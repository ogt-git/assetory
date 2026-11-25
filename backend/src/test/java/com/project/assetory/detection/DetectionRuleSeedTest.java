package com.project.assetory.detection;

import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.Severity;
import com.project.assetory.detection.domain.TriggerType;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// detection_rules 시드(data.sql)가 05 §8.1 초기 데이터와 일치하는지 고정한다 (name · severity · unit NULL은 05에 없는 제안값)
class DetectionRuleSeedTest extends IntegrationTestSupport {

    @Autowired DetectionRuleRepository detectionRuleRepository;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("시드는 6행이고 id 1~6이 rule_code에 고정돼 있다")
    void seededSixRulesWithFixedIds() {
        Map<Long, String> idToCode = detectionRuleRepository.findAll().stream()
                .collect(Collectors.toMap(DetectionRule::getId, DetectionRule::getRuleCode));

        assertThat(idToCode).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "STOCK_PRICE_CHANGE",
                2L, "TOTAL_ASSET_CHANGE",
                3L, "DEPOSIT_MATURITY",
                4L, "DAILY_BRIEFING",
                5L, "GOAL_PROGRESS_WEEKLY",
                6L, "MONTHLY_REPORT"));
    }

    @Test
    @DisplayName("종목 급등락: 기본 5% · 허용 2~20 · 개별 일 3건 · 초과분 묶음")
    void stockPriceChange() {
        DetectionRule r = rule("STOCK_PRICE_CHANGE");
        assertThat(r.getTriggerType()).isEqualTo(TriggerType.STOCK_CHANGE);
        assertThat(r.getName()).isEqualTo("종목 급등락");
        assertThat(r.getDefaultThreshold()).isEqualByComparingTo("5.0");
        assertThat(r.getUnit()).isEqualTo("%");
        assertThat(r.getMinValue()).isEqualByComparingTo("2");
        assertThat(r.getMaxValue()).isEqualByComparingTo("20");
        assertThat(r.getIndividualLimitPerDay()).isEqualTo(3);
        assertThat(r.isBundleOverflow()).isTrue();
        assertThat(r.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(r.isUserConfigurable()).isTrue();
        assertThat(r.isActive()).isTrue();
    }

    @Test
    @DisplayName("총자산 변동: 기본 3% · 허용 1~15 · 일 1건 · 묶음 없음")
    void totalAssetChange() {
        DetectionRule r = rule("TOTAL_ASSET_CHANGE");
        assertThat(r.getTriggerType()).isEqualTo(TriggerType.ASSET_CHANGE);
        assertThat(r.getDefaultThreshold()).isEqualByComparingTo("3.0");
        assertThat(r.getUnit()).isEqualTo("%");
        assertThat(r.getMinValue()).isEqualByComparingTo("1");
        assertThat(r.getMaxValue()).isEqualByComparingTo("15");
        assertThat(r.getIndividualLimitPerDay()).isEqualTo(1);
        assertThat(r.isBundleOverflow()).isFalse();
        assertThat(r.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(r.isUserConfigurable()).isTrue();
    }

    @Test
    @DisplayName("예금 만기: 기본 30일 · 허용 1~90 · 일 상한 없음(건별 2회)")
    void depositMaturity() {
        DetectionRule r = rule("DEPOSIT_MATURITY");
        assertThat(r.getTriggerType()).isEqualTo(TriggerType.DEPOSIT_MATURITY);
        assertThat(r.getDefaultThreshold()).isEqualByComparingTo("30");
        assertThat(r.getUnit()).isEqualTo("일");
        assertThat(r.getMinValue()).isEqualByComparingTo("1");
        assertThat(r.getMaxValue()).isEqualByComparingTo("90");
        assertThat(r.getIndividualLimitPerDay()).isNull();
        assertThat(r.isBundleOverflow()).isFalse();
        assertThat(r.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(r.isUserConfigurable()).isTrue();
    }

    @Test
    @DisplayName("브리핑 · 목표 · 결산: 임계치 · 단위 · 범위 없음, 임계치 조정 불가")
    void noThresholdRules() {
        assertNoThreshold(rule("DAILY_BRIEFING"), TriggerType.BRIEFING, 1);
        assertNoThreshold(rule("GOAL_PROGRESS_WEEKLY"), TriggerType.GOAL_PROGRESS, null);
        assertNoThreshold(rule("MONTHLY_REPORT"), TriggerType.MONTHLY_REPORT, null);
    }

    @Test
    @DisplayName("data.sql을 다시 실행해도 6행 · id · 값이 그대로다 (멱등)")
    void seedIsIdempotent() {
        Map<String, Long> before = codeToId();

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);

        assertThat(detectionRuleRepository.count()).isEqualTo(6);
        assertThat(codeToId()).isEqualTo(before);
        assertThat(rule("STOCK_PRICE_CHANGE").getDefaultThreshold()).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("활성 규칙 조회는 id 순서로 6개를 돌려준다")
    void findAllActiveOrderedById() {
        List<Long> ids = detectionRuleRepository.findAllByActiveTrueOrderById().stream()
                .map(DetectionRule::getId).toList();

        assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    private void assertNoThreshold(DetectionRule r, TriggerType type, Integer limitPerDay) {
        assertThat(r.getTriggerType()).isEqualTo(type);
        assertThat(r.getDefaultThreshold()).isNull();
        assertThat(r.getUnit()).isNull();
        assertThat(r.getMinValue()).isNull();
        assertThat(r.getMaxValue()).isNull();
        assertThat(r.getIndividualLimitPerDay()).isEqualTo(limitPerDay);
        assertThat(r.isBundleOverflow()).isFalse();
        assertThat(r.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(r.isUserConfigurable()).isFalse();
        assertThat(r.isActive()).isTrue();
        assertThat(r.isWithinRange(BigDecimal.ONE)).isFalse();
    }

    private DetectionRule rule(String code) {
        return detectionRuleRepository.findByRuleCode(code).orElseThrow();
    }

    private Map<String, Long> codeToId() {
        return detectionRuleRepository.findAll().stream()
                .collect(Collectors.toMap(DetectionRule::getRuleCode, DetectionRule::getId));
    }
}
