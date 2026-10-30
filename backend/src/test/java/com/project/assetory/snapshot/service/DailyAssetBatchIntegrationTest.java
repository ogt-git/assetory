package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.service.StockRefreshService;
import com.project.assetory.connect.service.StockSyncService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.kis.KisStockInfo;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.StockPriceCollectService;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.DailyAssetBatchService.BatchSummary;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 영업일 16:00 일일 자산 기록 배치 (FR-AS-02 · 05 · 06, FR-MK-01 · 05 · 06, 05 §11.2) — 외부 API는 목
@TestPropertySource(properties = {"assetory.sync.batch.lock-retry-wait-ms=0", "assetory.market.late-close-dates=2030-01-08"})
class DailyAssetBatchIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 미래 월요일 — 테스트에서 만든 연동은 모두 T 이전에 생성된 것이 된다
    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);
    private static final LocalDate PREV = LocalDate.of(2030, 1, 4);
    private static final LocalDateTime T = DAY.atTime(16, 0);
    // 장 마감 지연일(수능일 같은 날) — @TestPropertySource의 late-close-dates. 지연 가드 16:40
    private static final LocalDate LATE = LocalDate.of(2030, 1, 8);
    private static final String STOCK_URL = "/v1/kr/stock/a/account/financial-assets";

    @MockitoBean KisApiClient kis;
    @MockitoBean Clock clock;
    @MockitoSpyBean StockSyncService stockSyncService;
    @MockitoSpyBean SnapshotSaveService snapshotSaveService;

    @Autowired DailyAssetBatchService batchService;
    @Autowired StockPriceCollectService stockPriceCollectService;
    @Autowired StockRefreshService stockRefreshService;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired AssetDetailSnapshotRepository detailRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;
    @Autowired MeterRegistry meterRegistry;

    // connectedId 접두어 → CODEF 응답
    private final Map<String, String> codefResponses = new HashMap<>();

    private record LinkedUser(Long userId, Long accountId, Long securitiesAssetId, String connectedId) {}

    @BeforeEach
    void setUp() throws Exception {
        // 배치는 전체 연동 · 수기 자산을 대상으로 하므로 이전 테스트 데이터를 대상에서 뺀다
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from AssetDetailSnapshot").executeUpdate();
            entityManager.createQuery("delete from AssetSnapshot").executeUpdate();
            entityManager.createQuery("delete from StockPrice").executeUpdate();
            entityManager.createQuery("delete from Holding").executeUpdate();
            entityManager.createQuery("update ConnectedAccount c set c.status = :s")
                    .setParameter("s", ConnectedAccountStatus.DISCONNECTED).executeUpdate();
            entityManager.createQuery("update Asset a set a.active = false").executeUpdate();
        });
        codefResponses.clear();
        when(clock.instant()).thenReturn(T.atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        when(codefApiClient.requestProduct(eq(STOCK_URL), any())).thenAnswer(inv -> {
            Map<String, Object> params = inv.getArgument(1);
            String connectedId = (String) params.get("connectedId");
            return codefResponses.entrySet().stream()
                    .filter(e -> connectedId.startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst()
                    .orElse(codefFailure());
        });
        // 기본: 모든 종목(기준 종목 포함)이 오늘 거래됨
        doAnswer(inv -> rows(new BigDecimal("71200"), new BigDecimal("70000"))).when(kis).getDailyPrices(anyString());
    }

    // ---------- 준비 helpers ----------

    private User newUser() throws Exception {
        return userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
    }

    private LinkedUser linkedUser(ConnectedAccountStatus status) throws Exception {
        User user = newUser();
        String connectedId = "cid-" + UUID.randomUUID();
        Long[] ids = transactionTemplate.execute(tx -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
            account.changeStatus(status);
            Long assetId = assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
            return new Long[]{account.getId(), assetId};
        });
        return new LinkedUser(user.getId(), ids[0], ids[1], connectedId);
    }

    private Long manualCash(Long userId, String balance) {
        return transactionTemplate.execute(tx -> assetRepository.save(Asset.manual(
                userRepository.getReferenceById(userId), AssetType.CASH, "생활비", new BigDecimal(balance))).getId());
    }

    private static List<KisDailyPrice> rows(BigDecimal close, BigDecimal prevClose) {
        return List.of(
                new KisDailyPrice(DAY, close, close.subtract(prevClose), "2", new BigDecimal("1.71"), 1000L, "00", null),
                new KisDailyPrice(PREV, prevClose, BigDecimal.ZERO, "3", BigDecimal.ZERO, 900L, "00", null));
    }

    private static String item(String ticker, String quantity, String valuation) {
        return """
                {"resProductType":"주식","resItemName":"종목%s","resItemCode":"%s","resQuantity":"%s",
                 "resValuationAmt":"%s","resPurchaseAmount":"%s","resAccountCurrency":"KRW"}""".formatted(ticker, ticker, quantity, valuation, valuation);
    }

    private static String codefSuccess(String deposit, String... items) {
        return """
                {"result":{"code":"CF-00000","message":"성공"},
                 "data":{"resDepositReceived":"%s","resItemList":[%s]}}""".formatted(deposit, String.join(",", items));
    }

    private static String codefFailure() {
        return "{\"result\":{\"code\":\"CF-04000\",\"message\":\"테스트 전용 실패\"},\"data\":{}}";
    }

    private static String codefEmpty() {
        return "{\"result\":{\"code\":\"CF-03999\",\"message\":\"테스트 전용 결과 없음\"},\"data\":{}}";
    }

    private AssetSnapshot snapshot(Long userId) {
        return snapshotRepository.findByUserIdAndSnapshotDate(userId, DAY).orElse(null);
    }

    private List<AssetDetailSnapshot> details(Long userId) {
        return detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, DAY);
    }

    private boolean hasDetail(Long userId, Long assetId, String ticker) {
        return transactionTemplate.execute(tx -> details(userId).stream()
                .anyMatch(d -> d.getAsset().getId().equals(assetId) && Objects.equals(d.getTicker(), ticker)));
    }

    private void now(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(KST).toInstant());
    }

    private double runCount(String result) {
        var counter = meterRegistry.find("batch.run").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------- 거래일 판정 ----------

    @Test
    @DisplayName("휴장일(기준 종목 최신 거래일 < 오늘) → CODEF · 시세 호출 0회, 스냅샷 0건")
    void holiday_nothing() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        doReturn(rows(new BigDecimal("70000"), new BigDecimal("69000")).subList(1, 2)).when(kis).getDailyPrices("005930");

        BatchSummary summary = batchService.run();

        assertThat(summary.tradingDay()).isEqualTo(com.project.assetory.market.domain.TradingDayStatus.HOLIDAY);
        verify(codefApiClient, never()).requestProduct(anyString(), any());
        assertThat(snapshot(user.userId())).isNull();
    }

    @Test
    @DisplayName("거래일 판정 불가(UNKNOWN) → 전체 중단: CODEF · 종목 시세 호출 0회, 스냅샷 0건, batch.run{aborted} 기록")
    void unknown_aborts() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        double before = runCount("aborted");
        doThrow(new KisApiException(KisErrorType.NETWORK_ERROR, null)).when(kis).getDailyPrices(anyString());

        batchService.run();

        verify(codefApiClient, never()).requestProduct(anyString(), any());
        verify(kis, never()).getStockInfo(anyString());
        assertThat(snapshot(user.userId())).isNull();
        assertThat(stockPriceRepository.count()).isZero();
        assertThat(runCount("aborted") - before).isEqualTo(1.0);
    }

    // ---------- 장 마감 지연일 (수능일) ----------

    @Test
    @DisplayName("지연일 16:00 실행 → 건너뜀: 거래일 판정 · CODEF · 시세 호출 0회, 스냅샷 0건, batch.run{deferred} 기록")
    void lateClose_regularRun_deferred() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));
        now(LATE.atTime(16, 0));
        double before = runCount("deferred");

        batchService.run();

        verify(kis, never()).getDailyPrices(anyString());
        verify(codefApiClient, never()).requestProduct(anyString(), any());
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(user.userId(), LATE)).isEmpty();
        assertThat(stockPriceRepository.count()).isZero();
        assertThat(runCount("deferred") - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("일반일 장 마감 가드 전 실행(cron 오설정 등) → 미룰 실행이 없으므로 중단: 호출 0회, 스냅샷 0건, batch.run{aborted} (C-03)")
    void regularDay_beforeGuard_aborted() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));
        now(DAY.atTime(15, 0));
        double abortedBefore = runCount("aborted");
        double deferredBefore = runCount("deferred");

        BatchSummary summary = batchService.run();

        verify(kis, never()).getDailyPrices(anyString());
        verify(codefApiClient, never()).requestProduct(anyString(), any());
        assertThat(snapshot(user.userId())).isNull();
        assertThat(runCount("aborted") - abortedBefore).isEqualTo(1.0);
        assertThat(runCount("deferred") - deferredBefore).isZero();
        assertThat(summary.tradingDay()).isEqualTo(com.project.assetory.market.domain.TradingDayStatus.UNKNOWN);
    }

    @Test
    @DisplayName("지연일 16:00 시세 수집 → 장 마감 가드로 저장 0건 (장중 가격을 종가로 저장하지 않음)")
    void lateClose_priceGuard() {
        now(LATE.atTime(16, 0));

        assertThat(stockPriceCollectService.collect(LATE).closeGuardBlocked()).isTrue();
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("일반일 지연 실행 트리거 → 아무것도 하지 않음")
    void lateCloseTrigger_normalDay_noop() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));
        now(DAY.atTime(16, 50));

        assertThat(batchService.runLateClose()).isEmpty();

        verify(kis, never()).getDailyPrices(anyString());
        verify(codefApiClient, never()).requestProduct(anyString(), any());
        assertThat(snapshot(user.userId())).isNull();
    }

    @Test
    @DisplayName("지연일 16:50 지연 실행 → 그날 종가로 COMPLETE 기록")
    void lateCloseTrigger_lateDay_records() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));
        doAnswer(inv -> List.of(
                new KisDailyPrice(LATE, new BigDecimal("72000"), new BigDecimal("800"), "2", new BigDecimal("1.12"), 1000L, "00", null),
                new KisDailyPrice(DAY, new BigDecimal("71200"), BigDecimal.ZERO, "3", BigDecimal.ZERO, 900L, "00", null)))
                .when(kis).getDailyPrices(anyString());
        now(LATE.atTime(16, 50));

        assertThat(batchService.runLateClose()).isPresent();

        AssetSnapshot snap = snapshotRepository.findByUserIdAndSnapshotDate(user.userId(), LATE).orElseThrow();
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snap.getSecuritiesAmount()).isEqualByComparingTo("720000"); // 마감 후 종가 72,000 × 10
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", LATE).orElseThrow().getClosePrice())
                .isEqualByComparingTo("72000");
    }

    // ---------- 정상 · 실패 계좌 ----------

    @Test
    @DisplayName("정상일 전체 성공 → COMPLETE, stock_prices · detail(예수금 · 종목 · 수기) 생성")
    void allSuccess_complete() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        Long cashId = manualCash(user.userId(), "50000");
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));

        BatchSummary summary = batchService.run();

        AssetSnapshot snap = snapshot(user.userId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snap.getSecuritiesAmount()).isEqualByComparingTo("712000"); // 한투 종가 71,200 × 10 (CODEF 평가액 아님)
        assertThat(snap.getCashAmount()).isEqualByComparingTo("150000");
        assertThat(snap.getCollectedAt()).isEqualTo(T);
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", DAY)).isPresent();
        assertThat(hasDetail(user.userId(), user.securitiesAssetId(), null)).isTrue();
        assertThat(hasDetail(user.userId(), user.securitiesAssetId(), "005930")).isTrue();
        assertThat(hasDetail(user.userId(), cashId, null)).isTrue();
        assertThat(summary.snapshots()).containsEntry(SnapshotStatus.COMPLETE, 1);
    }

    @Test
    @DisplayName("이월 방지: 오늘 CODEF가 실패하면 DB에 남은 예전 예수금 · 종목 값을 스냅샷에 쓰지 않는다")
    void accountFailed_noCarryOver() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        manualCash(user.userId(), "50000");
        // 어제까지 수집된 값이 DB에 남아 있다
        codefResponses.put(user.connectedId(), codefSuccess("999999", item("005930", "10", "700000")));
        stockSyncService.sync(user.accountId(), SyncJobType.HOLDING);
        // 오늘은 실패
        codefResponses.put(user.connectedId(), codefFailure());

        batchService.run();

        AssetSnapshot snap = snapshot(user.userId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(snap.getSecuritiesAmount()).isNull(); // 증권 단위 전부 실패 → NULL (확정 7)
        assertThat(snap.getCashAmount()).isEqualByComparingTo("50000"); // 999,999 예수금이 들어가지 않음
        assertThat(hasDetail(user.userId(), user.securitiesAssetId(), null)).isFalse();
        assertThat(hasDetail(user.userId(), user.securitiesAssetId(), "005930")).isFalse();
    }

    @Test
    @DisplayName("EMPTY_RESULT → 그 계좌 누락 (DB의 예전 값 미사용)")
    void emptyResult_missing() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        manualCash(user.userId(), "50000");
        codefResponses.put(user.connectedId(), codefSuccess("999999", item("005930", "10", "700000")));
        stockSyncService.sync(user.accountId(), SyncJobType.HOLDING);
        codefResponses.put(user.connectedId(), codefEmpty());

        BatchSummary summary = batchService.run();

        assertThat(snapshot(user.userId()).getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(snapshot(user.userId()).getCashAmount()).isEqualByComparingTo("50000");
        assertThat(summary.accountResults())
                .containsEntry(com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus.EMPTY_RESULT, 1);
    }

    @Test
    @DisplayName("동기화 락 충돌 → 대기 후 1회 재시도해 성공하면 COMPLETE")
    void lockConflict_retrySucceeds() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000"));
        doThrow(new CodefSyncLockConflictException()).doCallRealMethod()
                .when(stockSyncService).sync(eq(user.accountId()), eq(SyncJobType.HOLDING));

        batchService.run();

        assertThat(snapshot(user.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
    }

    @Test
    @DisplayName("재시도도 락 충돌 → LOCK_FAILED(누락), 수기 자산만 있으면 PARTIAL")
    void lockConflict_twice_lockFailed() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        manualCash(user.userId(), "50000");
        doThrow(new CodefSyncLockConflictException())
                .when(stockSyncService).sync(eq(user.accountId()), eq(SyncJobType.HOLDING));

        BatchSummary summary = batchService.run();

        assertThat(snapshot(user.userId()).getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(summary.accountResults())
                .containsEntry(com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus.LOCK_FAILED, 1);
    }

    @Test
    @DisplayName("ACTIVE 연동인데 증권 자산 행이 없음 → 그 계좌 FAILED(누락), 수기 자산이 있으면 PARTIAL (C-06)")
    void activeLinkWithoutAssetRow_failed() throws Exception {
        User user = newUser();
        transactionTemplate.executeWithoutResult(tx -> connectedAccountRepository.save(ConnectedAccount.create(
                user, institutionRepository.findByCodefOrgCode("0240").orElseThrow(),
                "cid-noasset-" + UUID.randomUUID(), BusinessType.ST)));
        manualCash(user.getId(), "50000");

        BatchSummary summary = batchService.run();

        AssetSnapshot snap = snapshot(user.getId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(snap.getSecuritiesAmount()).isNull();
        assertThat(snap.getCashAmount()).isEqualByComparingTo("50000");
        assertThat(summary.accountResults())
                .containsEntry(com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus.FAILED, 1);
    }

    @Test
    @DisplayName("AUTH_FAILED 연동 → CODEF 호출 없이 EXCLUDED_AUTH, 수기 자산이 있으면 PARTIAL")
    void authFailed_excluded() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.AUTH_FAILED);
        manualCash(user.userId(), "50000");

        batchService.run();

        verify(codefApiClient, never()).requestProduct(anyString(),
                argThat(params -> String.valueOf(params.get("connectedId")).equals(user.connectedId())));
        assertThat(snapshot(user.userId()).getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
    }

    @Test
    @DisplayName("T 이후 생성된 연동 · DISCONNECTED 연동은 대상이 아니다 — 다른 자산이 없으면 행 없음")
    void notTargets() throws Exception {
        LinkedUser late = linkedUser(ConnectedAccountStatus.ACTIVE);
        LinkedUser gone = linkedUser(ConnectedAccountStatus.DISCONNECTED);
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createQuery("update ConnectedAccount c set c.createdAt = :at where c.id = :id")
                .setParameter("at", T.plusHours(1)).setParameter("id", late.accountId()).executeUpdate());

        batchService.run();

        verify(codefApiClient, never()).requestProduct(anyString(), any());
        assertThat(snapshot(late.userId())).isNull();
        assertThat(snapshot(gone.userId())).isNull();
    }

    @Test
    @DisplayName("거래정지 종목 → stock_prices의 직전 종가로 평가, COMPLETE")
    void halted_previousClose() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("0", item("111111", "5", "60000")));
        transactionTemplate.executeWithoutResult(tx -> {
            StockPrice prev = StockPrice.create("111111", PREV);
            prev.update("정지종목", new BigDecimal("12000"), new BigDecimal("12000"), BigDecimal.ZERO, 0L);
            stockPriceRepository.save(prev);
        });
        doReturn(rows(new BigDecimal("12000"), new BigDecimal("12000")).subList(1, 2)).when(kis).getDailyPrices("111111");
        doReturn(new KisStockInfo("111111", "정지종목", "ST", "0", null, "Y", "N", null)).when(kis).getStockInfo("111111");

        batchService.run();

        AssetSnapshot snap = snapshot(user.userId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snap.getSecuritiesAmount()).isEqualByComparingTo("60000"); // 12,000 × 5
        assertThat(stockPriceRepository.findByTickerAndTradeDate("111111", DAY)).isEmpty(); // 거래정지일 행은 만들지 않음
        verify(kis, times(1)).getDailyPrices("111111"); // 직전 종가 이력이 있으면 소급하지 않는다 (시세 수집 1회만)
    }

    // ---------- 거래정지 + 직전 종가 이력 없음 → 소급 보완 (C-04, FR-MK-04) ----------

    // 장기 거래정지 종목 — 한투 일자별 응답에 오늘 행이 없고 거래정지 전 거래일만 온다 (테스트 전용 더미 시세, 실호출 미확인)
    private static final LocalDate HALT_LAST = LocalDate.of(2029, 12, 20);

    private void longHalted(String ticker) {
        doReturn(List.of(
                new KisDailyPrice(HALT_LAST, new BigDecimal("12000"), new BigDecimal("100"), "2", new BigDecimal("0.84"), 10L, "00", null),
                new KisDailyPrice(HALT_LAST.minusDays(1), new BigDecimal("11900"), BigDecimal.ZERO, "3", BigDecimal.ZERO, 10L, "00", null),
                new KisDailyPrice(HALT_LAST.minusDays(2), new BigDecimal("11900"), BigDecimal.ZERO, "3", BigDecimal.ZERO, 10L, "00", null)))
                .when(kis).getDailyPrices(ticker);
        doReturn(new KisStockInfo(ticker, "정지종목", "ST", "0", null, "Y", "N", null)).when(kis).getStockInfo(ticker);
    }

    @Test
    @DisplayName("거래정지 + 직전 종가 이력 없음 + 소급 성공 → COMPLETE, 단가 = 소급한 거래정지 전 마지막 종가")
    void halted_noHistory_backfillSucceeds_complete() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("0", item("111111", "5", "60000")));
        longHalted("111111");

        batchService.run();

        AssetSnapshot snap = snapshot(user.userId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snap.getSecuritiesAmount()).isEqualByComparingTo("60000"); // 12,000 × 5
        AssetDetailSnapshot halted = transactionTemplate.execute(tx -> details(user.userId()).stream()
                .filter(d -> "111111".equals(d.getTicker())).findFirst().orElseThrow());
        assertThat(halted.getPrice()).isEqualByComparingTo("12000");
        assertThat(stockPriceRepository.findByTickerAndTradeDate("111111", HALT_LAST)).isPresent();
        assertThat(stockPriceRepository.findByTickerAndTradeDate("111111", DAY)).isEmpty();
        verify(kis, times(2)).getDailyPrices("111111"); // 시세 수집 1회 + 소급 1회
    }

    @Test
    @DisplayName("거래정지 + 직전 종가 이력 없음 + 소급 실패 → 그 종목 실패(PARTIAL), 0으로 채우지 않음")
    void halted_noHistory_backfillFails_partial() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("111111", "5", "60000")));
        longHalted("111111");
        // 시세 수집(1회차)은 응답, 소급(2회차)은 한투 오류
        doReturn(List.of(new KisDailyPrice(HALT_LAST, new BigDecimal("12000"), BigDecimal.ZERO, "3", BigDecimal.ZERO, 10L, "00", null)))
                .doThrow(new KisApiException(KisErrorType.API_ERROR, null))
                .when(kis).getDailyPrices("111111");

        batchService.run();

        AssetSnapshot snap = snapshot(user.userId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(snap.getSecuritiesAmount()).isNull();                    // 종목 대상이 전부 실패 → NULL (0 아님)
        assertThat(snap.getCashAmount()).isEqualByComparingTo("100000");   // 예수금은 성공
        assertThat(hasDetail(user.userId(), user.securitiesAssetId(), "111111")).isFalse();
        verify(kis, times(2)).getDailyPrices("111111");
    }

    @Test
    @DisplayName("두 사용자가 같은 장기 거래정지 종목 보유 → 같은 실행에서 소급 1회, 둘 다 COMPLETE")
    void halted_noHistory_twoUsers_backfillOnce() throws Exception {
        LinkedUser first = linkedUser(ConnectedAccountStatus.ACTIVE);
        LinkedUser second = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(first.connectedId(), codefSuccess("0", item("111111", "5", "60000")));
        codefResponses.put(second.connectedId(), codefSuccess("0", item("111111", "2", "24000")));
        longHalted("111111");

        batchService.run();

        assertThat(snapshot(first.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snapshot(second.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snapshot(second.userId()).getSecuritiesAmount()).isEqualByComparingTo("24000"); // 12,000 × 2
        verify(kis, times(2)).getDailyPrices("111111"); // 시세 수집 1회(종목 합집합) + 소급 1회
    }

    // ---------- 재실행 · 격리 · 대상 ----------

    @Test
    @DisplayName("재실행: COMPLETE 다음 실행이 실패해도 COMPLETE 유지 / PARTIAL 다음 COMPLETE는 갱신")
    void rerun() throws Exception {
        LinkedUser kept = linkedUser(ConnectedAccountStatus.ACTIVE);
        LinkedUser upgraded = linkedUser(ConnectedAccountStatus.ACTIVE);
        manualCash(upgraded.userId(), "1000");
        codefResponses.put(kept.connectedId(), codefSuccess("100000"));
        codefResponses.put(upgraded.connectedId(), codefFailure());
        batchService.run();
        assertThat(snapshot(kept.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snapshot(upgraded.userId()).getStatus()).isEqualTo(SnapshotStatus.PARTIAL);

        codefResponses.put(kept.connectedId(), codefFailure());
        codefResponses.put(upgraded.connectedId(), codefSuccess("200000"));
        batchService.run();

        assertThat(snapshot(kept.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snapshot(kept.userId()).getTotalAsset()).isEqualByComparingTo("100000");
        assertThat(snapshot(upgraded.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snapshot(upgraded.userId()).getTotalAsset()).isEqualByComparingTo("201000");
    }

    @Test
    @DisplayName("사용자 A 처리 중 예외 → A는 행 없음(FAILED로 바꾸지 않음), B는 정상 저장")
    void userFailureIsolated() throws Exception {
        LinkedUser a = linkedUser(ConnectedAccountStatus.ACTIVE);
        LinkedUser b = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(a.connectedId(), codefSuccess("1"));
        codefResponses.put(b.connectedId(), codefSuccess("2"));
        doThrow(new IllegalStateException("테스트 전용")).when(snapshotSaveService).save(eq(a.userId()), any());

        BatchSummary summary = batchService.run();

        assertThat(snapshot(a.userId())).isNull();
        assertThat(snapshot(b.userId()).getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(summary.userErrors()).isEqualTo(1);
    }

    @Test
    @DisplayName("수기 자산만 있는 사용자 → COMPLETE, 증권 대상이 없으니 securities_amount 0 (확정 7)")
    void manualOnly_complete() throws Exception {
        User user = newUser();
        manualCash(user.getId(), "50000");

        batchService.run();

        AssetSnapshot snap = snapshot(user.getId());
        assertThat(snap.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(snap.getSecuritiesAmount()).isEqualByComparingTo("0");
        assertThat(snap.getTotalAsset()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("수동 새로고침은 스냅샷을 만들지 않는다 (정기 배치에서만)")
    void manualRefresh_noSnapshot() throws Exception {
        LinkedUser user = linkedUser(ConnectedAccountStatus.ACTIVE);
        codefResponses.put(user.connectedId(), codefSuccess("100000", item("005930", "10", "700000")));

        stockRefreshService.refresh(user.userId());

        assertThat(snapshotRepository.findAll()).isEmpty();
        verify(snapshotSaveService, never()).save(any(), any());
    }
}
