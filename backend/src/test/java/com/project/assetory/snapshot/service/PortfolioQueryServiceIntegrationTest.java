package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.service.PortfolioResult.AssetClassItem;
import com.project.assetory.snapshot.service.PortfolioResult.HoldingItem;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04) — 최근 COMPLETE 기록(한투 종가 × 수량) 기준. holdings의 CODEF 평가액 · 손익은 쓰지 않는다
class PortfolioQueryServiceIntegrationTest extends IntegrationTestSupport {

    // 테스트 전용 더미 값 — 다른 테스트 시세와 겹치지 않는 날짜 · 종목코드
    private static final LocalDate DAY1 = LocalDate.of(2027, 4, 1);
    private static final LocalDate DAY2 = LocalDate.of(2027, 4, 2);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호
    private static final String BIG = "T7A001";
    private static final String SMALL = "T7B002";
    private static final String SOLD = "T7C003";   // 기록 뒤 전량 매도 — 현재 holdings에 없음

    @Autowired PortfolioQueryService portfolioService;
    @Autowired AssetChangeQueryService changeService;
    @Autowired SnapshotSaveService saveService;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    private record Member(Long userId, Long accountA, Long accountB, Long cashId) {}

    private Member member;

    @BeforeEach
    void setUp() throws Exception {
        member = newMember();
        price(BIG, "큰종목", DAY2);
    }

    private Member newMember() throws Exception {
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
        Long[] ids = new Long[3];
        transactionTemplate.executeWithoutResult(status -> {
            // 연동은 사용자 · 기관 · 업무 구분당 1개 — 두 계좌는 다른 증권사로 만든다
            ids[0] = securities(user, institutionRepository.findByCodefOrgCode("0240").orElseThrow()); // 삼성증권
            ids[1] = securities(user, institutionRepository.findByCodefOrgCode("0218").orElseThrow()); // KB증권
            ids[2] = assetRepository.save(Asset.manual(user, AssetType.CASH, "생활비", new BigDecimal("500000"))).getId();
        });
        return new Member(user.getId(), ids[0], ids[1], ids[2]);
    }

