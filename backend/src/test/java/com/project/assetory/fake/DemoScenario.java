package com.project.assetory.fake;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * fake 프로필 데모 시나리오 — 로컬 화면 확인 전용(테스트 소스, 운영 jar에 포함되지 않음). 스프링 · DB 없는 순수 계산.
 * 기동일 직전 평일부터 거꾸로 평일(= 거래일, 공휴일 무시)을 만들고, 고정 시드로 가짜 시세를 만든다.
 * 종목코드 · 이름만 실제 값이고 가격은 실제와 무관하다. 수량 · 금액은 모두 더미다.
 *
 * <pre>
 * demo1 기본       : 증권(예수금 + 005930 · 000660 · 069500 · 035720) + 정기예금 + 현금, 400 평일
 *                    급등 · 급락 각 1일, 추가 매수 1회 · 전량 매도 1회, PARTIAL 3일(시세 실패 2 · 계좌 실패 1) · FAILED 1일
 * demo2 최근 누락  : 증권(AUTH_FAILED) + 현금, 30 평일, 최근 2일은 인증 만료로 증권 미수집 → PARTIAL
 * demo3 연동 직후  : 증권 1계좌, 기록 1일
 * demo4 빈 계정    : 자산 · 기록 없음
 * </pre>
 *
 * 이벤트 날짜는 마지막 날에서 몇 평일 전인지로 정한다 — 기동일이 바뀌어도 모양이 같다.
 */
public final class DemoScenario {

    public static final int DEMO1_DAYS = 400;
    public static final int DEMO2_DAYS = 30;

    public static final String SURGE_TICKER = "005930";
    public static final String PLUNGE_TICKER = "035720";
    public static final String BUY_TICKER = "005930";
    public static final String SALE_TICKER = "000660";

    private static final long SEED = 20261030L;

    // 마지막 날 기준 몇 평일 전인지
    private static final int SURGE_OFFSET = 200;
    private static final int PLUNGE_OFFSET = 45;
    private static final int BUY_OFFSET = 120;
    private static final int SALE_OFFSET = 60;
    private static final Map<Integer, String> PRICE_FAIL_OFFSETS = Map.of(300, "069500", 150, "035720");
    private static final int ACCOUNT_FAIL_OFFSET = 20;
    private static final int ALL_FAIL_OFFSET = 90;

    private static final BigDecimal SURGE_RATE = new BigDecimal("0.07");
    private static final BigDecimal PLUNGE_RATE = new BigDecimal("-0.08");
    // 평소 하루 변동 상한 — 급등락(±5%)은 위 이벤트 날에만 생긴다
    private static final double NORMAL_LIMIT = 0.04;
    private static final double NORMAL_SIGMA = 0.012;
    // 평균 매입가 = 첫 날 종가 × 0.9 (수익 구간으로 보이게)
    private static final BigDecimal AVERAGE_PRICE_RATIO = new BigDecimal("0.9");

    private static final Map<String, Stock> STOCKS = stocks(
            new Stock("005930", "삼성전자", "70000"),
            new Stock("000660", "SK하이닉스", "180000"),
            new Stock("069500", "KODEX 200", "35000"),
            new Stock("035720", "카카오", "45000"));

    public enum DemoUser { DEMO1, DEMO2, DEMO3, DEMO4 }

    public record Stock(String ticker, String name, String firstClose) {}

    public record PricePoint(LocalDate date, BigDecimal close) {}

    /** 그날 수집한 종목 1개 — price null = 시세 수집 실패(0이 아님) */
    public record HoldingDay(String ticker, BigDecimal quantity, BigDecimal price) {}

    /** 그날 증권 계좌 수집 결과 — SUCCESS가 아니면 예수금 null · 종목 없음 */
    public record AccountDay(AccountStatus status, BigDecimal deposit, List<HoldingDay> holdings) {}

    /** 그날 수기 자산 1개 — balance null = 실패(FAILED 날 표시용) */
    public record ManualDay(AssetType type, BigDecimal balance) {}

    /** 하루 수집 결과 — account null = 연동 계좌 없음 */
    public record Day(LocalDate date, AccountDay account, List<ManualDay> manuals) {

