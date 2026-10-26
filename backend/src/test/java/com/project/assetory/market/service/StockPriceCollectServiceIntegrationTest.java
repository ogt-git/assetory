package com.project.assetory.market.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.kis.KisFixtures;
import com.project.assetory.market.kis.KisStockInfo;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.StockPriceCollectResult.FailReason;
import com.project.assetory.market.service.StockPriceCollectResult.TickerStatus;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 보유 종목 시세 수집 (FR-MK-01 · 05 · 07, stock_prices) + STOCK/ETF 판별 (FR-CN-03, holdings.security_type)
class StockPriceCollectServiceIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12); // daily-price-success.json 최신 거래일
    private static final LocalDate SPLIT_DAY = LocalDate.of(2018, 5, 4);

    @MockitoBean KisApiClient kis;
    @MockitoBean Clock clock;

    @Autowired StockPriceCollectService collectService;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private Long assetId;

    @BeforeEach
    void setUp() throws Exception {
        // 종목 합집합은 전체 활성 보유 종목이라 이전 테스트의 종목이 섞이지 않게 비운다
        holdingRepository.deleteAll();
        stockPriceRepository.deleteAll();
        now(MON.atTime(16, 0));

        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        assetId = transactionTemplate.execute(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-price-" + UUID.randomUUID(), BusinessType.ST));
            return assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
        });
    }

    private void now(LocalDateTime time) {
        when(clock.instant()).thenReturn(time.atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);
    }

    private void hold(String ticker, String name, SecurityType type) {
        transactionTemplate.executeWithoutResult(status -> {
            Holding holding = Holding.create(assetRepository.findById(assetId).orElseThrow(), ticker);
            holding.update(name, type, new BigDecimal("10"), null, null, new BigDecimal("1000"), null, null);
            holdingRepository.save(holding);
        });
    }

    private List<KisDailyPrice> prices(String fixture) {
        return KisFixtures.dailyPrices(fixture);
    }

    private SecurityType typeOf(String ticker) {
        return holdingRepository.findAll().stream().filter(h -> h.getTicker().equals(ticker))
                .findFirst().orElseThrow().getSecurityType();
    }

    @Test
    @DisplayName("장 마감 가드: 오늘을 15:39에 수집하면 호출 없이 전부 실패, 저장 0건")
    void closeGuard_beforeGuardTime() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        now(MON.atTime(15, 39));

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.closeGuardBlocked()).isTrue();
        assertThat(result.tickers().get("005930").status()).isEqualTo(TickerStatus.FAILED);
        assertThat(result.tickers().get("005930").reason()).isEqualTo(FailReason.CLOSE_GUARD);
        assertThat(stockPriceRepository.count()).isZero();
        verify(kis, never()).getDailyPrices(anyString());
    }

    @Test
    @DisplayName("15:40부터는 저장: 종가 · 변동률(prdy_ctrt 그대로, 하락은 음수) · 거래량 · 직전 거래일 종가 · 종목명(holdings)")
    void collect_mapping() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        now(MON.atTime(15, 40));
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices("005930");

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.closeGuardBlocked()).isFalse();
        assertThat(result.tickers().get("005930").status()).isEqualTo(TickerStatus.COLLECTED);
        StockPrice price = stockPriceRepository.findByTickerAndTradeDate("005930", MON).orElseThrow();
        assertThat(price.getStockName()).isEqualTo("삼성전자");
        assertThat(price.getClosePrice()).isEqualByComparingTo("71200");
        assertThat(price.getChangeRate()).isEqualByComparingTo("-1.11");
        assertThat(price.getPrevClosePrice()).isEqualByComparingTo("72000"); // 2026-10-08 행
        assertThat(price.getVolume()).isEqualTo(15_234_567L);
    }

    @Test
    @DisplayName("액면분할일: change_rate는 prdy_ctrt(−2.08%) 그대로 — 종가 ÷ 전일 종가 − 1(≈ −98%)로 계산하지 않는다")
    void splitDay_usesProvidedRate() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        now(SPLIT_DAY.atTime(16, 0));
        doReturn(prices("daily-price-split.json")).when(kis).getDailyPrices("005930");

        collectService.collect(SPLIT_DAY);

        StockPrice price = stockPriceRepository.findByTickerAndTradeDate("005930", SPLIT_DAY).orElseThrow();
        assertThat(price.getChangeRate()).isEqualByComparingTo("-2.08");
        assertThat(price.getClosePrice()).isEqualByComparingTo("51900");
        assertThat(price.getPrevClosePrice()).isEqualByComparingTo("2650000");
    }

    @Test
    @DisplayName("같은 기준 날짜로 2번 수집해도 행은 1개 (UNIQUE ticker, trade_date upsert)")
    void idempotent() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices("005930");

        collectService.collect(MON);
        collectService.collect(MON);

        assertThat(stockPriceRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("기준 날짜 행 없음 + tr_stop_yn=Y → HALTED, =N → FAILED. 두 경우 모두 기준일 행을 만들지 않는다")
    void staleRow_haltedOrFailed() {
        hold("111111", "정지종목", SecurityType.STOCK);
        hold("222222", "누락종목", SecurityType.STOCK);
        List<KisDailyPrice> stale = prices("daily-price-success.json").subList(1, 2); // 최신 2026-10-08
        when(kis.getDailyPrices("111111")).thenReturn(stale);
        when(kis.getDailyPrices("222222")).thenReturn(stale);
        doReturn(KisFixtures.stockInfo("stock-info-halted.json")).when(kis).getStockInfo("111111");
        doReturn(KisFixtures.stockInfo("stock-info-etf.json")).when(kis).getStockInfo("222222"); // tr_stop_yn=N

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("111111").status()).isEqualTo(TickerStatus.HALTED);
        assertThat(result.tickers().get("222222").status()).isEqualTo(TickerStatus.FAILED);
        assertThat(result.tickers().get("222222").reason()).isEqualTo(FailReason.NO_ROW_FOR_DATE);
        assertThat(stockPriceRepository.count()).isZero();
    }

    @Test
    @DisplayName("응답 해석 불가(종가 누락 등) → FAILED, 행 없음 (0으로 채우지 않음)")
    void invalidResponse_failed() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        when(kis.getDailyPrices("005930")).thenThrow(new KisApiException(KisErrorType.INVALID_RESPONSE, null));

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("005930").status()).isEqualTo(TickerStatus.FAILED);
        assertThat(result.tickers().get("005930").reason()).isEqualTo(FailReason.API_ERROR);
        assertThat(stockPriceRepository.count()).isZero();
    }

    @Test
    @DisplayName("직전 거래일 행이 없으면(상장 첫날) FAILED — prev_close_price를 0으로 채우지 않는다")
    void noPreviousRow_failed() {
        hold("005930", "삼성전자", SecurityType.STOCK);
        doReturn(prices("daily-price-success.json").subList(0, 1)).when(kis).getDailyPrices("005930");

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("005930").reason()).isEqualTo(FailReason.NO_PREVIOUS_DAY);
        assertThat(stockPriceRepository.count()).isZero();
    }

    @Test
    @DisplayName("한 종목 실패가 다른 종목 수집을 막지 않는다 (FR-MK-05)")
    void failureIsolated() {
        hold("000001", "실패종목", SecurityType.STOCK);
        hold("005930", "삼성전자", SecurityType.STOCK);
        when(kis.getDailyPrices("000001")).thenThrow(new KisApiException(KisErrorType.API_ERROR, "TEST"));
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices("005930");

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("000001").status()).isEqualTo(TickerStatus.FAILED);
        assertThat(result.tickers().get("005930").status()).isEqualTo(TickerStatus.COLLECTED);
        assertThat(result.aborted()).isFalse();
    }

    @Test
    @DisplayName("호출 실패가 연속 5회면 남은 종목은 호출하지 않고 FAILED(중단) — 판별도 건너뛴다")
    void consecutiveFailures_abort() {
        for (int i = 1; i <= 7; i++) hold("00000" + i, "종목" + i, null);
        when(kis.getDailyPrices(anyString())).thenThrow(new KisApiException(KisErrorType.NETWORK_ERROR, null));

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.aborted()).isTrue();
        verify(kis, times(5)).getDailyPrices(anyString());
        assertThat(result.tickers().get("000006").reason()).isEqualTo(FailReason.ABORTED);
        assertThat(result.tickers().get("000007").reason()).isEqualTo(FailReason.ABORTED);
        assertThat(result.count(TickerStatus.FAILED)).isEqualTo(7);
        verify(kis, never()).getStockInfo(anyString());
    }

    @Test
    @DisplayName("STOCK/ETF 판별: EF → ETF, ST → STOCK, 그 외 → NULL 유지 / 이미 채워진 종목은 호출 안 함")
    void securityType_classification() {
        hold("069500", "KODEX 200", null);
        hold("005930", "삼성전자", null);
        hold("530000", "테스트ETN", null);
        hold("000660", "SK하이닉스", SecurityType.STOCK);
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices(anyString());
        doReturn(KisFixtures.stockInfo("stock-info-etf.json")).when(kis).getStockInfo("069500");
        doReturn(KisFixtures.stockInfo("stock-info-halted.json")).when(kis).getStockInfo("005930"); // ST
        when(kis.getStockInfo("530000")).thenReturn(new KisStockInfo("530000", "테스트ETN", "EN", null, null, "N", "N", null));

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(typeOf("069500")).isEqualTo(SecurityType.ETF);
        assertThat(typeOf("005930")).isEqualTo(SecurityType.STOCK);
        assertThat(typeOf("530000")).isNull();
        verify(kis, never()).getStockInfo("000660");
        assertThat(result.securityTypes().classified()).isEqualTo(2);
        assertThat(result.securityTypes().unknownGroup()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 실행에서 거래정지 확인과 판별이 같은 종목을 보면 stock-info는 1회만 호출")
    void stockInfo_cachedWithinRun() {
        hold("111111", "정지종목", null);
        doReturn(prices("daily-price-success.json").subList(1, 2)).when(kis).getDailyPrices("111111");
        doReturn(KisFixtures.stockInfo("stock-info-halted.json")).when(kis).getStockInfo("111111");

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("111111").status()).isEqualTo(TickerStatus.HALTED);
        assertThat(typeOf("111111")).isEqualTo(SecurityType.STOCK);
        verify(kis, times(1)).getStockInfo("111111");
    }

    @Test
    @DisplayName("판별 실패는 시세 수집 결과에 영향을 주지 않는다")
    void classificationFailure_doesNotAffectPrices() {
        hold("005930", "삼성전자", null);
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices("005930");
        when(kis.getStockInfo("005930")).thenThrow(new KisApiException(KisErrorType.API_ERROR, "TEST"));

        StockPriceCollectResult result = collectService.collect(MON);

        assertThat(result.tickers().get("005930").status()).isEqualTo(TickerStatus.COLLECTED);
        assertThat(result.securityTypes().failed()).isEqualTo(1);
        assertThat(typeOf("005930")).isNull();
    }

    @Test
    @DisplayName("소급(FR-MK-04): 직전 행이 있는 거래일만 저장, 오늘 행은 장 마감 가드 적용")
    void backfill() {
        doReturn(prices("daily-price-success.json")).when(kis).getDailyPrices("005930");

        now(MON.atTime(15, 0));
        assertThat(collectService.backfill("005930", "삼성전자")).isZero(); // 오늘(10-12)은 가드, 10-08은 직전 행 없음

        now(MON.atTime(16, 0));
        assertThat(collectService.backfill("005930", "삼성전자")).isEqualTo(1);
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", MON)).isPresent();
    }
}
