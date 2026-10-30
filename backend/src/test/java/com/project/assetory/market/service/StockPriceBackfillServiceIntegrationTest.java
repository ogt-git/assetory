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
import com.project.assetory.market.exception.KisApiException;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.market.kis.KisDailyPrice;
import com.project.assetory.market.kis.KisErrorType;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.StockPriceBackfillService.BackfillResult;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 보유 종목 시세 소급 (FR-MK-04, 05 §11.1 · §4.9) — 종목 시세만 저장하고 사용자 자산 기록은 만들지 않는다
class StockPriceBackfillServiceIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @MockitoBean KisApiClient kis;
    @MockitoBean Clock clock;

    @Autowired StockPriceBackfillService backfillService;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    private Long userId;
    private Long assetId;

    @BeforeEach
    void setUp() throws Exception {
        stockPriceRepository.deleteAll();
        when(clock.instant()).thenReturn(MON.atTime(16, 0).atZone(KST).toInstant());
        when(clock.getZone()).thenReturn(KST);

        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(signupNew())).orElseThrow();
        userId = user.getId();
        assetId = transactionTemplate.execute(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-backfill-" + UUID.randomUUID(), BusinessType.ST));
            return assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
        });
    }

    private void hold(String ticker, String name) {
        transactionTemplate.executeWithoutResult(status -> {
            Holding holding = Holding.create(assetRepository.findById(assetId).orElseThrow(), ticker);
            holding.update(name, SecurityType.STOCK, new BigDecimal("10"), null, null, new BigDecimal("1000"), null, null);
            holdingRepository.save(holding);
        });
    }

    // 최신 거래일(MON)부터 days개 — 한투 일자별 응답 순서(최신이 먼저). 테스트 전용 더미 시세
    private static List<KisDailyPrice> days(int count, String close) {
        List<KisDailyPrice> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new KisDailyPrice(MON.minusDays(i), new BigDecimal(close).add(BigDecimal.valueOf(i)),
                    BigDecimal.ONE, "2", new BigDecimal("0.10"), 1000L, null, null));
        }
        return rows;
    }

    private int priceRows(String ticker) {
        return jdbc.queryForObject("select count(*) from stock_prices where ticker = ?", Integer.class, ticker);
    }

    @Test
    @DisplayName("보유 2종목 → 응답 거래일 행 전부 저장 (가장 오래된 날은 직전 행이 없어 제외)")
    void backfill_allRowsExceptOldest() {
        hold("005930", "삼성전자");
        hold("069500", "KODEX 200");
        doReturn(days(5, "70000")).when(kis).getDailyPrices("005930");
        doReturn(days(3, "35000")).when(kis).getDailyPrices("069500");

        BackfillResult result = backfillService.backfillHoldings(assetId);

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(priceRows("005930")).isEqualTo(4);
        assertThat(priceRows("069500")).isEqualTo(2);
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", MON.minusDays(4))).isEmpty(); // 가장 오래된 날
        assertThat(stockPriceRepository.findByTickerAndTradeDate("005930", MON).orElseThrow().getPrevClosePrice())
                .isEqualByComparingTo("70001");
    }

    @Test
    @DisplayName("종목 단위 실패 격리 (FR-MK-05): 한 종목 호출 실패 → 다른 종목은 저장")
    void oneTickerFails_othersSaved() {
        hold("005930", "삼성전자");
        hold("069500", "KODEX 200");
        doThrow(new KisApiException(KisErrorType.API_ERROR, null)).when(kis).getDailyPrices("005930");
        doReturn(days(3, "35000")).when(kis).getDailyPrices("069500");

        BackfillResult result = backfillService.backfillHoldings(assetId);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(priceRows("005930")).isZero();
        assertThat(priceRows("069500")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 계좌 2회 실행 → stock_prices 행 수 변화 없음 (ticker, trade_date upsert 멱등)")
    void idempotent() {
        hold("005930", "삼성전자");
        doReturn(days(5, "70000")).when(kis).getDailyPrices("005930");

        backfillService.backfillHoldings(assetId);
        backfillService.backfillHoldings(assetId);

        assertThat(priceRows("005930")).isEqualTo(4);
    }

    @Test
    @DisplayName("소급은 사용자 자산 기록을 만들지 않는다 — asset_snapshots · asset_detail_snapshots 0행 (01 §3.1, FR-AS-05)")
    void noUserSnapshots() {
        hold("005930", "삼성전자");
        doReturn(days(5, "70000")).when(kis).getDailyPrices("005930");
        int snapshotsBefore = jdbc.queryForObject("select count(*) from asset_snapshots", Integer.class);
        int detailsBefore = jdbc.queryForObject("select count(*) from asset_detail_snapshots", Integer.class);

        backfillService.backfillHoldings(assetId);

        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_detail_snapshots where asset_id = ?", Integer.class, assetId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_snapshots", Integer.class)).isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForObject("select count(*) from asset_detail_snapshots", Integer.class)).isEqualTo(detailsBefore);
    }

    @Test
    @DisplayName("보유 종목 0개 → 한투 호출 없음")
    void noHoldings_noCall() {
        BackfillResult result = backfillService.backfillHoldings(assetId);

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        verifyNoInteractions(kis);
    }
}