        /** 실제 판정 · 저장 입력으로 바꾼다. id는 시드가 만든 assets 행 */
        public SnapshotInput toInput(Long accountAssetId, Map<AssetType, Long> manualAssetIds) {
            List<Account> accounts = account == null ? List.of() : List.of(toAccount(accountAssetId));
            List<Manual> manualInputs = manuals.stream()
                    .map(m -> new Manual(manualAssetIds.get(m.type()), m.type(), m.balance()))
                    .toList();
            return new SnapshotInput(date, date.atTime(16, 5), accounts, manualInputs);
        }

        private Account toAccount(Long assetId) {
            if (account.status() != AccountStatus.SUCCESS) {
                return Account.of(assetId, account.status());
            }
            List<HoldingPrice> holdings = account.holdings().stream()
                    .map(h -> h.price() == null
                            ? HoldingPrice.failed(h.ticker(), h.quantity())
                            : HoldingPrice.collected(h.ticker(), h.quantity(), h.price()))
                    .toList();
            return Account.success(assetId, account.deposit(), holdings);
        }
    }

    /** 현재 보유 종목(holdings 테이블) — 마지막 성공 수집 수량 + 평균 매입가 */
    public record CurrentHolding(String ticker, String name, BigDecimal quantity, BigDecimal averagePrice) {}

    /**
     * 계정 1개의 계획.
     *
     * @param accountStatus 연동 계좌 상태 — 연동이 없으면 null
     * @param currentDeposit 현재 예수금(마지막 성공 수집 값) — 연동이 없으면 null
     */
    public record UserPlan(DemoUser user, ConnectedAccountStatus accountStatus, List<AssetType> manualTypes,
                           List<Day> days, List<CurrentHolding> currentHoldings, BigDecimal currentDeposit,
                           Map<AssetType, BigDecimal> currentManualBalances) {}

    private final List<LocalDate> calendar;
    private final Map<String, List<PricePoint>> prices;
    private final Map<String, Map<LocalDate, BigDecimal>> closes;
    private final Map<DemoUser, UserPlan> plans;

    private DemoScenario(LocalDate baseDate) {
        this.calendar = calendar(baseDate);
        this.closes = closes(calendar);
        this.prices = pricePoints(closes);
        Map<DemoUser, UserPlan> built = new LinkedHashMap<>();
        built.put(DemoUser.DEMO1, demo1());
        built.put(DemoUser.DEMO2, demo2());
        built.put(DemoUser.DEMO3, demo3());
        built.put(DemoUser.DEMO4, new UserPlan(DemoUser.DEMO4, null, List.of(), List.of(), List.of(), null, Map.of()));
        this.plans = Collections.unmodifiableMap(built);
    }

    public static DemoScenario of(LocalDate baseDate) {
        return new DemoScenario(baseDate);
    }

    public static String stockName(String ticker) {
        return STOCKS.get(ticker).name();
    }

    /** demo1 전체 기간 평일(오래된 순) — 다른 계정은 이 끝부분을 쓴다 */
    public List<LocalDate> calendar() {
        return calendar;
    }

    /** 종목별 시세(오래된 순) — stock_prices 시드용. 시세 실패로 둔 (종목, 날짜)는 빠져 있다 */
    public Map<String, List<PricePoint>> prices() {
        return prices;
    }

    public UserPlan plan(DemoUser user) {
        return plans.get(user);
    }

    public LocalDate surgeDate() {
        return dateAt(SURGE_OFFSET);
    }

    public LocalDate plungeDate() {
        return dateAt(PLUNGE_OFFSET);
    }

    public LocalDate buyDate() {
        return dateAt(BUY_OFFSET);
    }

    public LocalDate saleDate() {
        return dateAt(SALE_OFFSET);
    }

    // ---------- 계정 ----------

