package com.project.assetory.detection.service;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.NotifyStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.asset.domain.Asset;
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
import com.project.assetory.detection.domain.DetectionEvent;
import com.project.assetory.detection.domain.DetectionEventKey;
import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.Severity;
import com.project.assetory.detection.dto.DetectionSettingUpdateRequest;
import com.project.assetory.detection.repository.DetectionEventRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.SnapshotSaveService;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 종목 급등락 감지 — 사용자 1명 × 날짜 1개 (FR-DT-03 · 06 · 07, 05 §11.3, A4). 값은 모두 테스트 전용 더미
class StockChangeDetectionServiceIntegrationTest extends IntegrationTestSupport {

    // 다른 테스트 시세와 겹치지 않는 날짜
    private static final LocalDate DAY = LocalDate.of(2031, 3, 3);
    private static final LocalDateTime T = DAY.atTime(16, 0);
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호

    @Autowired StockChangeDetectionService detectionService;
    @Autowired DetectionSettingService settingService;
    @Autowired SnapshotSaveService saveService;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserMarketEventRepository userMarketEventRepository;
    @Autowired DetectionEventRepository detectionEventRepository;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired StockPriceRepository stockPriceRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private record Member(Long userId, Long accountId, Long cashId) {}

    // ── 처음 감지 ──

    @Test
    @DisplayName("처음 감지 → 분석 · 사용자 이벤트 · 감지 기록 3행 + has_event, 저장값 전부 정확")
    void firstDetection_createsRowsWithValues() {
        String up = ticker();
        String flat = ticker();
        price(up, "1100", "1000", "10.0000");
        price(flat, "1010", "1000", "1.0000");
        Member m = member();
        // 10주 · 평단 900, 1주 · 평단 1000 / 현금 500,000 → 총자산 512,010
        complete(m, List.of(HoldingPrice.collected(up, d("10"), d("1100")), HoldingPrice.collected(flat, d("1"), d("1010"))));
        holding(m.accountId(), up, "900");

        int created = detectionService.detect(m.userId(), DAY, T);

        assertThat(created).isEqualTo(1);
        StockEventAnalysis analysis = analysisRepository.findByTickerAndEventDate(up, DAY).orElseThrow();
        assertThat(analysis.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(analysis.getEvidenceCount()).isZero();
        assertThat(analysis.getChangeRate()).isEqualByComparingTo("10");
        assertThat(analysis.getStockName()).isEqualTo(up + " 종목");
        assertThat(analysis.getCreatedAt()).isNotNull();
        assertThat(analysis.getUpdatedAt()).isNotNull();
        assertThat(analysisRepository.findByTickerAndEventDate(flat, DAY)).isEmpty();

        UserMarketEvent ume = userMarketEventRepository.findByUserIdAndAnalysisId(m.userId(), analysis.getId()).orElseThrow();
        assertThat(ume.getDirection()).isEqualTo(Direction.UP);
        assertThat(ume.getEventDate()).isEqualTo(DAY);
        assertThat(ume.getAppliedThreshold()).isEqualByComparingTo("5");
        assertThat(ume.getChangeAmount()).isEqualByComparingTo("1000.00");          // 10 × (1100 − 1000)
        assertThat(ume.getPositionRatio()).isEqualByComparingTo("2.15");            // 11,000 ÷ 512,010 × 100
        assertThat(ume.getProfitLossRate()).isEqualByComparingTo("22.2222");        // (11,000 − 9,000) ÷ 9,000 × 100
        assertThat(ume.getNotifyStatus()).isEqualTo(NotifyStatus.NONE);
        assertThat(ume.isBackfilled()).isFalse();
        assertThat(ume.isSummaryProvided()).isFalse();

        String key = DetectionEventKey.stock(up, DAY);
        DetectionEvent de = detectionEventRepository.findByUserIdAndEventKey(m.userId(), key).orElseThrow();
        assertThat(de.getEventKey()).isEqualTo("STOCK:" + up + ":2031-03-03");
        assertThat(de.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(de.getAppliedThreshold()).isEqualByComparingTo("5");
        assertThat(de.getDetectedAt()).isEqualTo(T);
        assertThat(de.getPayload()).containsEntry("ticker", up).containsEntry("stockName", up + " 종목")
                .containsEntry("direction", "UP").containsEntry("eventDate", "2031-03-03");
        assertThat(new BigDecimal(de.getPayload().get("changeRate").toString())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select user_market_event_id from detection_events where id = ?",
                Long.class, de.getId())).isEqualTo(ume.getId());

        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(m.userId(), DAY).orElseThrow().isHasEvent()).isTrue();
    }

