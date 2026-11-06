package com.project.assetory.detection.service;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.NotifyStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.dto.DetectionSettingUpdateRequest;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import com.project.assetory.user.service.UserSettingsService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

// 과거 이벤트 소급 — 계좌 1개 (FR-DT-08, 05 §11.1, A5). 시세 · 계정은 모두 테스트 전용 더미
class StockEventBackfillServiceIntegrationTest extends IntegrationTestSupport {

    // 소급 시세 시작일 — 다른 테스트 시세와 겹치지 않는 과거 날짜(오늘보다 이전)
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호

    @Autowired StockEventBackfillService backfillService;
    @Autowired DetectionSettingService settingService;
    @MockitoSpyBean UserSettingsService userSettingsService;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserMarketEventRepository userMarketEventRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private record Member(Long userId, Long assetId) {}

    // ── 범위 · 방향 ──

    @Test
    @DisplayName("최신 20행 안의 임계치 초과일만 — 21번째 이전 날은 넘어도 제외, 상승 · 하락 모두")
    void latestTwentyRowsOnly() {
        String t = ticker();
        // 25거래일: 0번(가장 오래됨) +9%(범위 밖), 10번 +6%, 20번 −7%, 나머지 +1%
        prices(t, 25, i -> i == 0 ? "9" : i == 10 ? "6" : i == 20 ? "-7" : "1");
        Member m = member(t);

        int created = backfillService.backfill(m.assetId(), true);

        assertThat(created).isEqualTo(2);
        assertThat(eventDates(m.userId())).containsExactly(day(10), day(20));
        assertThat(event(m, t, day(10)).getDirection()).isEqualTo(Direction.UP);
        assertThat(event(m, t, day(20)).getDirection()).isEqualTo(Direction.DOWN);
        assertThat(analysisRepository.findByTickerAndEventDate(t, day(0))).isEmpty();
    }

    @Test
    @DisplayName("오늘보다 미래 날짜의 시세 행은 쓰지 않는다")
    void futureRowsIgnored() {
        String t = ticker();
        LocalDate future = LocalDate.now().plusDays(30);
        price(t, future, "10");
        price(t, START, "6");
        Member m = member(t);

        backfillService.backfill(m.assetId(), true);

        assertThat(eventDates(m.userId())).containsExactly(START);
    }

    @Test
    @DisplayName("시세 행이 N개보다 적은 종목(신규 상장 · 거래정지)도 있는 행만으로 처리, ETF 포함")
    void fewRowsAndEtf() {
        String etf = ticker();
        prices(etf, 3, i -> i == 1 ? "-5" : "0.5");
        Member m = member(etf, SecurityType.ETF);

        assertThat(backfillService.backfill(m.assetId(), true)).isEqualTo(1);
        assertThat(eventDates(m.userId())).containsExactly(day(1));
    }

    // ── 저장값 ──

    @Test
    @DisplayName("소급 이벤트 값: 금액 3개 NULL · is_backfilled · NONE · 적용 임계치 · 시세 날짜, 감지 기록 0")
    void storedValues() {
        String t = ticker();
        prices(t, 5, i -> i == 2 ? "8.5000" : "1");
        Member m = member(t);

        backfillService.backfill(m.assetId(), true);

        UserMarketEvent e = event(m, t, day(2));
        assertThat(e.getChangeAmount()).isNull();
        assertThat(e.getPositionRatio()).isNull();
        assertThat(e.getProfitLossRate()).isNull();
        assertThat(e.isBackfilled()).isTrue();
        assertThat(e.getNotifyStatus()).isEqualTo(NotifyStatus.NONE);
        assertThat(e.getAppliedThreshold()).isEqualByComparingTo("5");
        assertThat(e.getEventDate()).isEqualTo(day(2));
        StockEventAnalysis analysis = analysisRepository.findByTickerAndEventDate(t, day(2)).orElseThrow();
        assertThat(analysis.getChangeRate()).isEqualByComparingTo("8.5");
        assertThat(analysis.getStockName()).isEqualTo(t + " 종목");
        assertThat(count("detection_events where user_id = ?", m.userId())).isZero();
        assertThat(count("asset_snapshots where user_id = ?", m.userId())).isZero();
    }