    private UserPlan demo1() {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        quantities.put("005930", new BigDecimal("20"));
        quantities.put("000660", new BigDecimal("5"));
        quantities.put("069500", new BigDecimal("30"));
        quantities.put("035720", new BigDecimal("15"));
        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        quantities.keySet().forEach(t -> averages.put(t, initialAverage(t)));

        BigDecimal deposit = new BigDecimal("1500000");
        BigDecimal cash = new BigDecimal("3000000");
        BigDecimal fixedDeposit = new BigDecimal("10000000");
        List<Day> days = new ArrayList<>();

        for (int i = 0; i < calendar.size(); i++) {
            LocalDate date = calendar.get(i);
            int offset = calendar.size() - 1 - i;

            if (offset == BUY_OFFSET) {
                BigDecimal added = new BigDecimal("10");
                BigDecimal price = close(BUY_TICKER, date);
                BigDecimal before = quantities.get(BUY_TICKER);
                BigDecimal after = before.add(added);
                averages.put(BUY_TICKER, averages.get(BUY_TICKER).multiply(before).add(price.multiply(added))
                        .divide(after, 2, RoundingMode.HALF_UP));
                quantities.put(BUY_TICKER, after);
                deposit = deposit.subtract(price.multiply(added));
            }
            if (offset == SALE_OFFSET) {
                deposit = deposit.add(close(SALE_TICKER, date).multiply(quantities.remove(SALE_TICKER)));
                averages.remove(SALE_TICKER);
            }
            // 월급 입금처럼 보이는 기타 자산 변화 — 달의 첫 평일마다 현금 증가
            if (i > 0 && date.getMonthValue() != calendar.get(i - 1).getMonthValue()) {
                cash = cash.add(new BigDecimal("500000"));
            }

            AccountDay account;
            List<ManualDay> manuals;
            if (offset == ALL_FAIL_OFFSET) {
                account = failedAccount(AccountStatus.FAILED);
                manuals = List.of(new ManualDay(AssetType.DEPOSIT, null), new ManualDay(AssetType.CASH, null));
            } else {
                account = offset == ACCOUNT_FAIL_OFFSET
                        ? failedAccount(AccountStatus.FAILED)
                        : collected(date, deposit, quantities);
                manuals = List.of(new ManualDay(AssetType.DEPOSIT, fixedDeposit), new ManualDay(AssetType.CASH, cash));
            }
            days.add(new Day(date, account, manuals));
        }

        return new UserPlan(DemoUser.DEMO1, ConnectedAccountStatus.ACTIVE, List.of(AssetType.DEPOSIT, AssetType.CASH),
                List.copyOf(days), currentHoldings(quantities, averages), deposit,
                Map.of(AssetType.DEPOSIT, fixedDeposit, AssetType.CASH, cash));
    }

    private UserPlan demo2() {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        quantities.put("005930", new BigDecimal("3"));
        quantities.put("069500", new BigDecimal("10"));
        BigDecimal deposit = new BigDecimal("200000");
        BigDecimal cash = new BigDecimal("1000000");
        List<Day> days = new ArrayList<>();

        List<LocalDate> dates = tail(DEMO2_DAYS);
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            // 최근 2일은 인증 만료 — 분모에는 들어가지만 수집하지 않는다(EXCLUDED_AUTH)
            AccountDay account = i >= dates.size() - 2
                    ? failedAccount(AccountStatus.EXCLUDED_AUTH)
                    : collected(date, deposit, quantities);
            days.add(new Day(date, account, List.of(new ManualDay(AssetType.CASH, cash))));
        }

