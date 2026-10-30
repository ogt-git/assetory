package com.project.assetory.e2e;

import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.service.StockSyncService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.TradingDayStatus;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.service.DailyAssetBatchService;
import com.project.assetory.snapshot.service.DailyAssetBatchService.BatchSummary;
import com.project.assetory.snapshot.service.SnapshotSaveService;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.project.assetory.e2e.FakeKisServer.Day;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일일 자산 기록 배치 E2E — 키 · 실제 계좌 없이 전체 흐름을 검증한다 (6주차 종합 점검 E1~E6 · SQL · 지표의 자동화).
 * - 한투: 가짜 HTTP 서버(FakeKisServer) — 실제 KisApiClient · 토큰 Redis 저장 · 메트릭을 그대로 거친다
 * - CODEF: CodefApiClient 목 + 시나리오 응답 JSON (src/test/resources/e2e/codef)
 * - 데이터: E2eFixture (U1~U5, 모두 테스트 전용 더미 값)
 * - 시각: 미래 월요일 16:00 고정 (모든 연동이 기준 시각 T 이전에 생성된 것이 된다)
 * 실제 종가 일치 · 예수금 D+0/D+2 · 거래정지 실코드 확인은 kisLiveTest와 실계좌 점검에 남는다.
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ExtendWith(OutputCaptureExtension.class)
class DailyAssetE2eTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);   // 월 — 거래일
    private static final LocalDate PREV = LocalDate.of(2030, 1, 4);  // 금 — 직전 거래일
    private static final LocalDate NEXT = LocalDate.of(2030, 1, 8);  // 화 — 시세상 휴장으로 설정
    private static final String STOCK_URL = "/v1/kr/stock/a/account/financial-assets";
    // 테스트 전용 더미 키
    private static final String APP_KEY = "e2e-test-only-app-key";
    private static final String APP_SECRET = "e2e-test-only-app-secret";

    private static final FakeKisServer KIS = new FakeKisServer();

    @DynamicPropertySource
    static void kisProperties(DynamicPropertyRegistry registry) {
        registry.add("assetory.kis.base-url", KIS::baseUrl);
        registry.add("assetory.kis.app-key", () -> APP_KEY);
        registry.add("assetory.kis.app-secret", () -> APP_SECRET);
        registry.add("assetory.kis.call-interval-ms", () -> "0");
        registry.add("assetory.kis.rate-limit.backoff-ms", () -> "0");
        registry.add("assetory.sync.batch.lock-retry-wait-ms", () -> "0");
    }

    @AfterAll
    static void stopKis() {
        KIS.stop();
    }

    @MockitoBean Clock clock;

    @LocalServerPort int serverPort;
    @LocalManagementPort int managementPort;
    @Autowired TestRestTemplate rest;
    @Autowired DailyAssetBatchService batch;
    @Autowired SnapshotSaveService snapshotSaveService;
    @Autowired StockSyncService stockSyncService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired DepositDetailRepository depositDetailRepository;
    @Autowired StockPriceRepository stockPriceRepository;

    // connectedId → CODEF 응답 (시나리오 중 바꿀 수 있다)
    private final Map<String, String> codef = new ConcurrentHashMap<>();
    private E2eFixture.Seeded seeded;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        redis.delete("kis:access-token");
        KIS.reset();
        KIS.prices("005930", new Day(DAY, new BigDecimal("71200")), new Day(PREV, new BigDecimal("72000")));
        KIS.prices("069500", new Day(DAY, new BigDecimal("35000")), new Day(PREV, new BigDecimal("34800")));
        KIS.prices("000660", new Day(DAY, new BigDecimal("210000")), new Day(PREV, new BigDecimal("200000")));
        KIS.prices("111111", new Day(PREV, new BigDecimal("12000")));
        KIS.stockInfo("069500", "EF", false);
        KIS.stockInfo("111111", "ST", true);

        codef.clear();
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-test-only");
        when(codefApiClient.requestProduct(eq(STOCK_URL), any())).thenAnswer(inv -> {
            Map<String, Object> params = inv.getArgument(1);
            return codef.getOrDefault((String) params.get("connectedId"), json("failure.json"));
        });

        E2eFixture fixture = new E2eFixture(this::newUser, userRepository, institutionRepository,
                connectedAccountRepository, assetRepository, depositDetailRepository, stockPriceRepository, tx);
        seeded = fixture.seed();
        fixture.yesterdayPrice("111111", "정지종목", PREV, "12000");
        codef.put(seeded.u1().connectedId(), json("portfolio-success.json"));

        // U4 — 어제 정기 수집이 저장해 둔 값(오늘 실패하면 이 값이 쓰이면 안 된다)
        codef.put(seeded.u4().connectedId(), json("yesterday-holdings.json"));
        stockSyncService.sync(seeded.u4().accountId(), SyncJobType.HOLDING);
        codef.put(seeded.u4().connectedId(), json("failure.json"));

        // U2 — 직전 정기 기록(PREV COMPLETE 280,000)
        snapshotSaveService.save(seeded.u2(), new SnapshotInput(PREV, PREV.atTime(16, 5), List.of(),
                List.of(new Manual(seeded.u2CashId(), AssetType.CASH, new BigDecimal("280000")))));
        // U1 — 직전 정기 기록(PREV COMPLETE 4,686,000, 종목 행 수량 · 단가 포함)
        fixture.u1PreviousRecord(snapshotSaveService, seeded, PREV);

        at(DAY.atTime(16, 0));
    }

    // ================= E1 · E2 =================

    @Test
    @DisplayName("E1 정상 영업일 배치 + E2 같은 날 재실행 — SQL 점검 · 토큰 재사용 · 지표 · 로그 민감정보")
    void e1_normalDay_e2_rerun(CapturedOutput output) {
        BatchSummary first = batch.run();

        assertThat(first.tradingDay()).isEqualTo(TradingDayStatus.TRADING_DAY);
        assertThat(first.userErrors()).isZero();
        assertSnapshots();
        assertStockPrices();
        assertSecurityTypes();
        assertDetails();
        assertSyncJobs();
        assertThat(KIS.tokenRequests()).isEqualTo(1);
        assertTokenTtl();

        // ---- E2 재실행 ----
        Map<String, Object> before = counts();
        BatchSummary second = batch.run();

        assertThat(second.userErrors()).isZero();
        assertThat(counts()).isEqualTo(before);                      // 행 수 변화 없음
        assertSnapshots();                                           // 상태 · 금액 그대로 (COMPLETE 유지)
        assertThat(KIS.tokenRequests()).isEqualTo(1);                 // Redis 토큰 재사용 — 재발급 없음

        assertMetrics();
        // 로그에 키 · 토큰 · 계좌번호 · connectedId가 남지 않는다 (NFR-SE-07)
        assertThat(output.getAll())
                .doesNotContain(APP_KEY).doesNotContain(APP_SECRET).doesNotContain(FakeKisServer.TOKEN)
                .doesNotContain(E2eFixture.ACCOUNT_NUMBER)
                .doesNotContain(seeded.u1().connectedId()).doesNotContain(seeded.u4().connectedId());
    }

    // ================= E3 ~ E6 =================

    @Test
    @DisplayName("E3 장 마감 전 실행(일반일 15:00) → 중단: 한투 · CODEF 호출 0, 스냅샷 0, ERROR 로그")
    void e3_beforeClose(CapturedOutput output) throws Exception {
        at(DAY.atTime(15, 0));

        BatchSummary summary = batch.run();

        assertThat(summary.tradingDay()).isEqualTo(TradingDayStatus.UNKNOWN);
        assertNoCollection();
        // 로그의 대시(—)는 Windows 콘솔 인코딩에서 ?로 찍혀 앞뒤 문구로 확인한다
        assertThat(output.getAll()).contains("[DailyBatch] 장 마감 가드 전 실행").contains("이번 실행 전체 중단 (cron 설정 확인 필요)");
    }

    @Test
    @DisplayName("E4 휴장일 → 거래일 판정 호출 1회 외 외부 호출 0, 스냅샷 0")
    void e4_holiday() throws Exception {
        at(NEXT.atTime(16, 0)); // 기준 종목 최신 거래일이 DAY(월)라 화요일은 휴장으로 판정

        BatchSummary summary = batch.run();

        assertThat(summary.tradingDay()).isEqualTo(TradingDayStatus.HOLIDAY);
        assertThat(KIS.requests("daily-price")).isEqualTo(1);
        assertThat(KIS.requests("stock-info")).isZero();
        verify(codefApiClient, never()).requestProduct(eq(STOCK_URL),
                org.mockito.ArgumentMatchers.argThat(p -> seeded.u1().connectedId().equals(p.get("connectedId"))));
        assertThat(snapshotCount(NEXT)).isZero();
    }

    @Test
    @DisplayName("E5 한투 장애(연결 끊김) → UNKNOWN 중단, 휴장으로 처리하지 않음, ERROR 로그")
    void e5_kisDown(CapturedOutput output) throws Exception {
        KIS.mode(FakeKisServer.Mode.NETWORK_DOWN);

        BatchSummary summary = batch.run();

        assertThat(summary.tradingDay()).isEqualTo(TradingDayStatus.UNKNOWN);
        assertNoCollection();
        assertThat(output.getAll()).contains("[DailyBatch] 거래일 판정 불가").contains("이번 실행 전체 중단 (휴장으로 처리하지 않음)");
    }

    @Test
    @DisplayName("E6 한투 키 오류 → 토큰 발급 1회 실패, 재시도 없음, 중단")
    void e6_badKey(CapturedOutput output) throws Exception {
        KIS.mode(FakeKisServer.Mode.BAD_KEY);

        BatchSummary summary = batch.run();

        assertThat(summary.tradingDay()).isEqualTo(TradingDayStatus.UNKNOWN);
        assertThat(KIS.tokenRequests()).isEqualTo(1);
        assertThat(KIS.requests("daily-price")).isZero();
        assertThat(redis.hasKey("kis:access-token")).isFalse();
        assertNoCollection();
        assertThat(output.getAll()).contains("토큰 발급 실패").doesNotContain(APP_KEY).doesNotContain(APP_SECRET);
    }

    // ================= 점검 단언 =================

    // asset_snapshots — 사용자별 상태 · 금액 · 전일 대비 · total = 자산군 합 · 시장가격 영향 / 기타 자산 변화
    private void assertSnapshots() {
        Map<String, Object> u1 = snapshot(seeded.u1().userId());
        assertThat(u1.get("status")).isEqualTo("COMPLETE");
        assertThat((BigDecimal) u1.get("securities_amount")).isEqualByComparingTo("923000"); // 71,200×10 + 35,000×5 + 12,000×3(거래정지 직전 종가)
        assertThat((BigDecimal) u1.get("cash_amount")).isEqualByComparingTo("1500000");      // 예수금 1,000,000 + CASH 500,000
        assertThat((BigDecimal) u1.get("deposit_amount")).isEqualByComparingTo("2000000");
        assertThat((BigDecimal) u1.get("total_asset")).isEqualByComparingTo("4423000");
        assertThat(u1.get("base_snapshot_date").toString()).isEqualTo(PREV.toString());
        assertThat((BigDecimal) u1.get("day_change_amount")).isEqualByComparingTo("-263000");   // 4,423,000 − 4,686,000
        assertThat((BigDecimal) u1.get("day_change_rate")).isEqualByComparingTo("-5.6125");
        // 시장가격 영향 (05 §4.7): 005930 min(8,10)×(71,200−72,000) −6,400 + 069500 5×(35,000−34,800) +1,000
        //   + 111111 거래정지 0 + 000660 전량 매도 0 = −5,400 / 기타 = −263,000 − (−5,400)
        assertThat((BigDecimal) u1.get("market_change_amount")).isEqualByComparingTo("-5400");
        assertThat((BigDecimal) u1.get("other_change_amount")).isEqualByComparingTo("-257600");

        Map<String, Object> u2 = snapshot(seeded.u2());
        assertThat(u2.get("status")).isEqualTo("COMPLETE");
        assertThat((BigDecimal) u2.get("securities_amount")).isEqualByComparingTo("0");       // 증권 대상 없음 → 0
        assertThat(u2.get("base_snapshot_date").toString()).isEqualTo(PREV.toString());
        assertThat((BigDecimal) u2.get("day_change_amount")).isEqualByComparingTo("20000");
        assertThat((BigDecimal) u2.get("day_change_rate")).isEqualByComparingTo("7.1429");
        assertThat((BigDecimal) u2.get("market_change_amount")).isEqualByComparingTo("0");     // 종목 없음 → 정상 0
        assertThat((BigDecimal) u2.get("other_change_amount")).isEqualByComparingTo("20000");

        Map<String, Object> u3 = snapshot(seeded.u3().userId());
        assertThat(u3.get("status")).isEqualTo("PARTIAL");
        assertThat(u3.get("securities_amount")).isNull();
        assertThat((BigDecimal) u3.get("cash_amount")).isEqualByComparingTo("100000");

        Map<String, Object> u4 = snapshot(seeded.u4().userId());
        assertThat(u4.get("status")).isEqualTo("PARTIAL");
        assertThat(u4.get("securities_amount")).isNull();                                     // 어제 000660 평가액 미사용
        assertThat((BigDecimal) u4.get("cash_amount")).isEqualByComparingTo("50000");         // 어제 예수금 999,999 미사용

        assertThat(snapshotCount(DAY)).isEqualTo(4);                                          // U5(해지만)는 행 없음
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where snapshot_date = ? and total_asset <> "
                + "coalesce(securities_amount,0) + coalesce(cash_amount,0) + coalesce(deposit_amount,0)", Integer.class, DAY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where snapshot_date = ? and status <> 'COMPLETE' "
                + "and (base_snapshot_date is not null or day_change_amount is not null or day_change_rate is not null)", Integer.class, DAY)).isZero();
        // 시장가격 영향 · 기타 자산 변화 (01 §14 #1 · #2 확정, 05 §4.7): COMPLETE + 기준 있음 → 둘 다 값, 그 외 → 둘 다 NULL
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where snapshot_date = ? and status = 'COMPLETE' "
                + "and base_snapshot_date is not null and (market_change_amount is null or other_change_amount is null)",
                Integer.class, DAY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where (status <> 'COMPLETE' or base_snapshot_date is null) "
                + "and (market_change_amount is not null or other_change_amount is not null)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where market_change_amount is not null "
                + "and other_change_amount <> day_change_amount - market_change_amount", Integer.class)).isZero();
    }

    // stock_prices — 보유 합집합 중 거래정지 제외 행, 변동률 부호, 직전 종가
    private void assertStockPrices() {
        assertThat(jdbc.queryForList("select ticker from stock_prices where trade_date = ? order by ticker", String.class, DAY))
                .containsExactly("000660", "005930", "069500");                                // 111111은 거래정지 → 행 없음
        Map<String, Object> samsung = jdbc.queryForMap(
                "select close_price, prev_close_price, change_rate from stock_prices where ticker = '005930' and trade_date = ?", DAY);
        assertThat((BigDecimal) samsung.get("close_price")).isEqualByComparingTo("71200");
        assertThat((BigDecimal) samsung.get("prev_close_price")).isEqualByComparingTo("72000");
        assertThat((BigDecimal) samsung.get("change_rate")).isEqualByComparingTo("-1.11");
    }

    // holdings.security_type — EF → ETF, ST → STOCK
    private void assertSecurityTypes() {
        assertThat(jdbc.queryForList("select distinct ticker, security_type from holdings where ticker in "
                + "('005930','069500','111111','000660') order by ticker"))
                .extracting(r -> r.get("ticker") + "=" + r.get("security_type"))
                .containsExactly("000660=STOCK", "005930=STOCK", "069500=ETF", "111111=STOCK");
    }

    // asset_detail_snapshots — 실패 자산 행 없음, ticker NULL 행 중복 없음, detail 합 = 자산군 금액
    private void assertDetails() {
        assertThat(jdbc.queryForObject("select count(*) from asset_detail_snapshots where asset_id = ? and snapshot_date = ?",
                Integer.class, seeded.u4().securitiesAssetId(), DAY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_detail_snapshots where asset_id = ? and snapshot_date = ?",
                Integer.class, seeded.u3().securitiesAssetId(), DAY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from (select asset_id from asset_detail_snapshots "
                + "where ticker is null group by asset_id, snapshot_date having count(*) > 1) d", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select sum(amount) from asset_detail_snapshots where asset_id = ? and snapshot_date = ? "
                + "and ticker is not null", BigDecimal.class, seeded.u1().securitiesAssetId(), DAY)).isEqualByComparingTo("923000");
        assertThat(jdbc.queryForObject("select day_change_amount from asset_detail_snapshots where asset_id = ? and snapshot_date = ?",
                BigDecimal.class, seeded.u2CashId(), DAY)).isEqualByComparingTo("20000");
    }

    // sync_jobs — U1 SUCCESS, U4 FAILED(오늘), U3(AUTH_FAILED)는 호출 없음
    private void assertSyncJobs() {
        assertThat(lastJobStatus(seeded.u1().accountId())).isEqualTo("SUCCESS");
        assertThat(lastJobStatus(seeded.u4().accountId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from sync_jobs where connected_account_id = ?",
                Integer.class, seeded.u3().accountId())).isZero();
    }

    // Redis kis:access-token TTL = expires_in − 여유(10분), 다른 kis:* 키 없음
    private void assertTokenTtl() {
        Long ttl = redis.getExpire("kis:access-token");
        assertThat(ttl).isBetween(FakeKisServer.TOKEN_EXPIRES_IN - 600 - 60, FakeKisServer.TOKEN_EXPIRES_IN - 600);
        assertThat(redis.keys("kis:*")).containsExactly("kis:access-token");
    }

    // 관리 포트 지표 — 이름 · 태그, 종목코드 · 사용자 · 키가 태그에 없음. API 포트에는 actuator 없음
    private void assertMetrics() {
        String body = rest.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class).getBody();
        assertThat(body)
                .contains("external_api_calls_total{api=\"daily-price\"")
                .contains("external_api_calls_total{api=\"token\"")
                .contains("external_api_latency_seconds_count{api=\"stock-info\"")
                .contains("market_price_collect_total{")
                .contains("result=\"halted\"")
                .contains("snapshot_status_total{")
                .contains("batch_step_duration_seconds_count{")
                .contains("batch_run_total{")
                .contains("http_server_requests_seconds");
        assertThat(body.lines().filter(l -> l.matches("^(external_api|market_price|snapshot_status|batch_).*")))
                .isNotEmpty()
                .noneMatch(l -> l.contains("005930") || l.contains("069500") || l.contains("user")
                        || l.contains(APP_KEY) || l.contains(FakeKisServer.TOKEN));
        assertThat(rest.getForEntity("http://localhost:" + serverPort + "/actuator/prometheus", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
    }

    private void assertNoCollection() throws Exception {
        assertThat(KIS.requests("stock-info")).isZero();
        verify(codefApiClient, never()).requestProduct(eq(STOCK_URL),
                org.mockito.ArgumentMatchers.argThat(p -> seeded.u1().connectedId().equals(p.get("connectedId"))));
        assertThat(snapshotCount(DAY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stock_prices where trade_date = ?", Integer.class, DAY)).isZero();
    }

    // ================= helpers =================

    private void at(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);
    }

    private User newUser() {
        try {
            return userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> snapshot(Long userId) {
        return jdbc.queryForMap("select status, total_asset, securities_amount, cash_amount, deposit_amount, "
                + "base_snapshot_date, day_change_amount, day_change_rate, market_change_amount, other_change_amount from asset_snapshots where user_id = ? and snapshot_date = ?",
                userId, DAY);
    }

    private int snapshotCount(LocalDate date) {
        return jdbc.queryForObject("select count(*) from asset_snapshots where snapshot_date = ?", Integer.class, date);
    }

    private String lastJobStatus(Long accountId) {
        return jdbc.queryForObject("select status from sync_jobs where connected_account_id = ? order by id desc limit 1",
                String.class, accountId);
    }

    private Map<String, Object> counts() {
        return Map.of(
                "snapshots", jdbc.queryForObject("select count(*) from asset_snapshots", Integer.class),
                "details", jdbc.queryForObject("select count(*) from asset_detail_snapshots", Integer.class),
                "prices", jdbc.queryForObject("select count(*) from stock_prices", Integer.class),
                "statuses", jdbc.queryForList("select concat(user_id, ':', status) from asset_snapshots where snapshot_date = ? "
                        + "order by user_id", String.class, DAY));
    }

    // 이전 테스트 데이터를 배치 대상에서 뺀다 — 배치는 전체 연동 · 수기 자산을 대상으로 한다
    private void cleanDatabase() {
        tx.executeWithoutResult(status -> {
            entityManager.createQuery("delete from AssetDetailSnapshot").executeUpdate();
            entityManager.createQuery("delete from AssetSnapshot").executeUpdate();
            entityManager.createQuery("delete from StockPrice").executeUpdate();
            entityManager.createQuery("delete from Holding").executeUpdate();
            entityManager.createQuery("update ConnectedAccount c set c.status = :s")
                    .setParameter("s", ConnectedAccountStatus.DISCONNECTED).executeUpdate();
            entityManager.createQuery("update Asset a set a.active = false").executeUpdate();
        });
    }

    private static String json(String name) {
        try {
            return new ClassPathResource("e2e/codef/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
