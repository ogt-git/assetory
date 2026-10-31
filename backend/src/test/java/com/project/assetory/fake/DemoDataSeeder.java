package com.project.assetory.fake;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.DepositDetail;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.e2e.FakeKisServer;
import com.project.assetory.fake.DemoScenario.CurrentHolding;
import com.project.assetory.fake.DemoScenario.Day;
import com.project.assetory.fake.DemoScenario.DemoUser;
import com.project.assetory.fake.DemoScenario.PricePoint;
import com.project.assetory.fake.DemoScenario.UserPlan;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.service.SnapshotSaveService;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * fake 프로필 데모 데이터 — 앱이 뜬 직후 한 번 넣는다(매번 새 DB). 값은 모두 더미다.
 *
 * <ol>
 *   <li>시세(stock_prices) + 가짜 한투 서버 시세 · 종목 정보 등록</li>
 *   <li>회원 · 연동 계좌 · 자산 · 현재 보유 종목 — 실제 엔티티 생성 경로(비밀번호 인코더, 계좌번호 암호화 · 마스킹)</li>
 *   <li>가짜 CODEF 보유 조회 응답 등록 — 화면의 수동 새로고침이 마지막 기록과 같은 구성을 받는다</li>
 *   <li>날짜별 기록 — 실제 SnapshotSaveService로 날짜 오름차순 저장(직전 COMPLETE 사슬 · 시장가격 영향을 운영과 같은 계산으로)</li>
 * </ol>
 * 일일 배치(DailyAssetBatchService)는 쓰지 않는다 — 외부 호출 경로 대신 시나리오 결과를 그대로 넣는다.
 */
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    // 로컬 전용 더미 비밀번호 — 모든 데모 계정 공통 (docs/ops/fake-profile.md)
    public static final String PASSWORD = "Demo1234!";
    private static final String CODEF_ORG = "0240";
    private static final String TERMS_VERSION = "2026-10";
    // 테스트 전용 더미 계좌번호 — 마지막 자리만 계정별로 다르다
    private static final String ACCOUNT_NUMBER_PREFIX = "1234567890";
    private static final long DUMMY_VOLUME = 1_000_000L;

    private final Clock clock;
    private final FakeKisServer kisServer;
    private final FakeCodefApiClient codefApiClient;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final InstitutionRepository institutionRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AssetRepository assetRepository;
    private final DepositDetailRepository depositDetailRepository;
    private final HoldingRepository holdingRepository;
    private final StockPriceRepository stockPriceRepository;
    private final SnapshotSaveService snapshotSaveService;
    private final TransactionTemplate tx;

    public DemoDataSeeder(Clock clock, FakeKisServer kisServer, FakeCodefApiClient codefApiClient,
                          PasswordEncoder passwordEncoder, UserRepository userRepository,
                          InstitutionRepository institutionRepository, ConnectedAccountRepository connectedAccountRepository,
                          AssetRepository assetRepository, DepositDetailRepository depositDetailRepository,
                          HoldingRepository holdingRepository, StockPriceRepository stockPriceRepository,
                          SnapshotSaveService snapshotSaveService, TransactionTemplate tx) {
        this.clock = clock;
        this.kisServer = kisServer;
        this.codefApiClient = codefApiClient;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.institutionRepository = institutionRepository;
        this.connectedAccountRepository = connectedAccountRepository;
        this.assetRepository = assetRepository;
        this.depositDetailRepository = depositDetailRepository;
        this.holdingRepository = holdingRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.snapshotSaveService = snapshotSaveService;
        this.tx = tx;
    }

    /** 계정 1개의 자산 id — 기록 입력을 만들 때 쓴다 */
    private record Seeded(Long userId, Long accountAssetId, Map<AssetType, Long> manualAssetIds) {}

    @Override
    public void run(ApplicationArguments args) {
        long started = System.nanoTime();
        DemoScenario scenario = DemoScenario.of(LocalDate.now(clock));

        seedPrices(scenario);
        int snapshots = 0;
        for (DemoUser user : DemoUser.values()) {
            UserPlan plan = scenario.plan(user);
            Seeded seeded = seedUser(plan, scenario);
            for (Day day : plan.days()) {
                if (snapshotSaveService.save(seeded.userId(), day.toInput(seeded.accountAssetId(), seeded.manualAssetIds())).isPresent()) {
                    snapshots++;
                }
            }
        }

        log.info("[FakeSeed] 데모 데이터 준비 완료 — 계정 {}개, 기록 {}건, 기간 {} ~ {}, {}ms. 로그인: demo1~demo{}@assetory.test",
                DemoUser.values().length, snapshots, scenario.calendar().get(0),
                scenario.calendar().get(scenario.calendar().size() - 1), (System.nanoTime() - started) / 1_000_000,
                DemoUser.values().length);
    }

    // ---------- 시세 ----------

    private void seedPrices(DemoScenario scenario) {
        List<StockPrice> rows = new ArrayList<>();
        scenario.prices().forEach((ticker, points) -> {
            String name = DemoScenario.stockName(ticker);
            BigDecimal previous = null;
            for (PricePoint point : points) {
                BigDecimal prev = previous == null ? point.close() : previous;
                StockPrice row = StockPrice.create(ticker, point.date());
                row.update(name, point.close(), prev, changeRate(point.close(), prev), DUMMY_VOLUME);
                rows.add(row);
                previous = point.close();
            }
            // 가짜 한투 서버 — 화면의 수동 새로고침 · 소급이 같은 시세를 받는다(최신 날짜부터)
            FakeKisServer.Day[] latestFirst = new FakeKisServer.Day[points.size()];
            for (int i = 0; i < points.size(); i++) {
                PricePoint p = points.get(points.size() - 1 - i);
                latestFirst[i] = new FakeKisServer.Day(p.date(), p.close());
            }
            kisServer.prices(ticker, latestFirst);
            kisServer.stockInfo(ticker, securityType(ticker) == SecurityType.ETF ? "EF" : "ST", false);
        });
        tx.executeWithoutResult(s -> stockPriceRepository.saveAll(rows));
    }

    private static BigDecimal changeRate(BigDecimal close, BigDecimal previous) {
        return close.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 4, RoundingMode.HALF_UP);
    }

    private static SecurityType securityType(String ticker) {
        return ticker.equals("069500") ? SecurityType.ETF : SecurityType.STOCK;
    }

    // ---------- 회원 · 자산 ----------

    private Seeded seedUser(UserPlan plan, DemoScenario scenario) {
        int number = plan.user().ordinal() + 1;
        String email = "demo" + number + "@assetory.test";
        LocalDate lastDay = scenario.calendar().get(scenario.calendar().size() - 1);

        return tx.execute(s -> {
            User user = User.create(email, HashUtil.sha256Base64(email), passwordEncoder.encode(PASSWORD),
                    "데모" + number, TERMS_VERSION);
            user.markEmailVerified(lastDay.atStartOfDay());
            userRepository.save(user);

            Long accountAssetId = null;
            if (plan.accountStatus() != null) {
                Institution institution = institutionRepository.findByCodefOrgCode(CODEF_ORG).orElseThrow();
                String connectedId = "fake-cid-demo" + number;
                ConnectedAccount account = connectedAccountRepository.save(
                        ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
                account.changeStatus(plan.accountStatus());
                account.markSynced(lastSuccessDate(plan).atTime(16, 5));

                Asset asset = Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER_PREFIX + number);
                asset.updateBalance(plan.currentDeposit());
                accountAssetId = assetRepository.save(asset).getId();
                seedHoldings(asset, plan.currentHoldings(), scenario);
                registerCodef(connectedId, plan, scenario);
            }

            Map<AssetType, Long> manualIds = new EnumMap<>(AssetType.class);
            for (AssetType type : plan.manualTypes()) {
                BigDecimal balance = plan.currentManualBalances().get(type);
                Asset manual = assetRepository.save(Asset.manual(user, type, type == AssetType.DEPOSIT ? "정기예금" : "생활비 통장", balance));
                if (type == AssetType.DEPOSIT) {
                    depositDetailRepository.save(DepositDetail.create(manual, new BigDecimal("3.500"),
                            lastDay.minusMonths(6), lastDay.plusMonths(6), balance, 12, false));
                }
                manualIds.put(type, manual.getId());
            }
            return new Seeded(user.getId(), accountAssetId, manualIds);
        });
    }

    private void seedHoldings(Asset asset, List<CurrentHolding> holdings, DemoScenario scenario) {
        for (CurrentHolding h : holdings) {
            BigDecimal price = latestClose(scenario, h.ticker());
            BigDecimal eval = price.multiply(h.quantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = h.averagePrice().multiply(h.quantity());
            BigDecimal profitLoss = eval.subtract(principal).setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = profitLoss.multiply(BigDecimal.valueOf(100)).divide(principal, 4, RoundingMode.HALF_UP);
            Holding holding = Holding.create(asset, h.ticker());
            holding.update(h.name(), securityType(h.ticker()), h.quantity(), h.averagePrice(), price, eval, profitLoss, rate);
            holdingRepository.save(holding);
        }
    }

    // 수동 새로고침 응답 — 마지막 성공 수집과 같은 구성. 인증 만료 계정은 인증 실패 응답
    private void registerCodef(String connectedId, UserPlan plan, DemoScenario scenario) {
        if (plan.accountStatus() != ConnectedAccountStatus.ACTIVE) {
            codefApiClient.authFailure(connectedId);
            return;
        }
        List<FakeCodefApiClient.Item> items = plan.currentHoldings().stream()
                .map(h -> new FakeCodefApiClient.Item(h.ticker(), h.name(), h.quantity(),
                        latestClose(scenario, h.ticker()), h.averagePrice()))
                .toList();
        codefApiClient.respond(connectedId, FakeCodefApiClient.portfolio(plan.currentDeposit(), items));
    }

    private static BigDecimal latestClose(DemoScenario scenario, String ticker) {
        List<PricePoint> points = scenario.prices().get(ticker);
        return points.get(points.size() - 1).close();
    }

    // 마지막으로 예수금까지 받은 날 — connected_accounts.last_synced_at
    private static LocalDate lastSuccessDate(UserPlan plan) {
        LocalDate last = null;
        for (Day day : plan.days()) {
            if (day.account() != null && day.account().deposit() != null) {
                last = day.date();
            }
        }
        return last;
    }
}
