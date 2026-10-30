package com.project.assetory.snapshot.service;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
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

// 전일 · 전월 · 전년 · 누적 증감 조회 (FR-AN-01 · 02 · 03 · 06) — 저장된 COMPLETE 기록만 쓰고, 계산은 AssetChangeCalculator
class AssetChangeQueryServiceIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

    @Autowired AssetChangeQueryService queryService;
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

    // COMPLETE 기록 — 자산군: 증권 = total − 현금 1,000,000, 예적금 0. base · 전일 대비 · market/other는 저장값 그대로 (테스트 전용 더미 값)
    private void complete(Long user, LocalDate date, String total, LocalDate base, String dayChange, String market) {
        BigDecimal cash = d("1000000");
        BigDecimal t = d(total);
        SnapshotJudgement judgement = new SnapshotJudgement(SnapshotStatus.COMPLETE, t, t.subtract(cash), d("0"), cash, List.of());
        DayChange day = base == null ? DayChange.NONE : new DayChange(base, d(dayChange), d("1.0000"));
        MarketChange marketChange = base == null || market == null
                ? MarketChange.NONE
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

    @Test
    @DisplayName("1. COMPLETE 사이의 PARTIAL · FAILED는 건너뛰고 COMPLETE끼리 비교 (전월 기준 = 9/1 이하 마지막 COMPLETE)")
    void skipsPartialAndFailed() {
        complete(userId, LocalDate.of(2026, 8, 25), "3000000", null, null, null);
        notComplete(userId, LocalDate.of(2026, 9, 1), SnapshotStatus.PARTIAL);   // 목표 날짜(9/1) 당일이지만 PARTIAL
        notComplete(userId, LocalDate.of(2026, 9, 20), SnapshotStatus.FAILED);
        complete(userId, AS_OF, "3300000", LocalDate.of(2026, 8, 25), "300000", "100000");

        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.asOfDate()).isEqualTo(AS_OF);
        assertThat(result.totalAsset()).isEqualByComparingTo("3300000");
        Comparison month = result.month();
        assertThat(month.available()).isTrue();
        assertThat(month.baseDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(month.changeAmount()).isEqualByComparingTo("300000");
        assertThat(result.day().baseDate()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    @DisplayName("2. 최근 기록이 PARTIAL → latestRecord는 PARTIAL, 비교 기준(asOfDate)은 최근 COMPLETE")
    void latestPartial() {
        complete(userId, LocalDate.of(2026, 9, 30), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 30), "100000", "40000");
        notComplete(userId, AS_OF.plusDays(1), SnapshotStatus.PARTIAL);

        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.latestRecord().date()).isEqualTo(AS_OF.plusDays(1));
        assertThat(result.latestRecord().status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(result.asOfDate()).isEqualTo(AS_OF);
        assertThat(result.day().changeAmount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("3. 기록 20일 → 전월 · 전년 available=false, 누적 변화(첫 COMPLETE ~ 최근)는 있음")
    void twentyDays_monthYearUnavailable() {
        LocalDate first = AS_OF.minusDays(19);
        complete(userId, first, "3000000", null, null, null);
        for (int i = 1; i < 20; i++) {
            LocalDate date = first.plusDays(i);
            complete(userId, date, String.valueOf(3000000 + i * 1000), date.minusDays(1), "1000", "500");
        }

        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.month()).isEqualTo(Comparison.UNAVAILABLE);
        assertThat(result.year()).isEqualTo(Comparison.UNAVAILABLE);
        assertThat(result.sinceFirstRecord().available()).isTrue();
        assertThat(result.sinceFirstRecord().baseDate()).isEqualTo(first);
        assertThat(result.sinceFirstRecord().changeAmount()).isEqualByComparingTo("19000");
        assertThat(result.sinceFirstRecord().marketChangeAmount()).isEqualByComparingTo("9500"); // 19행 × 500
        assertThat(result.sinceFirstRecord().otherChangeAmount()).isEqualByComparingTo("9500");
    }

    @Test
    @DisplayName("4. 기록 0건 → 예외 없이 전부 available=false (정상 상태)")
    void noRecords() {
        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.latestRecord()).isNull();
        assertThat(result.asOfDate()).isNull();
        assertThat(result.totalAsset()).isNull();
        assertThat(List.of(result.day(), result.month(), result.year(), result.sinceFirstRecord()))
                .containsOnly(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("4-1. PARTIAL만 있음(COMPLETE 0건) → latestRecord만 있고 비교는 전부 available=false")
    void onlyPartial() {
        notComplete(userId, AS_OF, SnapshotStatus.PARTIAL);

        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.latestRecord().status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(result.asOfDate()).isNull();
        assertThat(List.of(result.day(), result.month(), result.year(), result.sinceFirstRecord()))
                .containsOnly(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("5. 다른 사용자의 같은 날짜 · 더 최근 스냅샷이 섞이지 않는다 (소유자 범위)")
    void otherUserNotMixed() throws Exception {
        Long other = newUserId();
        complete(other, LocalDate.of(2025, 9, 1), "1", null, null, null);                 // 내 전년 기준이 될 수 있는 날짜
        complete(other, LocalDate.of(2026, 8, 25), "9000000", null, null, null);
        complete(other, AS_OF, "9900000", LocalDate.of(2026, 8, 25), "900000", "1");
        notComplete(other, AS_OF.plusDays(5), SnapshotStatus.PARTIAL);
        complete(userId, LocalDate.of(2026, 9, 30), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 30), "100000", "40000");

        AssetChangeResult result = queryService.getChanges(userId);

        assertThat(result.latestRecord().date()).isEqualTo(AS_OF);
        assertThat(result.totalAsset()).isEqualByComparingTo("3100000");
        assertThat(result.month()).isEqualTo(Comparison.UNAVAILABLE);             // 내 기록은 9/30부터
        assertThat(result.year()).isEqualTo(Comparison.UNAVAILABLE);
        assertThat(result.sinceFirstRecord().baseDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(result.sinceFirstRecord().marketChangeAmount()).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("6. 전일 대비 = 최근 COMPLETE 행에 저장된 base · day_change · market · other 그대로")
    void dayIsStoredValues() {
        complete(userId, LocalDate.of(2026, 9, 30), "3000000", null, null, null);
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 30), "99999.99", "-5400");

        Comparison day = queryService.getChanges(userId).day();

        assertThat(day.baseDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(day.baseTotal()).isEqualByComparingTo("3000000");
        assertThat(day.changeAmount()).isEqualByComparingTo("99999.99");   // 두 행 차이(100,000)가 아니라 저장값
        assertThat(day.changeRate()).isEqualByComparingTo("1.0000");
        assertThat(day.marketChangeAmount()).isEqualByComparingTo("-5400");
        assertThat(day.otherChangeAmount()).isEqualByComparingTo("105399.99");
        assertThat(day.assetClassChanges().securities()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("7. 기간 안에 market NULL 행(B2 이전 전환기) 하나 → 월 market · other NULL, 증감액은 정상")
    void marketNullInRange() {
        complete(userId, LocalDate.of(2026, 9, 1), "3000000", null, null, null);
        complete(userId, LocalDate.of(2026, 9, 15), "3050000", LocalDate.of(2026, 9, 1), "50000", null); // 전환기 — market NULL
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 15), "50000", "20000");

        Comparison month = queryService.getChanges(userId).month();

        assertThat(month.available()).isTrue();
        assertThat(month.changeAmount()).isEqualByComparingTo("100000");
        assertThat(month.marketChangeAmount()).isNull();
        assertThat(month.otherChangeAmount()).isNull();
    }

    @Test
    @DisplayName("8. 기간 market 합은 (기준, 최근] — 기준일 행의 market은 포함하지 않는다")
    void marketSumExcludesBaseRow() {
        complete(userId, LocalDate.of(2026, 8, 31), "2900000", null, null, null);
        complete(userId, LocalDate.of(2026, 9, 1), "3000000", LocalDate.of(2026, 8, 31), "100000", "999");  // 기준일 행
        complete(userId, LocalDate.of(2026, 9, 15), "3050000", LocalDate.of(2026, 9, 1), "50000", "10");
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 15), "50000", "20");

        Comparison month = queryService.getChanges(userId).month();

        assertThat(month.baseDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.marketChangeAmount()).isEqualByComparingTo("30");          // 10 + 20 (999 제외)
        assertThat(month.otherChangeAmount()).isEqualByComparingTo("99970");        // 100,000 − 30
    }

    @Test
    @DisplayName("전년 비교 + 요청당 쿼리 수 7회 이하")
    void yearComparison_queryCount() {
        complete(userId, LocalDate.of(2025, 9, 30), "2000000", null, null, null);
        complete(userId, LocalDate.of(2026, 9, 1), "3000000", LocalDate.of(2025, 9, 30), "1000000", "600000");
        complete(userId, AS_OF, "3100000", LocalDate.of(2026, 9, 1), "100000", "40000");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        AssetChangeResult result;
        try {
            result = queryService.getChanges(userId);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(result.year().baseDate()).isEqualTo(LocalDate.of(2025, 9, 30));
        assertThat(result.year().changeAmount()).isEqualByComparingTo("1100000");
        assertThat(result.year().marketChangeAmount()).isEqualByComparingTo("640000");
        assertThat(result.month().baseDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(7);
    }
}
