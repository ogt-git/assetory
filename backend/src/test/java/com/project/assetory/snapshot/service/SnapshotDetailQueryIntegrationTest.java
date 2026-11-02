package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
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
import com.project.assetory.snapshot.exception.SnapshotNotFoundException;
import com.project.assetory.snapshot.service.SnapshotDetailResult.AssetItem;
import com.project.assetory.snapshot.service.SnapshotDetailResult.HoldingItem;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 자산 기록 날짜별 상세 (FR-AS-06, FR-AN-01 종목 단위, 01 §14 #19) — 저장된 asset_snapshots · asset_detail_snapshots만 읽는다
class SnapshotDetailQueryIntegrationTest extends IntegrationTestSupport {

    // 테스트 전용 더미 값 — 다른 테스트 시세와 겹치지 않는 날짜 · 종목코드
    private static final LocalDate DAY1 = LocalDate.of(2027, 3, 2);
    private static final LocalDate DAY2 = LocalDate.of(2027, 3, 3);
    private static final LocalDate DAY3 = LocalDate.of(2027, 3, 4);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호
    private static final String TRADED = "T6A001";   // 그날 시세 있음
    private static final String HALTED = "T6B002";   // 그날 시세 없음 — 이전 날짜 이름
    private static final String UNKNOWN = "T6C003";  // 시세 전혀 없음 — ticker 그대로

    @Autowired SnapshotRecordQueryService recordService;
    @Autowired SnapshotSaveService saveService;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Long userId;
    private Long securitiesId;
    private Long cashId;
    private Long depositId;

    @BeforeEach
    void setUp() throws Exception {
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
        userId = user.getId();
        transactionTemplate.executeWithoutResult(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-detail-" + UUID.randomUUID(), BusinessType.ST));
            securitiesId = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER)).getId();
            cashId = assetRepository.save(Asset.manual(user, AssetType.CASH, "생활비 통장", new BigDecimal("500000"))).getId();
            depositId = assetRepository.save(Asset.manual(user, AssetType.DEPOSIT, "정기예금", new BigDecimal("2000000"))).getId();
        });
        price(TRADED, "테스트종목A", DAY2);
        price(HALTED, "정지종목B", DAY1.minusDays(10));
    }

    private void price(String ticker, String name, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> {
            if (stockPriceRepository.findByTickerAndTradeDate(ticker, date).isPresent()) {
                return;
            }
            StockPrice p = StockPrice.create(ticker, date);
            p.update(name, new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO, 0L);
            stockPriceRepository.save(p);
        });
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // 정기 배치와 같은 경로(SnapshotSaveService)로 기록을 만든다
    private void record(LocalDate date, Account account, String cash, String deposit) {
        saveService.save(userId, new SnapshotInput(date, date.atTime(16, 5), List.of(account), List.of(
                new Manual(cashId, AssetType.CASH, d(cash)), new Manual(depositId, AssetType.DEPOSIT, d(deposit)))));
    }

    private Account account(String depositReceived, HoldingPrice... holdings) {
        return Account.success(securitiesId, d(depositReceived), List.of(holdings));
    }

    // DAY1: 예수금 100,000 · TRADED 10 × 1,000 · HALTED 5 × 2,000, 현금 500,000, 예금 2,000,000 → 2,620,000
    // DAY2: 예수금 120,000 · TRADED 10 × 1,100 · HALTED 5 × 2,000 · UNKNOWN 1 × 3,000, 현금 510,000, 예금 2,000,000 → 2,654,000
    private void twoCompleteDays() {
        record(DAY1, account("100000",
                HoldingPrice.collected(TRADED, d("10"), d("1000")),
                HoldingPrice.halted(HALTED, d("5"), d("2000"))), "500000", "2000000");
        record(DAY2, account("120000",
                HoldingPrice.collected(TRADED, d("10"), d("1100")),
                HoldingPrice.halted(HALTED, d("5"), d("2000")),
                HoldingPrice.collected(UNKNOWN, d("1"), d("3000"))), "510000", "2000000");
    }

    private static AssetItem asset(SnapshotDetailResult result, Long assetId) {
        return result.assets().stream().filter(a -> a.assetId().equals(assetId)).findFirst().orElseThrow();
    }

    private static HoldingItem holding(AssetItem asset, String ticker) {
        return asset.holdings().stream().filter(h -> h.ticker().equals(ticker)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("1. COMPLETE 날 — 자산군 금액 · 기준일 대비 증감, 자산별 묶음, 종목 수량 · 단가 · 금액 · 전일 대비, 예수금 · 수기 잔액")
    void completeDay() {
        twoCompleteDays();

        SnapshotDetailResult result = recordService.getDetail(userId, DAY2);

        assertThat(result.date()).isEqualTo(DAY2);
        assertThat(result.status()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(result.missing()).isFalse();
        assertThat(result.baseDate()).isEqualTo(DAY1);
        assertThat(result.referenceDate()).isEqualTo(DAY2);
        assertThat(result.totalAsset()).isEqualByComparingTo("2654000");
        assertThat(result.dayChangeAmount()).isEqualByComparingTo("34000");
        assertThat(result.marketChangeAmount()).isEqualByComparingTo("1000");     // TRADED 10 × 100 (HALTED 단가 같음, UNKNOWN 신규)
        assertThat(result.otherChangeAmount()).isEqualByComparingTo("33000");

        assertThat(result.assetClasses().securities().amount()).isEqualByComparingTo("24000");      // 11,000 + 10,000 + 3,000
        assertThat(result.assetClasses().securities().changeAmount()).isEqualByComparingTo("4000");
        assertThat(result.assetClasses().cash().amount()).isEqualByComparingTo("630000");           // 예수금 120,000 + 현금 510,000
        assertThat(result.assetClasses().cash().changeAmount()).isEqualByComparingTo("30000");
        assertThat(result.assetClasses().deposit().amount()).isEqualByComparingTo("2000000");
        assertThat(result.assetClasses().deposit().changeAmount()).isEqualByComparingTo("0");

        assertThat(result.assets()).extracting(AssetItem::assetId).containsExactly(securitiesId, cashId, depositId);
        AssetItem securities = asset(result, securitiesId);
        assertThat(securities.assetType()).isEqualTo(AssetType.SECURITIES);
        assertThat(securities.name()).isEqualTo("삼성증권");                    // 기관명
        assertThat(securities.amount()).isEqualByComparingTo("120000");           // 예수금(ticker NULL 행)
        assertThat(securities.dayChangeAmount()).isEqualByComparingTo("20000");
        assertThat(securities.holdings()).extracting(HoldingItem::ticker).containsExactly(TRADED, HALTED, UNKNOWN);
        HoldingItem traded = holding(securities, TRADED);
        assertThat(traded.quantity()).isEqualByComparingTo("10");
        assertThat(traded.price()).isEqualByComparingTo("1100");
        assertThat(traded.amount()).isEqualByComparingTo("11000");
        assertThat(traded.dayChangeAmount()).isEqualByComparingTo("1000");       // 종목 전일 대비 (FR-AN-01)
        assertThat(holding(securities, UNKNOWN).dayChangeAmount()).isNull();     // 신규 종목 — 기준일 행 없음

        AssetItem cash = asset(result, cashId);
        assertThat(cash.assetType()).isEqualTo(AssetType.CASH);
        assertThat(cash.name()).isEqualTo("생활비 통장");
        assertThat(cash.accountNumberMasked()).isNull();
        assertThat(cash.amount()).isEqualByComparingTo("510000");
        assertThat(cash.dayChangeAmount()).isEqualByComparingTo("10000");
        assertThat(cash.holdings()).isEmpty();
    }

    @Test
    @DisplayName("2. 종목명 — 그날 시세가 있으면 그 이름, 없으면 이전 날짜 최신 이름, 전혀 없으면 ticker")
    void stockNames() {
        twoCompleteDays();

        AssetItem securities = asset(recordService.getDetail(userId, DAY2), securitiesId);

        assertThat(holding(securities, TRADED).stockName()).isEqualTo("테스트종목A");
        assertThat(holding(securities, HALTED).stockName()).isEqualTo("정지종목B");
        assertThat(holding(securities, UNKNOWN).stockName()).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("3. PARTIAL 날 → assets [], totalAsset · referenceDate = 직전 COMPLETE, 증감 · baseDate · assetClasses null")
    void partialDay() {
        twoCompleteDays();
        record(DAY3, Account.of(securitiesId, AccountStatus.FAILED), "520000", "2000000"); // 증권 실패 → PARTIAL

        SnapshotDetailResult result = recordService.getDetail(userId, DAY3);

        assertThat(result.status()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(result.missing()).isTrue();
        assertThat(result.totalAsset()).isEqualByComparingTo("2654000");          // 부분 금액(2,520,000) 아님
        assertThat(result.referenceDate()).isEqualTo(DAY2);
        assertThat(result.baseDate()).isNull();
        assertThat(result.dayChangeAmount()).isNull();
        assertThat(result.dayChangeRate()).isNull();
        assertThat(result.marketChangeAmount()).isNull();
        assertThat(result.otherChangeAmount()).isNull();
        assertThat(result.assetClasses()).isNull();
        assertThat(result.assets()).isEmpty();
    }

    @Test
    @DisplayName("4. 비활성(soft delete) 수기 자산도 그날 기록이 있으면 보여준다")
    void inactiveManualAssetShown() {
        twoCompleteDays();
        transactionTemplate.executeWithoutResult(status -> assetRepository.findById(depositId).orElseThrow().deactivate());

        SnapshotDetailResult result = recordService.getDetail(userId, DAY2);

        assertThat(asset(result, depositId).name()).isEqualTo("정기예금");
        assertThat(asset(result, depositId).amount()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("5. 첫 COMPLETE(기준일 없음) → baseDate · 자산군 증감 · 전일 대비 null, 금액은 있음")
    void firstComplete() {
        twoCompleteDays();

        SnapshotDetailResult result = recordService.getDetail(userId, DAY1);

        assertThat(result.baseDate()).isNull();
        assertThat(result.dayChangeAmount()).isNull();
        assertThat(result.assetClasses().securities().amount()).isEqualByComparingTo("20000");
        assertThat(result.assetClasses().securities().changeAmount()).isNull();
        assertThat(result.assetClasses().cash().changeAmount()).isNull();
        assertThat(result.assetClasses().deposit().changeAmount()).isNull();
    }

    @Test
    @DisplayName("6. 없는 날짜 · 다른 사용자의 날짜 → SnapshotNotFoundException (존재 여부 비노출)")
    void notFound() throws Exception {
        twoCompleteDays();
        Long other = userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow().getId();

        assertThatThrownBy(() -> recordService.getDetail(userId, DAY1.minusDays(1))).isInstanceOf(SnapshotNotFoundException.class);
        assertThatThrownBy(() -> recordService.getDetail(other, DAY2)).isInstanceOf(SnapshotNotFoundException.class);
    }

    @Test
    @DisplayName("7. 계좌번호는 저장된 마스킹 값만 — 결과 어디에도 원문이 없다")
    void maskedAccountNumberOnly() {
        twoCompleteDays();
        String masked = transactionTemplate.execute(status -> assetRepository.findById(securitiesId).orElseThrow().getAccountNumberMasked());

        SnapshotDetailResult result = recordService.getDetail(userId, DAY2);

        assertThat(asset(result, securitiesId).accountNumberMasked()).isEqualTo(masked).isNotEqualTo(ACCOUNT_NUMBER);
        assertThat(result.toString()).doesNotContain(ACCOUNT_NUMBER);
    }

    @Test
    @DisplayName("8. 요청당 쿼리 수 — COMPLETE 4회 이하, PARTIAL 2회 이하 (자산 · 기관 N+1 없음)")
    void queryCount() {
        twoCompleteDays();
        record(DAY3, Account.of(securitiesId, AccountStatus.FAILED), "520000", "2000000");

        assertThat(statementsFor(() -> recordService.getDetail(userId, DAY2))).isLessThanOrEqualTo(4);
        assertThat(statementsFor(() -> recordService.getDetail(userId, DAY3))).isLessThanOrEqualTo(2);
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
