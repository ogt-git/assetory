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
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 잠정 종가 보정 — 16:00 잠정 종가로 저장한 거래일 기록을 다음 거래일에 확정 종가로 다시 평가한다 (01 §14 #23, kis-verification V4).
 * 데이터(테스트 전용 더미 값)
 * THU 10/29 COMPLETE: 005930 10주 × 220,000 + 069500 5주 × 30,000 + 예수금 100,000 + 현금 500,000 = 2,950,000
 * FRI 10/30 COMPLETE(잠정): 005930 10주 × 223,000 + 069500 5주 × 30,000 + 111111(거래정지, 직전 종가 10,000) 3주 + 예수금 100,000 + 현금 500,000 = 3,010,000
 * 확정 종가: 005930 225,000 · 069500 30,000(같음) · 111111 12,500(잠정 행 12,000 — 이 사용자는 직전 종가로 평가해 대상 아님)
 */
class SnapshotRevaluationServiceIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate THU = LocalDate.of(2026, 10, 29);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 30);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 31);
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @MockitoBean KisApiClient kis;
    @MockitoBean Clock clock;

    @Autowired SnapshotRevaluationService revaluationService;
    @Autowired SnapshotSaveService saveService;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired AssetDetailSnapshotRepository detailRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ApplicationContext context;

    private record Owner(Long userId, Long securitiesId, Long cashId) {}

    private Owner owner;

    @BeforeEach
    void setUp() throws Exception {
        // 종목 시세는 공유 테이블이라 이전 테스트 행이 섞이지 않게 비운다
        stockPriceRepository.deleteAll();
        now(MON.atTime(7, 30));
        owner = newOwner();

        saveService.save(owner.userId(), input(owner, THU, "100000",
                HoldingPrice.collected("005930", d("10"), d("220000")),
                HoldingPrice.collected("069500", d("5"), d("30000"))));
        saveService.save(owner.userId(), input(owner, FRI, "100000",
                HoldingPrice.collected("005930", d("10"), d("223000")),
                HoldingPrice.collected("069500", d("5"), d("30000")),
                HoldingPrice.halted("111111", d("3"), d("10000"))));

        // 16:00 배치가 저장한 FRI 잠정 종가
        provisional("005930", FRI, "223000");
        provisional("069500", FRI, "30000");
        provisional("111111", FRI, "12000");
        confirmed("005930", "225000");
        confirmed("069500", "30000");
        confirmed("111111", "12500");
    }

    private Owner newOwner() throws Exception {
        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        return transactionTemplate.execute(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-reval-" + UUID.randomUUID(), BusinessType.ST));
            Long securitiesId = assetRepository.save(
                    Asset.securitiesAccount(user, account, institution, "12345678901")).getId(); // 테스트 전용 더미 계좌번호
            Long cashId = assetRepository.save(
                    Asset.manual(user, AssetType.CASH, "생활비 통장", d("500000"))).getId();
            return new Owner(user.getId(), securitiesId, cashId);
        });
    }

    private void now(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static SnapshotInput input(Owner o, LocalDate date, String deposit, HoldingPrice... holdings) {
        return new SnapshotInput(date, date.atTime(16, 5),
                List.of(Account.success(o.securitiesId(), d(deposit), List.of(holdings))),
                List.of(new Manual(o.cashId(), AssetType.CASH, d("500000"))));
    }

    private void provisional(String ticker, LocalDate date, String close) {
        transactionTemplate.executeWithoutResult(status -> {
            StockPrice price = StockPrice.create(ticker, date);
            price.update("테스트종목", d(close), d(close), BigDecimal.ZERO, 1L);
            stockPriceRepository.save(price);
        });
    }

    // 다음 거래일 아침 한투 응답 — FRI 확정 행 + THU 행
    private void confirmed(String ticker, String friClose) {
        doReturn(List.of(
                new KisDailyPrice(FRI, d(friClose), null, null, BigDecimal.ONE, 2L, null, null),
                new KisDailyPrice(THU, d("1"), null, null, BigDecimal.ZERO, 1L, null, null)))
                .when(kis).getDailyPrices(ticker);
    }

    private AssetSnapshot snapshot(Long userId, LocalDate date) {
        return snapshotRepository.findByUserIdAndSnapshotDate(userId, date).orElseThrow();
    }

    private AssetDetailSnapshot detail(Long userId, LocalDate date, String ticker) {
        return transactionTemplate.execute(status -> detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, date)
                .stream().filter(x -> Objects.equals(x.getTicker(), ticker)).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("종가가 바뀐 종목만 다시 평가 — 금액 · 증권 · 총자산 · 전일 대비 · 시장가격 영향 · 기타가 확정 종가 기준으로 바뀌고, 상태 · 수집 시각 · 수량은 그대로")
    void revaluesRecordWithConfirmedClose() {
        AssetSnapshot before = snapshot(owner.userId(), FRI);

        RevaluationSummary summary = revaluationService.revalue(FRI);

        AssetSnapshot fri = snapshot(owner.userId(), FRI);
        assertThat(fri.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(fri.getCollectedAt()).isEqualTo(before.getCollectedAt());
        assertThat(fri.getTotalAsset()).isEqualByComparingTo("3030000.00");
        assertThat(fri.getSecuritiesAmount()).isEqualByComparingTo("2430000.00");
        assertThat(fri.getCashAmount()).isEqualByComparingTo("600000.00");
        assertThat(fri.getBaseSnapshotDate()).isEqualTo(THU);
        assertThat(fri.getDayChangeAmount()).isEqualByComparingTo("80000.00");     // 잠정 60,000
        assertThat(fri.getMarketChangeAmount()).isEqualByComparingTo("50000.00");  // 10주 × (225,000 − 220,000), 잠정 30,000
        assertThat(fri.getOtherChangeAmount()).isEqualByComparingTo("30000.00");   // 새 종목 111111 (변동 없음)

        AssetDetailSnapshot samsung = detail(owner.userId(), FRI, "005930");
        assertThat(samsung.getPrice()).isEqualByComparingTo("225000");
        assertThat(samsung.getQuantity()).isEqualByComparingTo("10");
        assertThat(samsung.getAmount()).isEqualByComparingTo("2250000.00");
        assertThat(samsung.getDayChangeAmount()).isEqualByComparingTo("50000.00");
        // 거래정지 — 직전 종가(10,000)로 평가한 행은 그날 잠정 종가(12,000) 보정 대상이 아니다
        assertThat(detail(owner.userId(), FRI, "111111").getPrice()).isEqualByComparingTo("10000");

        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", FRI).orElseThrow().getClosePrice())
                .isEqualByComparingTo("225000");
        assertThat(summary.revaluedRecords()).isEqualTo(1);
        assertThat(summary.confirm().changes()).containsOnlyKeys("005930", "111111");
    }

    @Test
    @DisplayName("다시 돌려도 결과가 같다(멱등) — 두 번째는 종가가 이미 확정값이라 기록을 건드리지 않는다")
    void idempotent() {
        revaluationService.revalue(FRI);
        RevaluationSummary second = revaluationService.revalue(FRI);

        assertThat(second.confirm().changes()).isEmpty();
        assertThat(second.revaluedRecords()).isZero();
        assertThat(snapshot(owner.userId(), FRI).getTotalAsset()).isEqualByComparingTo("3030000.00");
    }

    @Test
    @DisplayName("확정 종가가 잠정과 같으면 기록을 건드리지 않는다")
    void noChangeNoRevaluation() {
        confirmed("005930", "223000");
        confirmed("111111", "12000");

        RevaluationSummary summary = revaluationService.revalue(FRI);

        assertThat(summary.revaluedRecords()).isZero();
        assertThat(snapshot(owner.userId(), FRI).getTotalAsset()).isEqualByComparingTo("3010000.00");
        assertThat(snapshot(owner.userId(), FRI).getMarketChangeAmount()).isEqualByComparingTo("30000.00");
    }

    @Test
    @DisplayName("보정이 늦어 다음 기록이 이미 있으면, 그 기록의 전일 대비 · 시장가격 영향 · 종목 전일 대비도 보정된 기록 기준으로 다시 계산한다")
    void recomputesFollowingRecord() {
        now(MON.atTime(17, 0));
        saveService.save(owner.userId(), input(owner, MON, "100000",
                HoldingPrice.collected("005930", d("10"), d("226000")),
                HoldingPrice.collected("069500", d("5"), d("30000")),
                HoldingPrice.halted("111111", d("3"), d("10000"))));
        // MON = 2,260,000 + 150,000 + 30,000 + 600,000 = 3,040,000 — 잠정 FRI(3,010,000) 대비 +30,000

        RevaluationSummary summary = revaluationService.revalue(FRI);

        AssetSnapshot mon = snapshot(owner.userId(), MON);
        assertThat(mon.getTotalAsset()).isEqualByComparingTo("3040000.00");      // 그날 값은 그대로
        assertThat(mon.getBaseSnapshotDate()).isEqualTo(FRI);
        assertThat(mon.getDayChangeAmount()).isEqualByComparingTo("10000.00");    // 보정된 FRI 3,030,000 대비
        assertThat(mon.getMarketChangeAmount()).isEqualByComparingTo("10000.00"); // 10주 × (226,000 − 225,000)
        assertThat(mon.getOtherChangeAmount()).isEqualByComparingTo("0.00");
        assertThat(detail(owner.userId(), MON, "005930").getDayChangeAmount()).isEqualByComparingTo("10000.00");
        assertThat(summary.recomputedFollowing()).isEqualTo(1);
    }

    @Test
    @DisplayName("PARTIAL 기록도 저장된 종목은 보정하되 상태는 PARTIAL 그대로 — 다른 사용자 · 다른 종목 기록은 건드리지 않는다")
    void partialKeepsStatusOthersUntouched() throws Exception {
        Owner partial = newOwner();
        saveService.save(partial.userId(), new SnapshotInput(FRI, FRI.atTime(16, 5),
                List.of(Account.success(partial.securitiesId(), d("0"),
                        List.of(HoldingPrice.collected("005930", d("1"), d("223000")),
                                HoldingPrice.failed("000660", d("2"))))),
                List.of(new Manual(partial.cashId(), AssetType.CASH, d("500000")))));
        Owner other = newOwner();
        saveService.save(other.userId(), input(other, FRI, "100000",
                HoldingPrice.collected("069500", d("5"), d("30000"))));

        revaluationService.revalue(FRI);

        AssetSnapshot partialFri = snapshot(partial.userId(), FRI);
        assertThat(partialFri.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(partialFri.getTotalAsset()).isEqualByComparingTo("725000.00"); // 1주 × 225,000 + 현금 500,000
        assertThat(partialFri.getDayChangeAmount()).isNull();
        assertThat(snapshot(other.userId(), FRI).getTotalAsset()).isEqualByComparingTo("750000.00");
    }

    @Test
    @DisplayName("스케줄 실행: 대상은 오늘보다 이전의 가장 최근 기록 날짜 — 오늘 날짜 기록은 대상이 아니다")
    void revalueLatestPicksLatestRecordBeforeToday() {
        // 토요일 아침 실행 — 오늘(10/31) 날짜 기록이 있어도 대상은 직전 기록일(10/30)
        now(SAT.atTime(7, 30));
        saveService.save(owner.userId(), input(owner, SAT, "100000",
                HoldingPrice.collected("005930", d("10"), d("225000"))));

        RevaluationSummary summary = revaluationService.revalueLatest().orElseThrow();

        assertThat(summary.tradeDate()).isEqualTo(FRI);
        assertThat(snapshot(owner.userId(), FRI).getTotalAsset()).isEqualByComparingTo("3030000.00");
    }

    @Test
    @DisplayName("스케줄 실행: 오늘 이전 기록이 하나도 없으면 아무것도 하지 않는다")
    void revalueLatestWithoutRecords() {
        now(LocalDate.of(2000, 1, 3).atTime(7, 30));

        assertThat(revaluationService.revalueLatest()).isEmpty();
    }

    @Test
    @DisplayName("보정 스케줄러는 기본 비활성 — assetory.sync.confirm.enabled가 없으면 빈이 없다")
    void schedulerDisabledByDefault() {
        assertThat(context.getBeanNamesForType(SnapshotRevaluationScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("오늘 날짜는 확정 전이라 보정하지 않는다")
    void todayIsNotRevalued() {
        now(FRI.atTime(17, 0));

        RevaluationSummary summary = revaluationService.revalue(FRI);

        assertThat(summary.revaluedRecords()).isZero();
        assertThat(snapshot(owner.userId(), FRI).getTotalAsset()).isEqualByComparingTo("3010000.00");
    }
}
