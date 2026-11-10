package com.project.assetory.fake;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.fake.DemoScenario.Day;
import com.project.assetory.fake.DemoScenario.DemoUser;
import com.project.assetory.fake.DemoScenario.HoldingDay;
import com.project.assetory.fake.DemoScenario.ManualDay;
import com.project.assetory.fake.DemoScenario.PricePoint;
import com.project.assetory.fake.DemoScenario.UserPlan;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotJudge;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// fake 프로필 데모 시나리오 — 스프링 · DB 없는 순수 계산. 상태는 실제 SnapshotJudge로 판정해 확인한다
class DemoScenarioTest {

    // 금요일 기준 — 직전 평일은 목요일
    private static final LocalDate BASE = LocalDate.of(2026, 10, 30);
    private static final DemoScenario SCENARIO = DemoScenario.of(BASE);

    // 판정용 더미 id — 시드(Phase 3)에서 실제 assets id로 바뀐다
    private static final Long ACCOUNT_ID = 1L;
    private static final Map<AssetType, Long> MANUAL_IDS = Map.of(AssetType.CASH, 2L, AssetType.DEPOSIT, 3L);

    private static Optional<SnapshotStatus> status(Day day) {
        SnapshotInput input = day.toInput(ACCOUNT_ID, MANUAL_IDS);
        return SnapshotJudge.judge(input).map(j -> j.status());
    }

    private static long count(UserPlan plan, SnapshotStatus status) {
        return plan.days().stream().filter(d -> status(d).orElseThrow() == status).count();
    }

    private static BigDecimal quantity(Day day, String ticker) {
        return day.account().holdings().stream()
                .filter(h -> h.ticker().equals(ticker))
                .map(HoldingDay::quantity)
                .findFirst().orElse(null);
    }

    @Nested
    @DisplayName("날짜")
    class Dates {

        @Test
        @DisplayName("평일만 만들고, 마지막 날은 기동일 직전 평일 — 기동일 당일은 없다")
        void weekdaysOnlyEndingBeforeBase() {
            List<LocalDate> calendar = SCENARIO.calendar();

            assertThat(calendar).hasSize(DemoScenario.DEMO1_DAYS);
            assertThat(calendar).allMatch(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY);
            assertThat(calendar).isSorted().doesNotHaveDuplicates().doesNotContain(BASE);
            assertThat(calendar.get(calendar.size() - 1)).isEqualTo(LocalDate.of(2026, 10, 29));
        }

        @Test
        @DisplayName("월요일 · 토요일 기동이면 마지막 날은 직전 금요일")
        void weekendAndMondayBase() {
            LocalDate friday = LocalDate.of(2026, 10, 30);

            assertThat(last(DemoScenario.of(LocalDate.of(2026, 11, 2)).calendar())).isEqualTo(friday);
            assertThat(last(DemoScenario.of(LocalDate.of(2026, 10, 31)).calendar())).isEqualTo(friday);
        }

        @Test
        @DisplayName("계정별 기록 날짜는 달력의 끝에서부터 — demo1 400일, demo2 30일, demo3 1일, demo4 0일")
        void daysPerUser() {
            List<LocalDate> calendar = SCENARIO.calendar();

            assertThat(SCENARIO.plan(DemoUser.DEMO1).days()).extracting(Day::date).isEqualTo(calendar);
            assertThat(SCENARIO.plan(DemoUser.DEMO2).days()).extracting(Day::date)
                    .isEqualTo(calendar.subList(calendar.size() - DemoScenario.DEMO2_DAYS, calendar.size()));
            assertThat(SCENARIO.plan(DemoUser.DEMO3).days()).extracting(Day::date).containsExactly(last(calendar));
            assertThat(SCENARIO.plan(DemoUser.DEMO4).days()).isEmpty();
        }

        private LocalDate last(List<LocalDate> dates) {
            return dates.get(dates.size() - 1);
        }
    }

    @Test
    @DisplayName("같은 기준 날짜면 같은 결과 — 고정 시드")
    void deterministic() {
        DemoScenario again = DemoScenario.of(BASE);

        assertThat(again.prices()).isEqualTo(SCENARIO.prices());
        for (DemoUser user : DemoUser.values()) {
            assertThat(again.plan(user)).isEqualTo(SCENARIO.plan(user));
        }
    }

    @Nested
    @DisplayName("demo1 이벤트")
    class Demo1Events {

        private final UserPlan plan = SCENARIO.plan(DemoUser.DEMO1);