    @Test
    @DisplayName("두 사용자가 같은 종목 · 날짜를 소급하면 분석 1행, 사용자 이벤트 2행")
    void twoUsers_sharedAnalysis() {
        String t = ticker();
        prices(t, 3, i -> i == 0 ? "6" : "1");
        Member a = member(t);
        Member b = member(t);

        backfillService.backfill(a.assetId(), true);
        backfillService.backfill(b.assetId(), true);

        assertThat(count("stock_event_analyses where ticker = ?", t)).isEqualTo(1);
        Long analysisId = analysisRepository.findByTickerAndEventDate(t, day(0)).orElseThrow().getId();
        assertThat(count("user_market_events where analysis_id = ?", analysisId)).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 실행해도 멱등 — 행 수 그대로, 두 번째 반환값 0")
    void rerun_idempotent() {
        String t = ticker();
        prices(t, 5, i -> i % 2 == 0 ? "6" : "1");
        Member m = member(t);

        assertThat(backfillService.backfill(m.assetId(), true)).isEqualTo(3);
        assertThat(backfillService.backfill(m.assetId(), true)).isZero();

        assertThat(count("user_market_events where user_id = ?", m.userId())).isEqualTo(3);
        assertThat(count("stock_event_analyses where ticker = ?", t)).isEqualTo(3);
    }

    // ── 설정 ──

    @Test
    @DisplayName("수신을 끄면 아무 행도 생기지 않는다")
    void disabled_nothing() {
        String t = ticker();
        prices(t, 3, i -> "10");
        Member m = member(t);
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(false, null, null));

        assertThat(backfillService.backfill(m.assetId(), true)).isZero();
        assertThat(count("user_market_events where user_id = ?", m.userId())).isZero();
        assertThat(count("stock_event_analyses where ticker = ?", t)).isZero();
    }

    @Test
    @DisplayName("제외 종목은 대상에서 빠진다")
    void excludedTicker() {
        String excluded = ticker();
        String kept = ticker();
        prices(excluded, 2, i -> "10");
        prices(kept, 2, i -> "10");
        Member m = member(excluded, kept);
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE,
                new DetectionSettingUpdateRequest(true, null, List.of(excluded)));

        backfillService.backfill(m.assetId(), true);

        assertThat(count("stock_event_analyses where ticker = ?", excluded)).isZero();
        assertThat(count("user_market_events where user_id = ?", m.userId())).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 임계치 8이면 8로 판정 (7% 날은 제외)")
    void userThreshold() {
        String t = ticker();
        prices(t, 3, i -> i == 0 ? "7" : i == 1 ? "-8" : "1");
        Member m = member(t);
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(true, new BigDecimal("8"), null));

        backfillService.backfill(m.assetId(), true);