        return new UserPlan(DemoUser.DEMO2, ConnectedAccountStatus.AUTH_FAILED, List.of(AssetType.CASH),
                List.copyOf(days), currentHoldings(quantities, initialAverages(quantities)), deposit,
                Map.of(AssetType.CASH, cash));
    }

    private UserPlan demo3() {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        quantities.put("035720", new BigDecimal("10"));
        BigDecimal deposit = new BigDecimal("100000");
        LocalDate date = tail(1).get(0);

        return new UserPlan(DemoUser.DEMO3, ConnectedAccountStatus.ACTIVE, List.of(),
                List.of(new Day(date, collected(date, deposit, quantities), List.of())),
                currentHoldings(quantities, initialAverages(quantities)), deposit, Map.of());
    }

    // ---------- 계산 도우미 ----------

    private AccountDay collected(LocalDate date, BigDecimal deposit, Map<String, BigDecimal> quantities) {
        List<HoldingDay> holdings = quantities.entrySet().stream()
                .map(e -> new HoldingDay(e.getKey(), e.getValue(), closes.get(e.getKey()).get(date)))
                .toList();
        return new AccountDay(AccountStatus.SUCCESS, deposit, holdings);
    }

    private static AccountDay failedAccount(AccountStatus status) {
        return new AccountDay(status, null, List.of());
    }

    private List<CurrentHolding> currentHoldings(Map<String, BigDecimal> quantities, Map<String, BigDecimal> averages) {
        return quantities.entrySet().stream()
                .map(e -> new CurrentHolding(e.getKey(), stockName(e.getKey()), e.getValue(), averages.get(e.getKey())))
                .toList();
    }

    private Map<String, BigDecimal> initialAverages(Map<String, BigDecimal> quantities) {
        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        quantities.keySet().forEach(t -> averages.put(t, initialAverage(t)));
        return averages;
    }

    private BigDecimal initialAverage(String ticker) {
        return close(ticker, calendar.get(0)).multiply(AVERAGE_PRICE_RATIO).setScale(2, RoundingMode.HALF_UP);
    }

    // 시세 실패 날에도 거래 계산(매수 · 매도)에 쓸 수 있는 원래 종가 — 매수 · 매도일은 시세 실패 날과 겹치지 않는다
    private BigDecimal close(String ticker, LocalDate date) {
        return closes.get(ticker).get(date);
    }

    private LocalDate dateAt(int offset) {
        return calendar.get(calendar.size() - 1 - offset);
    }

    private List<LocalDate> tail(int size) {
        return calendar.subList(calendar.size() - size, calendar.size());
    }

    private static List<LocalDate> calendar(LocalDate baseDate) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = baseDate.minusDays(1);
        while (dates.size() < DEMO1_DAYS) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                dates.add(date);
            }
            date = date.minusDays(1);
        }
        Collections.reverse(dates);
        return List.copyOf(dates);
    }

    /**
     * 종목별 종가(원 단위 정수). 시세 실패로 둔 (종목, 날짜)는 null — 시세 목록 · 수집 결과 양쪽에서 빠진다.
     * 랜덤 걷기는 실패 날과 관계없이 이어진다(다음 날 가격이 바뀌지 않게).
     */
    private static Map<String, Map<LocalDate, BigDecimal>> closes(List<LocalDate> calendar) {
        int last = calendar.size() - 1;
        Map<String, Map<LocalDate, BigDecimal>> result = new LinkedHashMap<>();
        for (Stock stock : STOCKS.values()) {
            Random random = new Random(SEED ^ stock.ticker().hashCode());
            Map<LocalDate, BigDecimal> series = new LinkedHashMap<>();
            BigDecimal close = new BigDecimal(stock.firstClose());
            for (int i = 0; i <= last; i++) {
                int offset = last - i;
                if (i > 0) {
                    close = close.multiply(BigDecimal.ONE.add(rate(stock.ticker(), offset, random)))
                            .setScale(0, RoundingMode.HALF_UP);
                }
                boolean priceFailed = stock.ticker().equals(PRICE_FAIL_OFFSETS.get(offset));
                series.put(calendar.get(i), priceFailed ? null : close);
            }
            result.put(stock.ticker(), Collections.unmodifiableMap(series));
        }
        return Collections.unmodifiableMap(result);
    }

    private static BigDecimal rate(String ticker, int offset, Random random) {
        // 랜덤 호출 수를 날짜마다 같게 유지 — 이벤트 날도 한 번 뽑고 버린다
        double normal = Math.max(-NORMAL_LIMIT, Math.min(NORMAL_LIMIT, random.nextGaussian() * NORMAL_SIGMA));
        if (offset == SURGE_OFFSET && ticker.equals(SURGE_TICKER)) {
            return SURGE_RATE;
        }
        if (offset == PLUNGE_OFFSET && ticker.equals(PLUNGE_TICKER)) {
            return PLUNGE_RATE;
        }
        return BigDecimal.valueOf(normal).setScale(6, RoundingMode.HALF_UP);
    }

    private static Map<String, List<PricePoint>> pricePoints(Map<String, Map<LocalDate, BigDecimal>> closes) {
        Map<String, List<PricePoint>> result = new LinkedHashMap<>();
        closes.forEach((ticker, series) -> result.put(ticker, series.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> new PricePoint(e.getKey(), e.getValue()))
                .toList()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Stock> stocks(Stock... stocks) {
        Map<String, Stock> map = new LinkedHashMap<>();
        for (Stock stock : stocks) {
            map.put(stock.ticker(), stock);
        }
        return Collections.unmodifiableMap(map);
    }

    // 이벤트 날짜끼리 겹치지 않게 — 겹치면 시나리오가 의도와 달라진다
    static {
        Set<Integer> offsets = new HashSet<>(PRICE_FAIL_OFFSETS.keySet());
        for (int offset : List.of(SURGE_OFFSET, PLUNGE_OFFSET, BUY_OFFSET, SALE_OFFSET, ACCOUNT_FAIL_OFFSET, ALL_FAIL_OFFSET)) {
            if (!offsets.add(offset)) {
                throw new IllegalStateException("데모 이벤트 날짜가 겹친다: " + offset);
            }
        }
    }
}