        @Test
        @DisplayName("전 거래일 대비 +5% 초과 상승일과 −5% 초과 하락일이 각각 있고, 그날 기록은 COMPLETE")
        void surgeAndPlunge() {
            assertThat(changeRate(DemoScenario.SURGE_TICKER, SCENARIO.surgeDate())).isGreaterThan(new BigDecimal("5"));
            assertThat(changeRate(DemoScenario.PLUNGE_TICKER, SCENARIO.plungeDate())).isLessThan(new BigDecimal("-5"));
            assertThat(status(day(SCENARIO.surgeDate()))).contains(SnapshotStatus.COMPLETE);
            assertThat(status(day(SCENARIO.plungeDate()))).contains(SnapshotStatus.COMPLETE);
        }

        @Test
        @DisplayName("추가 매수 1회 — 매수일부터 수량이 늘고, 그 전후로는 바뀌지 않는다")
        void additionalBuy() {
            String ticker = DemoScenario.BUY_TICKER;
            List<BigDecimal> distinct = plan.days().stream()
                    .filter(d -> d.account().status() == AccountStatus.SUCCESS)
                    .map(d -> quantity(d, ticker)).distinct().toList();

            assertThat(distinct).hasSize(2);
            assertThat(distinct.get(1)).isGreaterThan(distinct.get(0));
            assertThat(quantity(day(SCENARIO.buyDate()), ticker)).isEqualByComparingTo(distinct.get(1));
            assertThat(quantity(previousDay(SCENARIO.buyDate()), ticker)).isEqualByComparingTo(distinct.get(0));
        }

        @Test
        @DisplayName("전량 매도 1회 — 매도일 전날까지 보유, 매도일부터 기록 · 현재 보유 목록에 없다")
        void fullSale() {
            String ticker = DemoScenario.SALE_TICKER;

            assertThat(quantity(previousDay(SCENARIO.saleDate()), ticker)).isPositive();
            assertThat(plan.days()).filteredOn(d -> !d.date().isBefore(SCENARIO.saleDate()))
                    .allMatch(d -> quantity(d, ticker) == null);
            assertThat(plan.currentHoldings()).noneMatch(h -> h.ticker().equals(ticker));
        }

        @Test
        @DisplayName("현재 보유 종목 수량 = 마지막 날 기록 수량, 평균 매입가는 양수")
        void currentHoldingsMatchLastDay() {
            Day last = plan.days().get(plan.days().size() - 1);

            assertThat(plan.currentHoldings()).hasSameSizeAs(last.account().holdings());
            assertThat(plan.currentHoldings()).allSatisfy(h -> {
                assertThat(h.quantity()).isEqualByComparingTo(quantity(last, h.ticker()));
                assertThat(h.averagePrice()).isPositive();
            });
        }

        private Day day(LocalDate date) {
            return plan.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
        }

        private Day previousDay(LocalDate date) {
            int index = plan.days().indexOf(day(date));
            return plan.days().get(index - 1);
        }

