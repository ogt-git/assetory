package com.project.assetory.connect;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJob;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.event.ConnectedAccountLinkedEvent;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.repository.SyncJobRepository;
import com.project.assetory.detection.service.StockEventBackfillService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.service.StockPriceBackfillService;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 최초 연동 수집(BACKFILL) 성공 뒤 같은 비동기 작업에서 보유 종목 시세 소급 (FR-MK-04, 05 §11.1)
class InitialSyncBackfillIntegrationTest extends IntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final LocalDate LATEST = LocalDate.of(2025, 3, 7); // 테스트 전용 과거 거래일 — 다른 테스트 시세와 겹치지 않게
    private static final String EMPTY_RESULT = "{\"result\":{\"code\":\"CF-03999\",\"message\":\"테스트 전용 결과 없음\"},\"data\":{}}";

    @MockitoBean KisApiClient kis;
    @MockitoSpyBean StockPriceBackfillService backfillService;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired SyncJobRepository syncJobRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("codefSyncExecutor") ThreadPoolTaskExecutor codefSyncExecutor;

    private final AtomicInteger backfillsDone = new AtomicInteger();
    @MockitoSpyBean StockEventBackfillService eventBackfillService;
    private final AtomicInteger eventBackfillsDone = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from stock_prices where trade_date between ? and ?", LATEST.minusDays(10), LATEST);
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        when(codefApiClient.requestProduct(anyString(), any())).thenReturn(fixture("stock-financial-assets-success.json"));
        when(kis.getDailyPrices(anyString())).thenReturn(days(5));
        // 실제 소급을 실행하고 끝난 횟수를 센다 (비동기 완료 대기용)
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            backfillsDone.incrementAndGet();
            return result;
        }).when(backfillService).backfillHoldings(anyLong());
    }

    @Test
    @DisplayName("CODEF 수집 SUCCESS → 같은 작업에서 소급 호출, 보유 종목 시세 저장 (가장 오래된 날 제외)")
    void syncSuccess_backfills() throws Exception {
        Connection connection = createSecuritiesConnection();

        publishLinked(connection.id());

        awaitUntil(() -> backfillsDone.get() == 1, WAIT);
        verify(backfillService).backfillHoldings(connection.assetId());
        assertThat(jobs(connection.id())).singleElement()
                .satisfies(job -> assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS));
        List<String> tickers = holdingTickers(connection.assetId());
        assertThat(tickers).hasSize(2);
        for (String ticker : tickers) {
            assertThat(priceRows(ticker)).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("CODEF 수집 실패 → 소급 호출 없음")
    void syncFailed_noBackfill() throws Exception {
        Connection connection = createSecuritiesConnection();
        when(codefApiClient.requestProduct(anyString(), argThat(p -> p != null && connection.connectedId().equals(p.get("connectedId")))))
                .thenReturn(e2eFixture("failure.json"));

        publishLinked(connection.id());

        awaitUntil(() -> jobs(connection.id()).stream().anyMatch(j -> j.getStatus() == SyncJobStatus.FAILED), WAIT);
        awaitExecutorIdle();
        verify(backfillService, never()).backfillHoldings(connection.assetId());
    }

    @Test
    @DisplayName("CODEF 조회 결과 없음(EMPTY_RESULT, sync_jobs는 SUCCESS) → 소급 호출 없음")
    void emptyResult_noBackfill() throws Exception {
        Connection connection = createSecuritiesConnection();
        when(codefApiClient.requestProduct(anyString(), argThat(p -> p != null && connection.connectedId().equals(p.get("connectedId")))))
                .thenReturn(EMPTY_RESULT);

        publishLinked(connection.id());

        awaitUntil(() -> jobs(connection.id()).stream().anyMatch(j -> j.getStatus() == SyncJobStatus.SUCCESS), WAIT);
        awaitExecutorIdle();
        verify(backfillService, never()).backfillHoldings(connection.assetId());
    }

    @Test
    @DisplayName("동기화 락 충돌(다른 수집 진행 중) → 수집 · 소급 모두 없음")
    void lockConflict_noBackfill() throws Exception {
        Connection connection = createSecuritiesConnection();
        String lockKey = "codef:lock:" + connection.id();
        redis.opsForValue().set(lockKey, "other-sync", Duration.ofMinutes(1));
        try {
            publishLinked(connection.id());

            Thread.sleep(1500);
            awaitExecutorIdle();
            assertThat(jobs(connection.id())).isEmpty();
            verify(backfillService, never()).backfillHoldings(connection.assetId());
        } finally {
            redis.delete(lockKey);
        }
    }

    @Test
    @DisplayName("소급이 예외를 던져도 연동 상태 · 보유 종목 · sync_jobs(BACKFILL SUCCESS)는 유지된다")
    void backfillThrows_syncResultKept() throws Exception {
        Connection connection = createSecuritiesConnection();
        doThrow(new IllegalStateException("테스트 전용 소급 실패")).when(backfillService).backfillHoldings(connection.assetId());

        publishLinked(connection.id());

        awaitUntil(() -> jobs(connection.id()).stream().anyMatch(j -> j.getStatus() == SyncJobStatus.SUCCESS), WAIT);
        awaitExecutorIdle();
        verify(backfillService).backfillHoldings(connection.assetId());
        assertThat(connectedAccountRepository.findById(connection.id()).orElseThrow().getStatus())
                .isEqualTo(ConnectedAccountStatus.ACTIVE);
        assertThat(holdingTickers(connection.assetId())).hasSize(2);
        SyncJob job = jobs(connection.id()).get(0);
        assertThat(job.getJobType()).isEqualTo(SyncJobType.BACKFILL);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(job.getCollectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재연결 이벤트를 다시 받아도 stock_prices 중복 행 없음 (ticker, trade_date upsert)")
    void relinked_noDuplicateRows() throws Exception {
        Connection connection = createSecuritiesConnection();

        publishLinked(connection.id());
        awaitUntil(() -> backfillsDone.get() == 1, WAIT);
        publishLinked(connection.id());
        awaitUntil(() -> backfillsDone.get() == 2, WAIT);

        assertThat(jobs(connection.id())).hasSize(2);
        for (String ticker : holdingTickers(connection.assetId())) {
            assertThat(priceRows(ticker)).isEqualTo(4);
        }
    }

    // ---------- 과거 이벤트 소급 (A5, FR-DT-08) ----------

    // 이벤트 소급(실제 실행)이 끝난 횟수 — 비동기 완료 대기용. 기존 setUp은 바꾸지 않고 테스트마다 건다.
    // 스파이는 트랜잭션 프록시 안쪽이라 이 카운터는 커밋 전에 오른다 → 결과를 읽기 전에 awaitExecutorIdle()로 커밋까지 기다린다
    private void countEventBackfills() {
        doAnswer(inv -> {
            try {
                return inv.callRealMethod();
            } finally {
                eventBackfillsDone.incrementAndGet();
            }
        }).when(eventBackfillService).backfill(anyLong(), anyBoolean());
    }

    // days(5)와 같고, LATEST − 1일만 +6% (모든 종목) — 소급되는 4행 중 1일이 임계치 초과
    private static List<KisDailyPrice> daysWithMove() {
        List<KisDailyPrice> rows = new ArrayList<>(days(5));
        KisDailyPrice moved = rows.get(1);
        rows.set(1, new KisDailyPrice(moved.tradeDate(), moved.closePrice(), BigDecimal.ONE, "2",
                new BigDecimal("6.00"), 100L, null, null));
        return rows;
    }

    private int userEvents(long assetId) {
        return jdbc.queryForObject("""
                select count(*) from user_market_events e join assets a on a.user_id = e.user_id
                where a.id = ? and e.is_backfilled = true""", Integer.class, assetId);
    }

    private boolean backfillCompleted(long assetId) {
        List<Boolean> flags = jdbc.queryForList("""
                select s.backfill_completed from user_settings s join assets a on a.user_id = s.user_id where a.id = ?""",
                Boolean.class, assetId);
        return !flags.isEmpty() && flags.get(0);
    }

    @Test
    @DisplayName("이벤트 소급: 연동 → 수집 → 시세 소급 → 이벤트 소급 순서, 보유 2종목 × 초과 1일 = 2건, 감지 기록 0, 완료 표시")
    void eventBackfill_afterPriceBackfill() throws Exception {
        when(kis.getDailyPrices(anyString())).thenReturn(daysWithMove());
        countEventBackfills();
        Connection connection = createSecuritiesConnection();

        publishLinked(connection.id());

        awaitUntil(() -> eventBackfillsDone.get() == 1, WAIT);
        awaitExecutorIdle();
        InOrder order = inOrder(backfillService, eventBackfillService);
        order.verify(backfillService).backfillHoldings(connection.assetId());
        order.verify(eventBackfillService).backfill(connection.assetId(), true);
        assertThat(holdingTickers(connection.assetId())).hasSize(2);
        assertThat(userEvents(connection.assetId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from detection_events d join assets a on a.user_id = d.user_id where a.id = ?""",
                Integer.class, connection.assetId())).isZero();
        assertThat(backfillCompleted(connection.assetId())).isTrue();
    }

    @Test
    @DisplayName("이벤트 소급: 시세 소급에 실패한 종목이 있으면 남은 종목만 소급하고 완료 표시는 하지 않는다")
    void eventBackfill_priceFailure_flagNotSet() throws Exception {
        when(kis.getDailyPrices(anyString())).thenAnswer(inv -> {
            if ("000660".equals(inv.getArgument(0))) {
                throw new IllegalStateException("테스트 전용 시세 실패");
            }
            return daysWithMove();
        });
        countEventBackfills();
        Connection connection = createSecuritiesConnection();

        publishLinked(connection.id());

        awaitUntil(() -> eventBackfillsDone.get() == 1, WAIT);
        awaitExecutorIdle();
        verify(eventBackfillService).backfill(connection.assetId(), false);
        assertThat(backfillCompleted(connection.assetId())).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from user_market_events e join stock_event_analyses s on s.id = e.analysis_id
                join assets a on a.user_id = e.user_id where a.id = ? and s.ticker = '005930'""",
                Integer.class, connection.assetId())).isEqualTo(1);
    }

    @Test
    @DisplayName("이벤트 소급이 예외를 던져도 연동 상태 · 보유 종목 · 수집 결과 · 시세는 유지되고 완료 표시는 없다")
    void eventBackfillThrows_othersKept() throws Exception {
        when(kis.getDailyPrices(anyString())).thenReturn(daysWithMove());
        Connection connection = createSecuritiesConnection();
        doAnswer(inv -> {
            eventBackfillsDone.incrementAndGet();
            throw new IllegalStateException("테스트 전용 이벤트 소급 실패");
        }).when(eventBackfillService).backfill(eq(connection.assetId()), anyBoolean());

        publishLinked(connection.id());

        awaitUntil(() -> eventBackfillsDone.get() == 1, WAIT);
        awaitExecutorIdle();
        assertThat(connectedAccountRepository.findById(connection.id()).orElseThrow().getStatus())
                .isEqualTo(ConnectedAccountStatus.ACTIVE);
        assertThat(jobs(connection.id())).singleElement()
                .satisfies(job -> assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS));
        for (String ticker : holdingTickers(connection.assetId())) {
            assertThat(priceRows(ticker)).isEqualTo(4);
        }
        assertThat(userEvents(connection.assetId())).isZero();
        assertThat(backfillCompleted(connection.assetId())).isFalse();
    }

    @Test
    @DisplayName("이벤트 소급: 수집 실패 · 조회 결과 없음(EMPTY_RESULT)이면 이벤트 소급도 하지 않는다")
    void eventBackfill_notRunWhenSyncFailsOrEmpty() throws Exception {
        Connection failed = createSecuritiesConnection();
        Connection empty = createSecuritiesConnection();
        when(codefApiClient.requestProduct(anyString(), argThat(p -> p != null && failed.connectedId().equals(p.get("connectedId")))))
                .thenReturn(e2eFixture("failure.json"));
        when(codefApiClient.requestProduct(anyString(), argThat(p -> p != null && empty.connectedId().equals(p.get("connectedId")))))
                .thenReturn(EMPTY_RESULT);

        publishLinked(failed.id());
        publishLinked(empty.id());

        awaitUntil(() -> jobs(failed.id()).stream().anyMatch(j -> j.getStatus() == SyncJobStatus.FAILED)
                && !jobs(empty.id()).isEmpty(), WAIT);
        awaitExecutorIdle();
        verify(eventBackfillService, never()).backfill(eq(failed.assetId()), anyBoolean());
        verify(eventBackfillService, never()).backfill(eq(empty.assetId()), anyBoolean());
    }

    @Test
    @DisplayName("이벤트 소급: 두 번째 계좌를 연동하면 그 계좌 종목이 추가로 소급되고 완료 표시는 true 유지")
    void eventBackfill_secondAccount() throws Exception {
        when(kis.getDailyPrices(anyString())).thenReturn(daysWithMove());
        countEventBackfills();
        Connection first = createSecuritiesConnection();
        publishLinked(first.id());
        awaitUntil(() -> eventBackfillsDone.get() == 1, WAIT);
        awaitExecutorIdle();
        assertThat(userEvents(first.assetId())).isEqualTo(2);           // 005930 · 000660

        // 같은 사용자의 두 번째 증권 계좌 — 005930 · 069500 · 111111 보유 (005930은 이미 같은 날 이벤트가 있어 건너뜀)
        Long userId = jdbc.queryForObject("select user_id from assets where id = ?", Long.class, first.assetId());
        String connectedId = "cid-backfill-2-" + UUID.randomUUID();
        Long[] ids = new Long[2];
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.getReferenceById(userId);
            var institution = institutionRepository.findByCodefOrgCode("0218").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
            ids[0] = account.getId();
            ids[1] = assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
        });
        when(codefApiClient.requestProduct(anyString(), argThat(p -> p != null && connectedId.equals(p.get("connectedId")))))
                .thenReturn(e2eFixture("portfolio-success.json"));

        publishLinked(ids[0]);
        awaitUntil(() -> eventBackfillsDone.get() == 2, WAIT);
        awaitExecutorIdle();

        assertThat(userEvents(first.assetId())).isEqualTo(4);           // + 069500 · 111111
        assertThat(backfillCompleted(first.assetId())).isTrue();
    }

    // ---------- helpers ----------

    private record Connection(long id, long assetId, String connectedId) {}

    // 수집 executor가 빌 때까지 — "호출 없음"을 단언하기 전에 비동기 작업이 끝났는지 확인한다
    private void awaitExecutorIdle() throws InterruptedException {
        ThreadPoolExecutor pool = codefSyncExecutor.getThreadPoolExecutor();
        awaitUntil(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), WAIT);
    }

    private void publishLinked(long connectedAccountId) {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new ConnectedAccountLinkedEvent(connectedAccountId, BusinessType.ST)));
    }

    private Connection createSecuritiesConnection() throws Exception {
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
        String connectedId = "cid-backfill-" + UUID.randomUUID();
        Long[] ids = new Long[2];
        transactionTemplate.executeWithoutResult(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
            ids[0] = account.getId();
            ids[1] = assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
        });
        return new Connection(ids[0], ids[1], connectedId);
    }

    // 최신 거래일(LATEST)부터 count개 — 한투 응답 순서(최신이 먼저). 테스트 전용 더미 시세
    private static List<KisDailyPrice> days(int count) {
        List<KisDailyPrice> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new KisDailyPrice(LATEST.minusDays(i), BigDecimal.valueOf(10000 + i), BigDecimal.ONE, "2",
                    new BigDecimal("0.01"), 100L, null, null));
        }
        return rows;
    }

    private int priceRows(String ticker) {
        return jdbc.queryForObject("select count(*) from stock_prices where ticker = ? and trade_date between ? and ?",
                Integer.class, ticker, LATEST.minusDays(10), LATEST);
    }

    private List<String> holdingTickers(long assetId) {
        return transactionTemplate.execute(status ->
                holdingRepository.findAllByAssetId(assetId).stream().map(h -> h.getTicker()).toList());
    }

    private List<SyncJob> jobs(long connectedAccountId) {
        return syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/codef/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String e2eFixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/e2e/codef/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
