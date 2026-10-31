package com.project.assetory.snapshot.service;

import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.snapshot.domain.AssetChangeCalculator.Comparison;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.SnapshotMonthResult.Record;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 자산 기록 월별 목록 (FR-AS-06, 03 S-02, 01 §14 #19) — 누락일은 직전 COMPLETE 값 + 기준 날짜, 월 요약은 그 달 첫 · 마지막 COMPLETE (FR-MR-01)
class SnapshotMonthQueryIntegrationTest extends IntegrationTestSupport {

    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @Autowired SnapshotRecordQueryService recordService;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

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

    private static LocalDate sep(int day) {
        return SEP.atDay(day);
    }

    // COMPLETE 기록 (테스트 전용 더미 값) — 증권 = total − 현금 1,000,000. 전일 대비 · market은 저장값 그대로
    private void complete(Long user, LocalDate date, String total, LocalDate base, String dayChange, String market) {
        BigDecimal t = d(total);
        BigDecimal cash = d("1000000");
        SnapshotJudgement judgement = new SnapshotJudgement(SnapshotStatus.COMPLETE, t, t.subtract(cash), d("0"), cash, List.of());
        DayChange day = base == null ? DayChange.NONE : new DayChange(base, d(dayChange), d("1.0000"));
        MarketChange marketChange = base == null || market == null ? MarketChange.NONE
                : new MarketChange(d(market), d(dayChange).subtract(d(market)));
        save(user, date, judgement, day, marketChange);
    }

    // PARTIAL은 부분 금액 777,777을 저장 — 응답 어디에도 나오면 안 된다
    private void notComplete(Long user, LocalDate date, SnapshotStatus status) {
        SnapshotJudgement judgement = status == SnapshotStatus.FAILED
                ? new SnapshotJudgement(status, null, null, null, null, List.of())
                : new SnapshotJudgement(status, d("777777"), null, d("0"), d("777777"), List.of());
        save(user, date, judgement, DayChange.NONE, MarketChange.NONE);
    }

    private void save(Long user, LocalDate date, SnapshotJudgement judgement, DayChange day, MarketChange market) {
        transactionTemplate.executeWithoutResult(status -> {
            AssetSnapshot snapshot = AssetSnapshot.create(userRepository.getReferenceById(user), date);
            snapshot.apply(judgement, date.atTime(16, 5), day, market);
            snapshotRepository.save(snapshot);
        });
    }

    private static Record record(SnapshotMonthResult result, LocalDate date) {
        return result.records().stream().filter(r -> r.date().equals(date)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("1. COMPLETE · PARTIAL · FAILED 섞인 달 → 내림차순, 누락 행은 missing · 직전 COMPLETE 총자산 · 기준 날짜 · 증감 null, 부분 금액 없음")
    void mixedMonth() {
        complete(userId, sep(1), "3000000", null, null, null);
        notComplete(userId, sep(2), SnapshotStatus.PARTIAL);
        complete(userId, sep(3), "3050000", sep(1), "50000", "20000");
        notComplete(userId, sep(4), SnapshotStatus.FAILED);
        jdbc.update("update asset_snapshots set has_event = true where user_id = ? and snapshot_date = ?", userId, sep(3));

        SnapshotMonthResult result = recordService.getMonth(userId, SEP);

        assertThat(result.month()).isEqualTo(SEP);
        assertThat(result.records()).extracting(Record::date).containsExactly(sep(4), sep(3), sep(2), sep(1));

        Record partial = record(result, sep(2));
        assertThat(partial.status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(partial.missing()).isTrue();
        assertThat(partial.totalAsset()).isEqualByComparingTo("3000000");      // 직전 COMPLETE, 부분 금액 777,777 아님
        assertThat(partial.referenceDate()).isEqualTo(sep(1));
        assertThat(partial.dayChangeAmount()).isNull();
        assertThat(partial.dayChangeRate()).isNull();
        assertThat(partial.marketChangeAmount()).isNull();
        assertThat(partial.otherChangeAmount()).isNull();

        Record failed = record(result, sep(4));
        assertThat(failed.missing()).isTrue();
        assertThat(failed.totalAsset()).isEqualByComparingTo("3050000");
        assertThat(failed.referenceDate()).isEqualTo(sep(3));

        Record day3 = record(result, sep(3));
        assertThat(day3.missing()).isFalse();
        assertThat(day3.referenceDate()).isEqualTo(sep(3));
        assertThat(day3.totalAsset()).isEqualByComparingTo("3050000");
        assertThat(day3.dayChangeAmount()).isEqualByComparingTo("50000");
        assertThat(day3.marketChangeAmount()).isEqualByComparingTo("20000");
        assertThat(day3.hasEvent()).isTrue();
        assertThat(record(result, sep(1)).hasEvent()).isFalse();

        // 부분 금액이 어떤 숫자 필드로도 나오지 않는다
        assertThat(result.records()).flatExtracting(r -> java.util.Arrays.asList(r.totalAsset(), r.dayChangeAmount(),
                        r.marketChangeAmount(), r.otherChangeAmount()))
                .filteredOn(java.util.Objects::nonNull)
                .noneMatch(v -> ((BigDecimal) v).compareTo(d("777777")) == 0);
    }

    @Test
    @DisplayName("2. 달 첫날이 PARTIAL → 직전 COMPLETE를 전달에서 가져옴")
    void firstDayPartial_usesPreviousMonth() {
        complete(userId, LocalDate.of(2026, 8, 31), "2900000", null, null, null);
        notComplete(userId, sep(1), SnapshotStatus.PARTIAL);
        complete(userId, sep(2), "3000000", LocalDate.of(2026, 8, 31), "100000", "50000");

        Record first = record(recordService.getMonth(userId, SEP), sep(1));

        assertThat(first.totalAsset()).isEqualByComparingTo("2900000");
        assertThat(first.referenceDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("3. 첫 기록 이전에 누락만 → totalAsset · referenceDate null (0 아님)")
    void missingBeforeAnyComplete() {
        notComplete(userId, sep(1), SnapshotStatus.FAILED);
        notComplete(userId, sep(2), SnapshotStatus.PARTIAL);

        SnapshotMonthResult result = recordService.getMonth(userId, SEP);

        assertThat(result.records()).hasSize(2).allSatisfy(r -> {
            assertThat(r.missing()).isTrue();
            assertThat(r.totalAsset()).isNull();
            assertThat(r.referenceDate()).isNull();
        });
        assertThat(result.summary()).isEqualTo(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("4. summary = 그 달 첫 · 마지막 COMPLETE, market은 (첫, 마지막] 일별 합 — 첫 COMPLETE 행의 market 제외")
    void summary_firstToLastComplete() {
        complete(userId, LocalDate.of(2026, 8, 31), "2900000", null, null, null);
        complete(userId, sep(1), "3000000", LocalDate.of(2026, 8, 31), "100000", "999");  // 그 달 첫 COMPLETE — 전월 말 대비 변화는 요약에 넣지 않음
        notComplete(userId, sep(2), SnapshotStatus.PARTIAL);
        complete(userId, sep(15), "3050000", sep(1), "50000", "10");
        complete(userId, sep(30), "3100000", sep(15), "50000", "20");
        complete(userId, LocalDate.of(2026, 10, 1), "9999999", sep(30), "6899999", "1");  // 다음 달 — 포함하지 않음

        Comparison summary = recordService.getMonth(userId, SEP).summary();

        assertThat(summary.available()).isTrue();
        assertThat(summary.baseDate()).isEqualTo(sep(1));
        assertThat(summary.baseTotal()).isEqualByComparingTo("3000000");
        assertThat(summary.changeAmount()).isEqualByComparingTo("100000");
        assertThat(summary.marketChangeAmount()).isEqualByComparingTo("30");      // 10 + 20 (999 제외)
        assertThat(summary.otherChangeAmount()).isEqualByComparingTo("99970");
    }

    @Test
    @DisplayName("4-1. summary 기간 안 market NULL 행 → market · other NULL, 증감액은 정상")
    void summary_marketNull() {
        complete(userId, sep(1), "3000000", null, null, null);
        complete(userId, sep(15), "3050000", sep(1), "50000", null);
        complete(userId, sep(30), "3100000", sep(15), "50000", "20");

        Comparison summary = recordService.getMonth(userId, SEP).summary();

        assertThat(summary.changeAmount()).isEqualByComparingTo("100000");
        assertThat(summary.marketChangeAmount()).isNull();
        assertThat(summary.otherChangeAmount()).isNull();
    }

    @Test
    @DisplayName("5. COMPLETE가 1개인 달 → summary.available=false")
    void oneCompleteInMonth() {
        complete(userId, sep(10), "3000000", null, null, null);
        notComplete(userId, sep(11), SnapshotStatus.PARTIAL);

        assertThat(recordService.getMonth(userId, SEP).summary()).isEqualTo(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("6. 상승 · 하락일 — COMPLETE 행의 전일 대비 부호(NULL · 0 · 누락 행 제외)")
    void risingFallingDays() {
        complete(userId, sep(1), "3000000", null, null, null);
        complete(userId, sep(2), "3010000", sep(1), "10000", "0");
        complete(userId, sep(3), "3010000", sep(2), "0", "0");
        complete(userId, sep(4), "3000000", sep(3), "-10000", "0");
        complete(userId, sep(7), "3020000", sep(4), "20000", "0");
        notComplete(userId, sep(8), SnapshotStatus.PARTIAL);

        SnapshotMonthResult result = recordService.getMonth(userId, SEP);

        assertThat(result.risingDays()).isEqualTo(2);
        assertThat(result.fallingDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. month 생략 → 상태 무관 최근 기록이 있는 달, first · latestRecordMonth / 기록 없는 달 → 빈 목록")
    void defaultMonthAndRange() {
        complete(userId, LocalDate.of(2026, 7, 20), "2800000", null, null, null);
        complete(userId, sep(1), "3000000", LocalDate.of(2026, 7, 20), "200000", "0");
        notComplete(userId, LocalDate.of(2026, 10, 2), SnapshotStatus.PARTIAL);  // 최근 기록은 10월 PARTIAL

        SnapshotMonthResult latest = recordService.getMonth(userId, null);
        assertThat(latest.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(latest.firstRecordMonth()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(latest.latestRecordMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(latest.records()).extracting(Record::date).containsExactly(LocalDate.of(2026, 10, 2));
        assertThat(latest.records().get(0).totalAsset()).isEqualByComparingTo("3000000");

        SnapshotMonthResult august = recordService.getMonth(userId, YearMonth.of(2026, 8));
        assertThat(august.month()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(august.records()).isEmpty();
        assertThat(august.summary()).isEqualTo(Comparison.UNAVAILABLE);
        assertThat(august.risingDays()).isZero();
        assertThat(august.fallingDays()).isZero();
        assertThat(august.firstRecordMonth()).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    @DisplayName("7-1. 기록이 전혀 없음 → month 생략 시 month · first · latest 모두 null, 빈 목록")
    void noRecordsAtAll() {
        SnapshotMonthResult result = recordService.getMonth(userId, null);

        assertThat(result.month()).isNull();
        assertThat(result.firstRecordMonth()).isNull();
        assertThat(result.latestRecordMonth()).isNull();
        assertThat(result.records()).isEmpty();
        assertThat(result.summary()).isEqualTo(Comparison.UNAVAILABLE);
    }

    @Test
    @DisplayName("8. 다른 사용자의 같은 달 · 전달 행이 섞이지 않는다")
    void otherUserNotMixed() throws Exception {
        Long other = newUserId();
        complete(other, LocalDate.of(2026, 8, 31), "1", null, null, null);
        complete(other, sep(2), "9000000", null, null, null);
        complete(other, LocalDate.of(2026, 11, 1), "9100000", sep(2), "100000", "0");
        notComplete(userId, sep(1), SnapshotStatus.PARTIAL);
        complete(userId, sep(3), "3000000", null, null, null);

        SnapshotMonthResult result = recordService.getMonth(userId, SEP);

        assertThat(result.records()).extracting(Record::date).containsExactly(sep(3), sep(1));
        assertThat(record(result, sep(1)).totalAsset()).isNull();     // 다른 사용자의 8/31 COMPLETE를 쓰지 않음
        assertThat(result.latestRecordMonth()).isEqualTo(SEP);
        assertThat(result.firstRecordMonth()).isEqualTo(SEP);
    }

    @Test
    @DisplayName("9. 요청당 쿼리 수 — 월 지정 · 생략 모두 3회 이하")
    void queryCount() {
        complete(userId, LocalDate.of(2026, 8, 31), "2900000", null, null, null);
        notComplete(userId, sep(1), SnapshotStatus.PARTIAL);
        complete(userId, sep(2), "3000000", LocalDate.of(2026, 8, 31), "100000", "50000");
        complete(userId, sep(3), "3010000", sep(2), "10000", "5000");

        assertThat(statementsFor(() -> recordService.getMonth(userId, SEP))).isLessThanOrEqualTo(3);
        assertThat(statementsFor(() -> recordService.getMonth(userId, null))).isLessThanOrEqualTo(3);
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
}