        assertThat(eventDates(m.userId())).containsExactly(day(1));
        assertThat(event(m, t, day(1)).getAppliedThreshold()).isEqualByComparingTo("8");
    }

    // ── 기존 행 ──

    @Test
    @DisplayName("이미 실시간 이벤트(A4)가 있는 날은 건너뛰고 기존 행은 그대로 (is_backfilled=false 유지)")
    void existingLiveEvent_kept() {
        String t = ticker();
        prices(t, 2, i -> "10");
        Member m = member(t);
        StockEventAnalysis analysis = analysisRepository.saveAndFlush(StockEventAnalysis.create(t, "기존 이름", day(0), new BigDecimal("10")));
        userMarketEventRepository.saveAndFlush(UserMarketEvent.create(userRepository.getReferenceById(m.userId()), analysis,
                Direction.UP, new BigDecimal("5"), new BigDecimal("1000"), new BigDecimal("1.00"), new BigDecimal("2.0000"), false));

        assertThat(backfillService.backfill(m.assetId(), true)).isEqualTo(1);

        UserMarketEvent live = event(m, t, day(0));
        assertThat(live.isBackfilled()).isFalse();
        assertThat(live.getChangeAmount()).isEqualByComparingTo("1000");
        StockEventAnalysis kept = analysisRepository.findByTickerAndEventDate(t, day(0)).orElseThrow();
        assertThat(kept.getId()).isEqualTo(analysis.getId());
        assertThat(kept.getStockName()).isEqualTo("기존 이름");
        assertThat(event(m, t, day(1)).isBackfilled()).isTrue();
    }

    // ── backfill_completed ──

    @Test
    @DisplayName("끝나면 backfill_completed=true, 다시 실행해도 true 유지")
    void flag_setAndKept() {
        String t = ticker();
        prices(t, 2, i -> "1");
        Member m = member(t);
        assertThat(flag(m)).isFalse();

        backfillService.backfill(m.assetId(), true);
        assertThat(flag(m)).isTrue();

        backfillService.backfill(m.assetId(), false);
        assertThat(flag(m)).isTrue();
    }

    @Test
    @DisplayName("시세 소급에 실패한 종목이 있으면(pricesComplete=false) 이벤트는 만들되 플래그는 그대로 false")
    void pricesIncomplete_flagUnchanged() {
        String t = ticker();
        prices(t, 2, i -> "10");
        Member m = member(t);

        assertThat(backfillService.backfill(m.assetId(), false)).isEqualTo(2);
        assertThat(flag(m)).isFalse();
    }

    @Test
    @DisplayName("소급 도중 예외 → 그 계좌의 행이 하나도 남지 않고 플래그도 그대로 (한 트랜잭션)")
    void failure_rollsBackAll() {
        String t = ticker();
        prices(t, 3, i -> "10");
        Member m = member(t);
        doThrow(new IllegalStateException("테스트 전용")).when(userSettingsService).getOrCreate(eq(m.userId()));

        assertThatThrownBy(() -> backfillService.backfill(m.assetId(), true)).isInstanceOf(IllegalStateException.class);

        assertThat(count("user_market_events where user_id = ?", m.userId())).isZero();
        assertThat(count("stock_event_analyses where ticker = ?", t)).isZero();
        assertThat(count("user_settings where user_id = ? and backfill_completed = true", m.userId())).isZero();
    }

    // ── 쿼리 수 ──

    @Test
    @DisplayName("계좌 1개 · 2종목 · 이벤트 3건의 쿼리 수 상한 (종목당 시세 1회, N+1 없음)")
    void queryCount() {
        String a = ticker();
        String b = ticker();
        prices(a, 20, i -> i < 2 ? "6" : "1");
        prices(b, 20, i -> i == 0 ? "-6" : "1");
        Member m = member(a, b);
        flag(m);   // user_settings 미리 생성 — 생성 경로 쿼리 제외

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        int created;
        try {
            created = backfillService.backfill(m.assetId(), true);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(created).isEqualTo(3);
        // 고정: 자산 · 규칙 · 설정 · holdings · user_settings 조회 · 갱신 = 6, 종목당 시세 1 × 2, 이벤트당 분석 조회 · 업서트 · 재조회 · 이벤트 조회 · 저장 5 × 3
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(23);
    }

    // ── 준비 ──

    private UserMarketEvent event(Member m, String ticker, LocalDate date) {
        Long analysisId = analysisRepository.findByTickerAndEventDate(ticker, date).orElseThrow().getId();
        return userMarketEventRepository.findByUserIdAndAnalysisId(m.userId(), analysisId).orElseThrow();
    }

    private List<LocalDate> eventDates(Long userId) {
        return jdbc.queryForList("select event_date from user_market_events where user_id = ? order by event_date",
                LocalDate.class, userId);
    }

    private boolean flag(Member m) {
        return userSettingsService.getOrCreate(m.userId()).isBackfillCompleted();
    }

    private Member member(String... tickers) {
        return member(SecurityType.STOCK, tickers);
    }

    private Member member(String ticker, SecurityType type) {
        return member(type, ticker);
    }

    private Member member(SecurityType type, String... tickers) {
        String email = uniqueEmail();
        User user = userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
        Long assetId = transactionTemplate.execute(status -> {
            Institution institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-backfill-" + UUID.randomUUID(), BusinessType.ST));
            Asset asset = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER));
            for (String ticker : tickers) {
                Holding h = Holding.create(asset, ticker);
                h.update(ticker + " 종목", type, new BigDecimal("1"), null, null, null, null, null);
                holdingRepository.save(h);
            }
            return asset.getId();
        });
        return new Member(user.getId(), assetId);
    }

    private interface Rate {
        String at(int index);
    }

    // index 0 = START, 1 = START + 1일 … (테스트 데이터라 실제 휴장일은 따지지 않는다)
    private void prices(String ticker, int days, Rate rate) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < days; i++) indexes.add(i);
        indexes.forEach(i -> price(ticker, day(i), rate.at(i)));
    }

    private void price(String ticker, LocalDate date, String changeRate) {
        transactionTemplate.executeWithoutResult(status -> {
            StockPrice p = StockPrice.create(ticker, date);
            p.update(ticker + " 종목", new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal(changeRate), 0L);
            stockPriceRepository.save(p);
        });
    }

    private static LocalDate day(int index) {
        return START.plusDays(index);
    }

    private int count(String fromWhere, Object... args) {
        Integer n = jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String ticker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