    @Test
    @DisplayName("하락도 감지 (DOWN), 원금을 모르면 손익률 NULL")
    void downAndUnknownPrincipal() {
        String down = ticker();
        price(down, "930", "1000", "-7.0000");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(down, d("10"), d("930"))));

        detectionService.detect(m.userId(), DAY, T);

        UserMarketEvent ume = onlyEvent(m, down);
        assertThat(ume.getDirection()).isEqualTo(Direction.DOWN);
        assertThat(ume.getChangeAmount()).isEqualByComparingTo("-700.00");
        assertThat(ume.getProfitLossRate()).isNull();
    }

    // ── 멱등 ──

    @Test
    @DisplayName("같은 조건으로 다시 실행하면 행 수가 그대로다")
    void rerun_idempotent() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(up, d("10"), d("1100"))));

        detectionService.detect(m.userId(), DAY, T);
        int second = detectionService.detect(m.userId(), DAY, T.plusMinutes(30));

        assertThat(second).isZero();
        assertThat(count("stock_event_analyses where ticker = ? and event_date = ?", up, DAY)).isEqualTo(1);
        assertThat(count("user_market_events where user_id = ?", m.userId())).isEqualTo(1);
        assertThat(count("detection_events where user_id = ?", m.userId())).isEqualTo(1);
        // 처음 감지 시각 유지 (덮어쓰지 않음)
        assertThat(detectionEventRepository.findByUserIdAndEventKey(m.userId(), DetectionEventKey.stock(up, DAY))
                .orElseThrow().getDetectedAt()).isEqualTo(T);
    }

    @Test
    @DisplayName("임계치를 낮춰 다시 실행하면 새로 걸린 종목만 추가된다 (기존 이벤트 유지)")
    void lowerThresholdRerun_addsOnlyNew() {
        String big = ticker();
        String mid = ticker();
        price(big, "1100", "1000", "10");
        price(mid, "1060", "1000", "6");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(big, d("1"), d("1100")), HoldingPrice.collected(mid, d("1"), d("1060"))));
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(true, d("8"), null));

        assertThat(detectionService.detect(m.userId(), DAY, T)).isEqualTo(1);
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(true, d("5"), null));
        assertThat(detectionService.detect(m.userId(), DAY, T)).isEqualTo(1);

        assertThat(count("user_market_events where user_id = ?", m.userId())).isEqualTo(2);
        assertThat(onlyEvent(m, big).getAppliedThreshold()).isEqualByComparingTo("8");
        assertThat(onlyEvent(m, mid).getAppliedThreshold()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("두 사용자가 같은 종목을 가지면 분석은 1행, 사용자 이벤트는 2행")
    void twoUsers_sharedAnalysis() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        Member a = member();
        Member b = member();
        complete(a, List.of(HoldingPrice.collected(up, d("1"), d("1100"))));
        complete(b, List.of(HoldingPrice.collected(up, d("3"), d("1100"))));

        detectionService.detect(a.userId(), DAY, T);
        detectionService.detect(b.userId(), DAY, T);

        assertThat(count("stock_event_analyses where ticker = ? and event_date = ?", up, DAY)).isEqualTo(1);
        Long analysisId = analysisRepository.findByTickerAndEventDate(up, DAY).orElseThrow().getId();
        assertThat(count("user_market_events where analysis_id = ?", analysisId)).isEqualTo(2);
        assertThat(onlyEvent(b, up).getChangeAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("이미 분석 행이 있으면 재사용한다 — 기존 값 · 상태 그대로")
    void existingAnalysis_reused() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        StockEventAnalysis existing = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(up, "기존 이름", DAY, d("9.9000")));
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(up, d("1"), d("1100"))));

        detectionService.detect(m.userId(), DAY, T);

        StockEventAnalysis after = analysisRepository.findByTickerAndEventDate(up, DAY).orElseThrow();
        assertThat(after.getId()).isEqualTo(existing.getId());
        assertThat(after.getStockName()).isEqualTo("기존 이름");
        assertThat(after.getChangeRate()).isEqualByComparingTo("9.9");
        assertThat(after.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(userMarketEventRepository.findByUserIdAndAnalysisId(m.userId(), existing.getId())).isPresent();
    }

    // ── 감지하지 않는 경우 ──

    @Test
    @DisplayName("수신을 끈 사용자는 아무 행도 생기지 않고 has_event도 그대로")
    void disabled_nothing() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(up, d("1"), d("1100"))));
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(false, null, null));

        assertThat(detectionService.detect(m.userId(), DAY, T)).isZero();

        assertThat(analysisRepository.findByTickerAndEventDate(up, DAY)).isEmpty();
        assertThat(count("user_market_events where user_id = ?", m.userId())).isZero();
        assertThat(count("detection_events where user_id = ?", m.userId())).isZero();
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(m.userId(), DAY).orElseThrow().isHasEvent()).isFalse();
    }

    @Test
    @DisplayName("제외 종목은 감지하지 않는다")
    void excludedTicker_notDetected() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(up, d("1"), d("1100"))));
        settingService.update(m.userId(), DetectionRule.STOCK_PRICE_CHANGE, new DetectionSettingUpdateRequest(true, null, List.of(up)));

        assertThat(detectionService.detect(m.userId(), DAY, T)).isZero();
        assertThat(count("user_market_events where user_id = ?", m.userId())).isZero();
    }

    @Test
    @DisplayName("그날 기록이 COMPLETE가 아니면(PARTIAL) 감지하지 않는다 (D2)")
    void partialRecord_notDetected() {
        String up = ticker();
        price(up, "1100", "1000", "10");
        Member m = member();
        Long second = transactionTemplate.execute(s -> securities(userRepository.getReferenceById(m.userId()),
                institutionRepository.findByCodefOrgCode("0218").orElseThrow()));
        record(m, List.of(
                Account.success(m.accountId(), d("0"), List.of(HoldingPrice.collected(up, d("1"), d("1100")))),
                Account.of(second, AccountStatus.FAILED)));
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(m.userId(), DAY).orElseThrow().getStatus())
                .isEqualTo(SnapshotStatus.PARTIAL);

        assertThat(detectionService.detect(m.userId(), DAY, T)).isZero();
        assertThat(count("user_market_events where user_id = ?", m.userId())).isZero();
    }

    @Test
    @DisplayName("움직인 종목이 없으면 아무 행도 없고 has_event 그대로")
    void noMove_nothing() {
        String flat = ticker();
        price(flat, "1010", "1000", "1");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(flat, d("1"), d("1010"))));

        assertThat(detectionService.detect(m.userId(), DAY, T)).isZero();
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(m.userId(), DAY).orElseThrow().isHasEvent()).isFalse();
    }

    // ── 쿼리 수 ──

    @Test
    @DisplayName("사용자 1명 · 감지 2종목의 쿼리 수 상한 21회 (기본 6 + 종목당 7 + has_event 1, N+1 없음)")
    void queryCount() {
        String a = ticker();
        String b = ticker();
        String flat = ticker();
        price(a, "1100", "1000", "10");
        price(b, "900", "1000", "-10");
        price(flat, "1000", "1000", "0");
        Member m = member();
        complete(m, List.of(HoldingPrice.collected(a, d("1"), d("1100")), HoldingPrice.collected(b, d("1"), d("900")),
                HoldingPrice.collected(flat, d("1"), d("1000"))));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        int created;
        try {
            created = detectionService.detect(m.userId(), DAY, T);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(created).isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(21);
    }

    // ── 준비 ──

    private UserMarketEvent onlyEvent(Member m, String ticker) {
        Long analysisId = analysisRepository.findByTickerAndEventDate(ticker, DAY).orElseThrow().getId();
        return userMarketEventRepository.findByUserIdAndAnalysisId(m.userId(), analysisId).orElseThrow();
    }

    private Member member() {
        String email = uniqueEmail();
        User user = userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
        Long[] ids = new Long[2];
        transactionTemplate.executeWithoutResult(status -> {
            ids[0] = securities(user, institutionRepository.findByCodefOrgCode("0240").orElseThrow());
            ids[1] = assetRepository.save(Asset.manual(user, AssetType.CASH, "생활비", d("500000"))).getId();
        });
        return new Member(user.getId(), ids[0], ids[1]);
    }

    private Long securities(User user, Institution institution) {
        ConnectedAccount account = connectedAccountRepository.save(
                ConnectedAccount.create(user, institution, "cid-detect-" + UUID.randomUUID(), BusinessType.ST));
        return assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER)).getId();
    }

    private void complete(Member m, List<HoldingPrice> holdings) {
        record(m, List.of(Account.success(m.accountId(), d("0"), holdings)));
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(m.userId(), DAY).orElseThrow().getStatus())
                .isEqualTo(SnapshotStatus.COMPLETE);
    }

    private void record(Member m, List<Account> accounts) {
        saveService.save(m.userId(), new SnapshotInput(DAY, DAY.atTime(16, 5), accounts,
                List.of(new Manual(m.cashId(), AssetType.CASH, d("500000")))));
    }

    private void holding(Long assetId, String ticker, String avgBuyPrice) {
        transactionTemplate.executeWithoutResult(status -> {
            Holding h = Holding.create(assetRepository.findById(assetId).orElseThrow(), ticker);
            h.update(ticker + " 종목", SecurityType.STOCK, d("1"), d(avgBuyPrice), d("999"), d("999"), d("999"), d("99.9999"));
            holdingRepository.save(h);
        });
    }

    private void price(String ticker, String close, String prevClose, String changeRate) {
        transactionTemplate.executeWithoutResult(status -> {
            StockPrice p = StockPrice.create(ticker, DAY);
            p.update(ticker + " 종목", d(close), d(prevClose), d(changeRate), 0L);
            stockPriceRepository.save(p);
        });
    }

    private int count(String fromWhere, Object... args) {
        Integer n = jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String ticker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }
}