        private BigDecimal changeRate(String ticker, LocalDate date) {
            List<PricePoint> points = SCENARIO.prices().get(ticker);
            int index = points.stream().map(PricePoint::date).toList().indexOf(date);
            BigDecimal today = points.get(index).close();
            BigDecimal before = points.get(index - 1).close();
            return today.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 4, RoundingMode.HALF_UP);
        }
    }

    @Nested
    @DisplayName("기록 상태 — 실제 SnapshotJudge 판정")
    class Statuses {

        @Test
        @DisplayName("demo1: PARTIAL 3일 · FAILED 1일, 나머지는 COMPLETE")
        void demo1() {
            UserPlan plan = SCENARIO.plan(DemoUser.DEMO1);

            assertThat(count(plan, SnapshotStatus.PARTIAL)).isEqualTo(3);
            assertThat(count(plan, SnapshotStatus.FAILED)).isEqualTo(1);
            assertThat(count(plan, SnapshotStatus.COMPLETE)).isEqualTo(DemoScenario.DEMO1_DAYS - 4);
        }

        @Test
        @DisplayName("demo2: 연동 상태 AUTH_FAILED, 최근 2일만 PARTIAL")
        void demo2() {
            UserPlan plan = SCENARIO.plan(DemoUser.DEMO2);
            List<Day> days = plan.days();

            assertThat(plan.accountStatus()).isEqualTo(ConnectedAccountStatus.AUTH_FAILED);
            assertThat(days.subList(days.size() - 2, days.size()))
                    .allMatch(d -> status(d).orElseThrow() == SnapshotStatus.PARTIAL);
            assertThat(days.subList(0, days.size() - 2))
                    .allMatch(d -> status(d).orElseThrow() == SnapshotStatus.COMPLETE);
        }

        @Test
        @DisplayName("demo3: 연동 계좌 1개, 기록 1일 COMPLETE · demo4: 자산 · 기록 없음")
        void demo3AndDemo4() {
            UserPlan demo3 = SCENARIO.plan(DemoUser.DEMO3);
            UserPlan demo4 = SCENARIO.plan(DemoUser.DEMO4);

            assertThat(demo3.accountStatus()).isEqualTo(ConnectedAccountStatus.ACTIVE);
            assertThat(status(demo3.days().get(0))).contains(SnapshotStatus.COMPLETE);
            assertThat(demo4.accountStatus()).isNull();
            assertThat(demo4.manualTypes()).isEmpty();
            assertThat(demo4.currentHoldings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("값")
    class Values {

        @Test
        @DisplayName("실패 대상은 0이 아니라 값 없음(null) — 그 외 값은 모두 양수")
        void failedTargetsAreNullNotZero() {
            for (DemoUser user : DemoUser.values()) {
                for (Day day : SCENARIO.plan(user).days()) {
                    if (day.account() != null) {
                        if (day.account().status() == AccountStatus.SUCCESS) {
                            assertThat(day.account().deposit()).isNotNull().isPositive();
                            assertThat(day.account().holdings()).allSatisfy(h -> {
                                assertThat(h.quantity()).isPositive();
                                if (h.price() != null) {
                                    assertThat(h.price()).isPositive();
                                }
                            });
                        } else {
                            assertThat(day.account().deposit()).isNull();
                            assertThat(day.account().holdings()).isEmpty();
                        }
                    }
                    for (ManualDay manual : day.manuals()) {
                        if (manual.balance() != null) {
                            assertThat(manual.balance()).isPositive();
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("시세 실패로 둔 (종목, 날짜)는 시세 목록에도 없다 — 같은 날짜 시세는 모든 계정이 공유")
        void priceFailureMatchesPriceList() {
            for (DemoUser user : DemoUser.values()) {
                for (Day day : SCENARIO.plan(user).days()) {
                    if (day.account() == null || day.account().status() != AccountStatus.SUCCESS) {
                        continue;
                    }
                    for (HoldingDay h : day.account().holdings()) {
                        Optional<BigDecimal> listed = SCENARIO.prices().get(h.ticker()).stream()
                                .filter(p -> p.date().equals(day.date())).map(PricePoint::close).findFirst();
                        if (h.price() == null) {
                            assertThat(listed).isEmpty();
                        } else {
                            assertThat(listed).contains(h.price());
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("모든 시세는 양수, 종목명이 있다")
        void pricesPositive() {
            assertThat(SCENARIO.prices()).isNotEmpty();
            SCENARIO.prices().forEach((ticker, points) -> {
                assertThat(DemoScenario.stockName(ticker)).isNotBlank();
                assertThat(points).allSatisfy(p -> assertThat(p.close()).isPositive());
            });
        }
    }

    // ---------- B5: 근거 · 지수 데모 ----------

    @Nested
    @DisplayName("B5 근거 · 지수 데모")
    class EvidenceDemo {

        private final UserPlan plan = SCENARIO.plan(DemoUser.DEMO1);

        @Test
        @DisplayName("1. 신규 이벤트(5평일 전) — 평일 · 그날 COMPLETE · 005930 보유 · 전일 대비 +6% 이상")
        void recentSurge() {
            LocalDate date = SCENARIO.recentSurgeDate();

            assertThat(date).isEqualTo(SCENARIO.calendar().get(SCENARIO.calendar().size() - 1 - 5));
            assertThat(date.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            Day day = plan.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
            assertThat(status(day)).contains(SnapshotStatus.COMPLETE);
            assertThat(quantity(day, DemoScenario.RECENT_SURGE_TICKER)).isPositive();
            assertThat(rate(SCENARIO.prices().get(DemoScenario.RECENT_SURGE_TICKER).stream()
                    .map(p -> new DemoScenario.SeriesPoint(p.date(), p.close())).toList(), date))
                    .isGreaterThanOrEqualTo(new BigDecimal("6"));
        }

        @Test
        @DisplayName("1-b. 감지 대상 이벤트일 = 200 · 45 · 5평일 전, 서로 겹치지 않는다")
        void detectionDates() {
            assertThat(SCENARIO.detectionDates()).containsExactly(
                    SCENARIO.surgeDate(), SCENARIO.plungeDate(), SCENARIO.recentSurgeDate());
        }

        @Test
        @DisplayName("3. 지수: 45평일 전(급락일)은 KOSPI · KOSDAQ가 같은 방향으로 크게(−2.5% 이하), 200 · 5평일 전과 나머지 날은 평탄(±1% 미만)")
        void indexPattern() {
            for (String code : List.of("KOSPI", "KOSDAQ")) {
                List<DemoScenario.SeriesPoint> series = SCENARIO.indexes().get(code);
                assertThat(series).extracting(DemoScenario.SeriesPoint::date).isEqualTo(SCENARIO.calendar());
                assertThat(rate(series, SCENARIO.plungeDate())).as(code + " 급락일").isLessThanOrEqualTo(new BigDecimal("-2.5"));
                for (int i = 1; i < series.size(); i++) {
                    LocalDate date = series.get(i).date();
                    if (date.equals(SCENARIO.plungeDate())) continue;
                    assertThat(rate(series, date).abs()).as(code + " " + date).isLessThan(BigDecimal.ONE);
                }
            }
        }

        @Test
        @DisplayName("3-b. 거시지표: 기준금리 · 원/달러 — 데모 기간 전체 평일, 값은 양수")
        void macroSeries() {
            for (String code : List.of("BASE_RATE", "USD_KRW")) {
                List<DemoScenario.SeriesPoint> series = SCENARIO.macro().get(code);
                assertThat(series).extracting(DemoScenario.SeriesPoint::date).isEqualTo(SCENARIO.calendar());
                assertThat(series).allSatisfy(p -> assertThat(p.value()).isPositive());
            }
        }

        @Test
        @DisplayName("공시: 5평일 · 45평일 전 이벤트 종목에만, 접수일은 이벤트일 전후 1일 안 · 접수번호 앞 8자리 = 접수일, 200평일 전은 없음")
        void disclosures() {
            List<DemoScenario.DemoDisclosure> list = SCENARIO.disclosures();

            assertThat(list).isNotEmpty();
            assertThat(list).allSatisfy(d -> {
                assertThat(d.receiptNo()).hasSize(14).startsWith(d.receiptDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                assertThat(d.type()).matches("[A-J]");
            });
            assertThat(list).filteredOn(d -> d.ticker().equals(DemoScenario.RECENT_SURGE_TICKER))
                    .isNotEmpty().allSatisfy(d -> assertThat(within(d.receiptDate(), SCENARIO.recentSurgeDate())).isTrue());
            assertThat(list).filteredOn(d -> d.ticker().equals(DemoScenario.PLUNGE_TICKER))
                    .isNotEmpty().allSatisfy(d -> assertThat(within(d.receiptDate(), SCENARIO.plungeDate())).isTrue());
            assertThat(list).noneMatch(d -> within(d.receiptDate(), SCENARIO.surgeDate()));
            assertThat(SCENARIO.corpCode(DemoScenario.RECENT_SURGE_TICKER)).hasSize(8);
            assertThat(SCENARIO.corpCode(DemoScenario.PLUNGE_TICKER)).hasSize(8);
        }

        @Test
        @DisplayName("4. 기사(5평일 전만): 15:30 이후 1건 이상 · 장중 1건 이상 · 제목에 종목명 없는 1건 · 같은 원문 URL 1건, 45 · 200평일 전 기사 없음")
        void articles() {
            LocalDate date = SCENARIO.recentSurgeDate();
            String name = DemoScenario.stockName(DemoScenario.RECENT_SURGE_TICKER);
            List<DemoScenario.DemoArticle> list = SCENARIO.articles();

            assertThat(list).allSatisfy(a -> {
                assertThat(a.query()).isEqualTo(name);
                assertThat(within(a.pubDate().toLocalDate(), date)).isTrue();
            });
            java.time.LocalTime close = java.time.LocalTime.of(15, 30);
            assertThat(list).anyMatch(a -> a.pubDate().toLocalDate().equals(date) && !a.pubDate().toLocalTime().isBefore(close));
            assertThat(list).anyMatch(a -> a.pubDate().toLocalDate().equals(date) && a.pubDate().toLocalTime().isBefore(close));
            assertThat(list).filteredOn(a -> !a.title().replaceAll("<[^>]*>", "").contains(name)).hasSize(1);
            assertThat(list.stream().map(DemoScenario.DemoArticle::originallink).distinct().count()).isEqualTo(list.size() - 1);
            assertThat(SCENARIO.expectedNewsCount()).isEqualTo(list.size() - 2); // 제목 필터 1건 · 중복 1건 제외
        }

        @Test
        @DisplayName("5. 결정성 — 같은 기준일이면 지수 · 지표 · 공시 · 기사가 같다")
        void deterministic() {
            DemoScenario again = DemoScenario.of(BASE);

            assertThat(again.indexes()).isEqualTo(SCENARIO.indexes());
            assertThat(again.macro()).isEqualTo(SCENARIO.macro());
            assertThat(again.disclosures()).isEqualTo(SCENARIO.disclosures());
            assertThat(again.articles()).isEqualTo(SCENARIO.articles());
        }

        private static boolean within(LocalDate value, LocalDate eventDate) {
            return !value.isBefore(eventDate.minusDays(1)) && !value.isAfter(eventDate.plusDays(1));
        }

        private static BigDecimal rate(List<DemoScenario.SeriesPoint> series, LocalDate date) {
            int index = series.stream().map(DemoScenario.SeriesPoint::date).toList().indexOf(date);
            BigDecimal today = series.get(index).value();
            BigDecimal before = series.get(index - 1).value();
            return today.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 4, RoundingMode.HALF_UP);
        }
    }
}