    private Long securities(User user, Institution institution) {
        ConnectedAccount account = connectedAccountRepository.save(
                ConnectedAccount.create(user, institution, "cid-portfolio-" + UUID.randomUUID(), BusinessType.ST));
        return assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER)).getId();
    }

    // 현재 holdings(CODEF) — 평가액 · 손익은 일부러 엉뚱한 값(999)으로 둔다
    private void holding(Long assetId, String ticker, String name, String avgBuyPrice) {
        transactionTemplate.executeWithoutResult(status -> {
            Holding h = Holding.create(assetRepository.findById(assetId).orElseThrow(), ticker);
            h.update(name, SecurityType.STOCK, d("1"), avgBuyPrice == null ? null : d(avgBuyPrice),
                    d("999"), d("999"), d("999"), d("99.9999"));
            holdingRepository.save(h);
        });
    }

    private void price(String ticker, String name, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> {
            if (stockPriceRepository.findByTickerAndTradeDate(ticker, date).isPresent()) {
                return;
            }
            StockPrice p = StockPrice.create(ticker, date);
            p.update(name, d("1000"), d("1000"), BigDecimal.ZERO, 0L);
            stockPriceRepository.save(p);
        });
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private void record(Member m, LocalDate date, List<Account> accounts, String cash) {
        saveService.save(m.userId(), new SnapshotInput(date, date.atTime(16, 5), accounts,
                List.of(new Manual(m.cashId(), AssetType.CASH, d(cash)))));
    }

    // DAY1 기준 기록, DAY2 최근 COMPLETE:
    //   A: 예수금 100,000 · BIG 10 × 70,000 = 700,000 · SOLD 2 × 5,000 = 10,000
    //   B: 예수금 50,000 · BIG 5 × 70,000 = 350,000 · SMALL 3 × 1,000 = 3,000
    //   현금 500,000 → 총자산 1,713,000 (증권 1,063,000, 현금 650,000)
    private void standardRecords(Member m) {
        record(m, DAY1, List.of(
                Account.success(m.accountA(), d("100000"), List.of(HoldingPrice.collected(BIG, d("10"), d("69000")))),
                Account.success(m.accountB(), d("50000"), List.of())), "500000");
        record(m, DAY2, List.of(
                Account.success(m.accountA(), d("100000"), List.of(
                        HoldingPrice.collected(BIG, d("10"), d("70000")),
                        HoldingPrice.collected(SOLD, d("2"), d("5000")))),
                Account.success(m.accountB(), d("50000"), List.of(
                        HoldingPrice.collected(BIG, d("5"), d("70000")),
                        HoldingPrice.collected(SMALL, d("3"), d("1000"))))), "500000");
        holding(m.accountA(), BIG, "큰종목", "65000");
        holding(m.accountB(), BIG, "큰종목", "72000");
        holding(m.accountB(), SMALL, "작은종목", null);   // 평균 매입가 없음
    }

    private static HoldingItem row(PortfolioResult result, Long assetId, String ticker) {
        return result.holdings().stream().filter(h -> h.assetId().equals(assetId) && h.ticker().equals(ticker))
                .findFirst().orElseThrow();
    }

    private static AssetClassItem assetClass(PortfolioResult result, AssetClass assetClass) {
        return result.assetClasses().stream().filter(c -> c.assetClass() == assetClass).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("1. 평가금액 · 단가 = 그날 detail 저장값(한투 종가 × 수량) — holdings의 CODEF 평가액(999)은 쓰지 않는다")
    void evalFromDetail() {
        standardRecords(member);

        HoldingItem big = row(portfolioService.getPortfolio(member.userId()), member.accountA(), BIG);

        assertThat(big.evalAmount()).isEqualByComparingTo("700000");
        assertThat(big.price()).isEqualByComparingTo("70000");
        assertThat(big.quantity()).isEqualByComparingTo("10");
        assertThat(big.principal()).isEqualByComparingTo("650000");       // 10 × 평균 매입가 65,000
        assertThat(big.profitLoss()).isEqualByComparingTo("50000");
        assertThat(big.profitLossRate()).isEqualByComparingTo("7.6923");
        assertThat(big.ratio()).isEqualByComparingTo("40.86");            // 700,000 / 1,713,000
        assertThat(big.dayChangeAmount()).isEqualByComparingTo("10000");  // detail 저장값 (10 × 1,000)
        assertThat(big.stockName()).isEqualTo("큰종목");
        assertThat(big.institutionName()).isEqualTo("삼성증권");
    }

    @Test
    @DisplayName("2. 총자산 · 자산군 금액 = 같은 데이터의 /changes 결과 — 비중 분모는 그날 총자산, 합계 보정 없음")
    void totalsMatchChanges() {
        standardRecords(member);

        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.asOfDate()).isEqualTo(DAY2);
        assertThat(result.totalAsset()).isEqualByComparingTo(changeService.getChanges(member.userId()).totalAsset());
        assertThat(result.totalAsset()).isEqualByComparingTo("1713000");
        assertThat(result.assetClasses()).extracting(AssetClassItem::assetClass)
                .containsExactly(AssetClass.SECURITIES, AssetClass.DEPOSIT, AssetClass.CASH);
        assertThat(assetClass(result, AssetClass.SECURITIES).amount()).isEqualByComparingTo("1063000");
        assertThat(assetClass(result, AssetClass.SECURITIES).ratio()).isEqualByComparingTo("62.05");
        assertThat(assetClass(result, AssetClass.DEPOSIT).amount()).isEqualByComparingTo("0");
        assertThat(assetClass(result, AssetClass.DEPOSIT).ratio()).isEqualByComparingTo("0");
        assertThat(assetClass(result, AssetClass.CASH).amount()).isEqualByComparingTo("650000");
        assertThat(assetClass(result, AssetClass.CASH).ratio()).isEqualByComparingTo("37.95");
    }

    @Test
    @DisplayName("3. 기록 뒤 전량 매도된 종목(현재 holdings에 없음) · 평균 매입가 없는 종목 → 원금 · 손익 · 손익률 NULL, 평가금액은 표시")
    void soldOutAndNoAvgPrice_null() {
        standardRecords(member);

        PortfolioResult result = portfolioService.getPortfolio(member.userId());
        HoldingItem sold = row(result, member.accountA(), SOLD);
        HoldingItem noAvg = row(result, member.accountB(), SMALL);

        assertThat(sold.evalAmount()).isEqualByComparingTo("10000");
        assertThat(sold.principal()).isNull();
        assertThat(sold.profitLoss()).isNull();
        assertThat(sold.profitLossRate()).isNull();
        assertThat(sold.stockName()).isEqualTo(SOLD);          // 시세 · holdings 모두 없음 → ticker
        assertThat(noAvg.principal()).isNull();
        assertThat(noAvg.profitLossRate()).isNull();
        assertThat(noAvg.stockName()).isEqualTo("작은종목");    // 시세 없음 → 현재 holdings 이름
        assertThat(noAvg.dayChangeAmount()).isNull();          // 새로 산 종목 — 기준일 행 없음
    }

    @Test
    @DisplayName("4. 같은 ticker를 두 계좌에 보유 → 행 2개, 각자 자기 계좌 평균 매입가로 원금 계산")
    void sameTickerTwoAccounts() {
        standardRecords(member);

        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.holdings()).filteredOn(h -> h.ticker().equals(BIG)).hasSize(2);
        assertThat(row(result, member.accountA(), BIG).principal()).isEqualByComparingTo("650000"); // 10 × 65,000
        assertThat(row(result, member.accountB(), BIG).principal()).isEqualByComparingTo("360000"); // 5 × 72,000
        assertThat(row(result, member.accountB(), BIG).profitLoss()).isEqualByComparingTo("-10000");
    }

    @Test
    @DisplayName("5. 최근 기록이 PARTIAL → 기준은 최근 COMPLETE, latestRecord = PARTIAL")
    void latestPartial() {
        standardRecords(member);
        record(member, DAY2.plusDays(1), List.of(
                Account.of(member.accountA(), AccountStatus.FAILED),
                Account.success(member.accountB(), d("1"), List.of())), "500000");

        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.latestRecord().date()).isEqualTo(DAY2.plusDays(1));
        assertThat(result.latestRecord().status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(result.asOfDate()).isEqualTo(DAY2);
        assertThat(result.totalAsset()).isEqualByComparingTo("1713000");
    }

    @Test
    @DisplayName("6. 기록 0건 → 빈 결과 (asOfDate · totalAsset · latestRecord null)")
    void noRecords() {
        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.asOfDate()).isNull();
        assertThat(result.totalAsset()).isNull();
        assertThat(result.latestRecord()).isNull();
        assertThat(result.assetClasses()).isEmpty();
        assertThat(result.holdings()).isEmpty();
    }

    @Test
    @DisplayName("7. 다른 사용자의 같은 ticker · 같은 날짜 기록 · holdings가 섞이지 않는다")
    void otherUserNotMixed() throws Exception {
        Member other = newMember();
        record(other, DAY2, List.of(
                Account.success(other.accountA(), d("9"), List.of(HoldingPrice.collected(BIG, d("99"), d("70000")))),
                Account.success(other.accountB(), d("9"), List.of())), "9");
        holding(other.accountA(), BIG, "큰종목", "1");
        standardRecords(member);

        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.holdings()).extracting(HoldingItem::assetId)
                .containsOnly(member.accountA(), member.accountB());
        assertThat(result.totalAsset()).isEqualByComparingTo("1713000");
        assertThat(row(result, member.accountA(), BIG).principal()).isEqualByComparingTo("650000");
    }

    @Test
    @DisplayName("8. 예수금 · 수기 자산(ticker NULL 행)은 holdings[]에 없고 자산군 금액에는 포함된다")
    void assetLevelRowsNotInHoldings() {
        standardRecords(member);

        PortfolioResult result = portfolioService.getPortfolio(member.userId());

        assertThat(result.holdings()).allSatisfy(h -> assertThat(h.ticker()).isNotNull());
        assertThat(result.holdings()).extracting(HoldingItem::assetId).doesNotContain(member.cashId());
        assertThat(assetClass(result, AssetClass.CASH).amount()).isEqualByComparingTo("650000"); // 예수금 150,000 + 현금 500,000
    }

    @Test
    @DisplayName("9. 정렬 — 평가금액 내림차순, 같으면 ticker 오름차순")
    void ordering() {
        standardRecords(member);

        List<HoldingItem> holdings = portfolioService.getPortfolio(member.userId()).holdings();

        assertThat(holdings).extracting(HoldingItem::ticker).containsExactly(BIG, BIG, SOLD, SMALL);
        assertThat(holdings).extracting(HoldingItem::evalAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("700000"), d("350000"), d("10000"), d("3000"));
    }

    @Test
    @DisplayName("10. 요청당 쿼리 수 ≤ 5 (자산 · holdings N+1 없음)")
    void queryCount() {
        standardRecords(member);
        record(member, DAY2.plusDays(1), List.of(
                Account.of(member.accountA(), AccountStatus.FAILED),
                Account.success(member.accountB(), d("1"), List.of())), "500000");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            portfolioService.getPortfolio(member.userId());
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(5);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
