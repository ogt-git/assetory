package com.project.assetory.snapshot.service;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.domain.TrendPeriod;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.AssetTrendResult.Point;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 총자산 기간별 추이 (FR-AN-05 · 06) — COMPLETE 기록 점 목록 + 기간 요약. 요약은 B4 /changes와 같은 기준
class AssetTrendQueryServiceIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

    @Autowired AssetTrendQueryService trendService;
    @Autowired AssetChangeQueryService changeService;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        userId = newUserId();
    }

    private Long newUserId() throws Exception {
        return userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow().getId();
    }

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    // COMPLETE 기록 (테스트 전용 더미 값) — 증권 = total − 현금 1,000,000, 예적금 0. 전일 대비 · market은 저장값 그대로
    private void complete(Long user, LocalDate date, String total, LocalDate base, String dayChange, String market) {
        BigDecimal t = d(total);
        BigDecimal cash = d("1000000");
        SnapshotJudgement judgement = new SnapshotJudgement(SnapshotStatus.COMPLETE, t, t.subtract(cash), d("0"), cash, List.of());
        DayChange day = base == null ? DayChange.NONE : new DayChange(base, d(dayChange), d("1.0000"));
        MarketChange marketChange = base == null || market == null ? MarketChange.NONE
                : new MarketChange(d(market), d(dayChange).subtract(d(market)));
        save(user, date, judgement, day, marketChange);
    }

    private void notComplete(Long user, LocalDate date, SnapshotStatus status) {
        SnapshotJudgement judgement = status == SnapshotStatus.FAILED
                ? new SnapshotJudgement(status, null, null, null, null, List.of())
                : new SnapshotJudgement(status, d("500000"), null, d("0"), d("500000"), List.of());
        save(user, date, judgement, DayChange.NONE, MarketChange.NONE);
    }

    private void save(Long user, LocalDate date, SnapshotJudgement judgement, DayChange day, MarketChange market) {
        transactionTemplate.executeWithoutResult(status -> {
            AssetSnapshot snapshot = AssetSnapshot.create(userRepository.getReferenceById(user), date);
            snapshot.apply(judgement, date.atTime(16, 5), day, market);
            snapshotRepository.save(snapshot);
        });
    }

    private static List<LocalDate> dates(AssetTrendResult result) {
        return result.points().stream().map(Point::date).toList();
    }

    // 1년치 기록: 2025-09-30(전년 기준) · 2026-08-25 · 2026-09-01(목표 날짜 당일 PARTIAL) · 9/15 FAILED · 9/20 · 10/1(asOf)
    private void yearOfRecords(Long user) {
        complete(user, LocalDate.of(2025, 9, 30), "2000000", null, null, null);
        complete(user, LocalDate.of(2026, 8, 25), "3000000", LocalDate.of(2025, 9, 30), "1000000", "700000");
        notComplete(user, LocalDate.of(2026, 9, 1), SnapshotStatus.PARTIAL);
        notComplete(user, LocalDate.of(2026, 9, 15), SnapshotStatus.FAILED);
        complete(user, LocalDate.of(2026, 9, 20), "3050000", LocalDate.of(2026, 8, 25), "50000", "20000");
        complete(user, AS_OF, "3100000", LocalDate.of(2026, 9, 20), "50000", "30000");
    }

    @Test
    @DisplayName("1. 1M 시작점 = 목표 날짜(9/1) 이하 마지막 COMPLETE — 9/1 당일이 PARTIAL이면 그 앞 8/25")
    void oneMonth_startIsLastCompleteOnOrBeforeTarget() {
        yearOfRecords(userId);

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ONE_MONTH);

        assertThat(result.period()).isEqualTo(TrendPeriod.ONE_MONTH);
        assertThat(result.asOfDate()).isEqualTo(AS_OF);
        assertThat(result.fullPeriod()).isTrue();
        assertThat(dates(result)).containsExactly(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 20), AS_OF);
        assertThat(result.summary().baseDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(result.summary().changeAmount()).isEqualByComparingTo("100000");
        assertThat(result.summary().marketChangeAmount()).isEqualByComparingTo("50000"); // 20,000 + 30,000
    }

    @Test
    @DisplayName("2. PARTIAL · FAILED는 점에 없다 (ALL)")
    void partialAndFailedExcluded() {
        yearOfRecords(userId);

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ALL);

        assertThat(dates(result)).containsExactly(LocalDate.of(2025, 9, 30), LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 9, 20), AS_OF);
    }

    @Test
    @DisplayName("3. 1Y 기간 미달 → 첫 COMPLETE부터, fullPeriod=false")
    void oneYear_shortHistory() {
        complete(userId, LocalDate.of(2026, 9, 10), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 10), "100000", "40000");

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ONE_YEAR);

        assertThat(result.fullPeriod()).isFalse();
        assertThat(dates(result)).containsExactly(LocalDate.of(2026, 9, 10), AS_OF);
        assertThat(result.summary().baseDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    @DisplayName("4. ALL → 첫 COMPLETE부터, fullPeriod=true")
    void all_fromFirstComplete() {
        complete(userId, LocalDate.of(2026, 9, 10), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 10), "100000", "40000");

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ALL);

        assertThat(result.fullPeriod()).isTrue();
        assertThat(dates(result)).containsExactly(LocalDate.of(2026, 9, 10), AS_OF);
    }

    @Test
    @DisplayName("5. B4 일치 — 1M full = month, 1Y full = year, ALL = sinceFirstRecord (AssetChangeQueryService와 직접 비교)")
    void matchesChangesFullPeriods() {
        yearOfRecords(userId);
        AssetChangeResult changes = changeService.getChanges(userId);

        assertSameComparison(trendService.getTrend(userId, TrendPeriod.ONE_MONTH).summary(), changes.month());
        assertSameComparison(trendService.getTrend(userId, TrendPeriod.ONE_YEAR).summary(), changes.year());
        assertSameComparison(trendService.getTrend(userId, TrendPeriod.ALL).summary(), changes.sinceFirstRecord());
        assertThat(changes.year().marketChangeAmount()).isEqualByComparingTo("750000"); // 700,000 + 20,000 + 30,000
    }

    @Test
    @DisplayName("5-1. B4 일치 — 기간 미달(fullPeriod=false)인 1M · 1Y = sinceFirstRecord")
    void matchesChangesShortPeriods() {
        complete(userId, LocalDate.of(2026, 9, 10), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 10), "100000", "40000");
        AssetChangeResult changes = changeService.getChanges(userId);

        assertThat(changes.month().available()).isFalse();
        assertSameComparison(trendService.getTrend(userId, TrendPeriod.ONE_MONTH).summary(), changes.sinceFirstRecord());
        assertSameComparison(trendService.getTrend(userId, TrendPeriod.ONE_YEAR).summary(), changes.sinceFirstRecord());
    }

    @Test
    @DisplayName("6. 기간 안 market NULL 행 → summary market · other NULL, 점 값은 저장값 그대로(NULL 유지, 0 아님)")
    void marketNullInRange() {
        complete(userId, LocalDate.of(2026, 9, 1), "3000000", null, null, null);
        complete(userId, LocalDate.of(2026, 9, 15), "3050000", LocalDate.of(2026, 9, 1), "50000", null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 15), "50000", "20000");

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ONE_MONTH);

        assertThat(result.summary().changeAmount()).isEqualByComparingTo("100000");
        assertThat(result.summary().marketChangeAmount()).isNull();
        assertThat(result.summary().otherChangeAmount()).isNull();
        Point transition = result.points().get(1);
        assertThat(transition.date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(transition.dayChangeAmount()).isEqualByComparingTo("50000");
        assertThat(transition.marketChangeAmount()).isNull();
        assertThat(transition.otherChangeAmount()).isNull();
    }

    @Test
    @DisplayName("7. 시작점에 저장된 market은 summary 합에 포함되지 않는다 — (시작, 끝] 기준")
    void startPointMarketExcluded() {
        complete(userId, LocalDate.of(2026, 8, 31), "2900000", null, null, null);
        complete(userId, LocalDate.of(2026, 9, 1), "3000000", LocalDate.of(2026, 8, 31), "100000", "999");  // 시작점
        complete(userId, LocalDate.of(2026, 9, 15), "3050000", LocalDate.of(2026, 9, 1), "50000", "10");
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 15), "50000", "20");

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ONE_MONTH);

        assertThat(result.points().get(0).marketChangeAmount()).isEqualByComparingTo("999"); // 점 값은 저장값 그대로
        assertThat(result.summary().marketChangeAmount()).isEqualByComparingTo("30");       // 10 + 20
        assertThat(result.summary().otherChangeAmount()).isEqualByComparingTo("99970");
    }

    @Test
    @DisplayName("8. 기록 0건 → 점 없음 · available=false / 1건 → 점 1개 · available=false")
    void zeroAndOneRecord() throws Exception {
        AssetTrendResult empty = trendService.getTrend(userId, TrendPeriod.ONE_YEAR);
        assertThat(empty.asOfDate()).isNull();
        assertThat(empty.points()).isEmpty();
        assertThat(empty.summary()).isEqualTo(Comparison.UNAVAILABLE);

        complete(userId, AS_OF, "3100000", null, null, null);
        for (TrendPeriod period : TrendPeriod.values()) {
            AssetTrendResult one = trendService.getTrend(userId, period);
            assertThat(dates(one)).as(period.code()).containsExactly(AS_OF);
            assertThat(one.summary()).as(period.code()).isEqualTo(Comparison.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("8-1. PARTIAL만 있음(COMPLETE 0건) → 점 없음 · available=false")
    void onlyPartial() {
        notComplete(userId, AS_OF, SnapshotStatus.PARTIAL);

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ALL);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isEqualTo(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("9. 같은 날짜 · 더 이른 날짜의 다른 사용자 행이 섞이지 않는다")
    void otherUserNotMixed() throws Exception {
        Long other = newUserId();
        complete(other, LocalDate.of(2025, 1, 1), "1", null, null, null);
        complete(other, LocalDate.of(2026, 9, 10), "9000000", null, null, null);
        complete(other, AS_OF, "9900000", LocalDate.of(2026, 9, 10), "900000", "1");
        complete(userId, LocalDate.of(2026, 9, 10), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 10), "100000", "40000");

        AssetTrendResult result = trendService.getTrend(userId, TrendPeriod.ALL);

        assertThat(dates(result)).containsExactly(LocalDate.of(2026, 9, 10), AS_OF);
        assertThat(result.points()).extracting(Point::totalAsset)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(d("3000000"), d("3100000"));
        assertThat(result.summary().marketChangeAmount()).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("10. 요청당 쿼리 수 — 1M · 1Y · ALL, 기간 미달 모두 3회 이하")
    void queryCount() throws Exception {
        yearOfRecords(userId);
        Long shortUser = newUserId();   // 기간 미달 — 시작 기준이 없어 첫 COMPLETE부터
        complete(shortUser, LocalDate.of(2026, 9, 10), "3000000", null, null, null);
        complete(shortUser, AS_OF, "3100000", LocalDate.of(2026, 9, 10), "100000", "40000");

        assertThat(statementsFor(() -> trendService.getTrend(userId, TrendPeriod.ONE_MONTH))).isLessThanOrEqualTo(3);
        assertThat(statementsFor(() -> trendService.getTrend(userId, TrendPeriod.ONE_YEAR))).isLessThanOrEqualTo(3);
        assertThat(statementsFor(() -> trendService.getTrend(userId, TrendPeriod.ALL))).isLessThanOrEqualTo(3);
        assertThat(statementsFor(() -> trendService.getTrend(shortUser, TrendPeriod.ONE_YEAR))).isLessThanOrEqualTo(3);
    }

    private long statementsFor(Runnable call) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            call.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // 금액은 compareTo로(스케일 차이 무시) 비교
    private static void assertSameComparison(Comparison trend, Comparison change) {
        assertThat(trend).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(change);
        assertThat(trend.available()).isTrue();
    }
}
