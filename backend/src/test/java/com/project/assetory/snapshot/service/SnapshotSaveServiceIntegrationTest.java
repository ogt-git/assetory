package com.project.assetory.snapshot.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 스냅샷 저장 — upsert 멱등, 직전 COMPLETE 기준, detail 행 규칙, snapshot.status 메트릭 (05 §4.7 · §4.8 · §12)
class SnapshotSaveServiceIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate THU = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @Autowired SnapshotSaveService saveService;
    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired AssetDetailSnapshotRepository detailRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long securitiesId;
    private Long cashId;

    @BeforeEach
    void setUp() throws Exception {
        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        userId = user.getId();
        transactionTemplate.executeWithoutResult(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-snap-" + UUID.randomUUID(), BusinessType.ST));
            securitiesId = assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901")).getId();
            cashId = assetRepository.save(Asset.manual(user, AssetType.CASH, "생활비 통장", new BigDecimal("50000"))).getId();
        });
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private SnapshotInput input(LocalDate date, Account account, String cash) {
        return new SnapshotInput(date, date.atTime(16, 5), List.of(account),
                List.of(new Manual(cashId, AssetType.CASH, d(cash))));
    }

    private Account account(String deposit, HoldingPrice... holdings) {
        return Account.success(securitiesId, d(deposit), List.of(holdings));
    }

    private List<AssetDetailSnapshot> details(LocalDate date) {
        return detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, date);
    }

    private AssetDetailSnapshot detail(LocalDate date, Long assetId, String ticker) {
        return transactionTemplate.execute(status -> details(date).stream()
                .filter(x -> x.getAsset().getId().equals(assetId) && Objects.equals(x.getTicker(), ticker))
                .findFirst().orElse(null));
    }

    @Test
    @DisplayName("같은 입력을 2번 저장해도 asset_snapshots 1행, detail 행 수 동일 (upsert 멱등 — ticker NULL 행 포함)")
    void idempotent() {
        SnapshotInput input = input(MON, account("100000", HoldingPrice.collected("005930", d("10"), d("71200"))), "50000");

        saveService.save(userId, input);
        saveService.save(userId, input);

        // 2행이면 단건 조회가 IncorrectResultSizeDataAccessException으로 실패한다
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(userId, MON)).isPresent();
        assertThat(details(MON)).hasSize(3); // 예수금 · 종목 · 수기 — ticker NULL 행 2개도 중복 없음
    }

    @Test
    @DisplayName("COMPLETE: 금액 · 직전 COMPLETE 대비 변화 저장 — 사이의 PARTIAL · FAILED 행은 건너뛴다")
    void dayChange_skipsNonComplete() {
        saveService.save(userId, input(THU, account("0", HoldingPrice.collected("005930", d("10"), d("5000"))), "50000")); // 100000 COMPLETE
        saveService.save(userId, input(FRI, Account.of(securitiesId, AccountStatus.FAILED), "50000"));                     // PARTIAL

        AssetSnapshot mon = saveService.save(userId,
                input(MON, account("0", HoldingPrice.collected("005930", d("10"), d("6000"))), "50000")).orElseThrow(); // 110000

        assertThat(mon.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(mon.getTotalAsset()).isEqualByComparingTo("110000");
        assertThat(mon.getSecuritiesAmount()).isEqualByComparingTo("60000");
        assertThat(mon.getCashAmount()).isEqualByComparingTo("50000");
        assertThat(mon.getDepositAmount()).isEqualByComparingTo("0");
        assertThat(mon.getBaseSnapshotDate()).isEqualTo(THU);
        assertThat(mon.getDayChangeAmount()).isEqualByComparingTo("10000");
        assertThat(mon.getDayChangeRate()).isEqualByComparingTo("10.0000");
        // 기준 = 직전 COMPLETE(THU) — 10주 × (6,000 − 5,000)
        assertThat(mon.getMarketChangeAmount()).isEqualByComparingTo("10000");
        assertThat(mon.getOtherChangeAmount()).isEqualByComparingTo("0");
        assertThat(mon.getCollectedAt()).isEqualTo(MON.atTime(16, 5));
        assertThat(mon.isHasEvent()).isFalse();

        AssetSnapshot fri = snapshotRepository.findByUserIdAndSnapshotDate(userId, FRI).orElseThrow();
        assertThat(fri.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(fri.getBaseSnapshotDate()).isNull();
        assertThat(fri.getDayChangeAmount()).isNull();
        assertThat(fri.getMarketChangeAmount()).isNull();
        assertThat(fri.getOtherChangeAmount()).isNull();
    }

    @Test
    @DisplayName("시장가격 영향: 연속 COMPLETE + 가격 변동 · 추가 매수 → market = 계속 보유 수량 × Δp, other = 총변화 − market (05 §4.7)")
    void marketChange_consecutiveComplete() {
        AssetSnapshot thu = saveService.save(userId,
                input(THU, account("0", HoldingPrice.collected("005930", d("10"), d("5000"))), "50000")).orElseThrow(); // 100,000
        AssetSnapshot mon = saveService.save(userId,
                input(MON, account("0", HoldingPrice.collected("005930", d("12"), d("6000"))), "50000")).orElseThrow(); // 122,000

        assertThat(thu.getMarketChangeAmount()).isNull(); // 첫 기록 — 기준 없음
        assertThat(thu.getOtherChangeAmount()).isNull();
        assertThat(mon.getDayChangeAmount()).isEqualByComparingTo("22000");
        assertThat(mon.getMarketChangeAmount()).isEqualByComparingTo("10000"); // min(10, 12) × 1,000
        assertThat(mon.getOtherChangeAmount()).isEqualByComparingTo("12000");  // 늘어난 2주는 기타 자산 변화
    }

    @Test
    @DisplayName("시장가격 영향 재실행: 같은 날 COMPLETE 재저장은 다시 계산, 더 나쁜 결과로는 덮어쓰지 않음")
    void marketChange_rerun() {
        saveService.save(userId, input(THU, account("0", HoldingPrice.collected("005930", d("10"), d("5000"))), "50000"));
        saveService.save(userId, input(MON, account("0", HoldingPrice.collected("005930", d("10"), d("6000"))), "50000"));

        AssetSnapshot recalculated = saveService.save(userId,
                input(MON, account("0", HoldingPrice.collected("005930", d("10"), d("6500"))), "50000")).orElseThrow();
        assertThat(recalculated.getMarketChangeAmount()).isEqualByComparingTo("15000");
        assertThat(recalculated.getOtherChangeAmount()).isEqualByComparingTo("0");

        AssetSnapshot kept = saveService.save(userId,
                input(MON, Account.of(securitiesId, AccountStatus.FAILED), "50000")).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(kept.getMarketChangeAmount()).isEqualByComparingTo("15000");
        assertThat(kept.getOtherChangeAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("시장가격 영향 전환기: 기준일 종목 행 quantity가 NULL(컬럼 추가 전 행) → market · other 모두 NULL, 0으로 채우지 않음")
    void marketChange_baseRowWithoutQuantity_null() {
        saveService.save(userId, input(THU, account("0", HoldingPrice.collected("005930", d("10"), d("5000"))), "50000"));
        jdbcTemplate.update("update asset_detail_snapshots set quantity = null, price = null "
                + "where asset_id = ? and ticker is not null and snapshot_date = ?", securitiesId, THU);

        AssetSnapshot mon = saveService.save(userId,
                input(MON, account("0", HoldingPrice.collected("005930", d("10"), d("6000"))), "50000")).orElseThrow();

        assertThat(mon.getDayChangeAmount()).isEqualByComparingTo("10000"); // 전일 대비는 그대로 계산
        assertThat(mon.getMarketChangeAmount()).isNull();
        assertThat(mon.getOtherChangeAmount()).isNull();
    }

    @Test
    @DisplayName("detail 규칙: 예수금 행(ticker NULL) · 성공 종목 행 · 수기 자산 행, 시세 실패 종목은 행 없음")
    void detailRows() {
        saveService.save(userId, input(MON, account("100000",
                HoldingPrice.collected("005930", d("10"), d("71200")),
                HoldingPrice.failed("000660", d("3"))), "50000"));

        assertThat(detail(MON, securitiesId, null).getAmount()).isEqualByComparingTo("100000");
        assertThat(detail(MON, securitiesId, "005930").getAmount()).isEqualByComparingTo("712000");
        assertThat(detail(MON, securitiesId, "000660")).isNull();
        assertThat(detail(MON, cashId, null).getAmount()).isEqualByComparingTo("50000");
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(userId, MON).orElseThrow().getStatus())
                .isEqualTo(SnapshotStatus.PARTIAL);
    }

    @Test
    @DisplayName("detail quantity · price: 종목 행은 평가에 쓴 수량 · 단가, ticker NULL 행(예수금 · 수기)은 NULL")
    void detailQuantityPrice() {
        saveService.save(userId, input(MON, account("100000",
                HoldingPrice.collected("005930", d("10"), d("71200")),
                HoldingPrice.halted("111111", d("5"), d("12000"))), "50000"));

        AssetDetailSnapshot samsung = detail(MON, securitiesId, "005930");
        assertThat(samsung.getQuantity()).isEqualByComparingTo("10");
        assertThat(samsung.getPrice()).isEqualByComparingTo("71200");
        AssetDetailSnapshot halted = detail(MON, securitiesId, "111111");
        assertThat(halted.getQuantity()).isEqualByComparingTo("5");
        assertThat(halted.getPrice()).isEqualByComparingTo("12000");
        assertThat(halted.getAmount()).isEqualByComparingTo("60000");

        for (AssetDetailSnapshot assetLevel : List.of(detail(MON, securitiesId, null), detail(MON, cashId, null))) {
            assertThat(assetLevel.getQuantity()).isNull();
            assertThat(assetLevel.getPrice()).isNull();
        }
    }

    @Test
    @DisplayName("같은 날 재실행(COMPLETE → COMPLETE): 종목 행 quantity · price 갱신, 이번에 없는 종목 행은 지운다")
    void rerun_updatesQuantityPrice_removesMissingTicker() {
        saveService.save(userId, input(MON, account("100000",
                HoldingPrice.collected("005930", d("10"), d("71200")),
                HoldingPrice.collected("035720", d("2"), d("40000"))), "50000"));

        saveService.save(userId, input(MON, account("100000",
                HoldingPrice.collected("005930", d("12"), d("70000"))), "50000"));

        AssetDetailSnapshot samsung = detail(MON, securitiesId, "005930");
        assertThat(samsung.getQuantity()).isEqualByComparingTo("12");
        assertThat(samsung.getPrice()).isEqualByComparingTo("70000");
        assertThat(samsung.getAmount()).isEqualByComparingTo("840000");
        assertThat(detail(MON, securitiesId, "035720")).isNull();
        assertThat(details(MON)).hasSize(3); // 예수금 · 005930 · 수기
    }

    @Test
    @DisplayName("detail day_change_amount: 같은 (asset_id, ticker)의 직전 행 대비, 직전 행이 없으면 NULL")
    void detailDayChange() {
        saveService.save(userId, input(THU, account("100000", HoldingPrice.collected("005930", d("10"), d("70000"))), "50000"));
        saveService.save(userId, input(MON, account("90000",
                HoldingPrice.collected("005930", d("10"), d("71200")),
                HoldingPrice.collected("035720", d("2"), d("40000"))), "55000"));

        assertThat(detail(THU, securitiesId, "005930").getDayChangeAmount()).isNull();
        assertThat(detail(MON, securitiesId, "005930").getDayChangeAmount()).isEqualByComparingTo("12000");
        assertThat(detail(MON, securitiesId, null).getDayChangeAmount()).isEqualByComparingTo("-10000");
        assertThat(detail(MON, cashId, null).getDayChangeAmount()).isEqualByComparingTo("5000");
        assertThat(detail(MON, securitiesId, "035720").getDayChangeAmount()).isNull(); // 새로 산 종목
    }

    @Test
    @DisplayName("detail day_change_amount 기준 = 총자산과 같은 직전 COMPLETE 날짜 — 사이의 PARTIAL detail 행과 비교하지 않는다")
    void detailDayChange_baseIsPreviousComplete() {
        saveService.save(userId, input(THU, account("100000", HoldingPrice.collected("005930", d("10"), d("70000"))), "50000")); // COMPLETE
        saveService.save(userId, input(FRI, account("95000",
                HoldingPrice.collected("005930", d("10"), d("71000")),
                HoldingPrice.failed("000660", d("3"))), "52000"));                                                          // PARTIAL
        AssetSnapshot mon = saveService.save(userId,
                input(MON, account("90000", HoldingPrice.collected("005930", d("10"), d("71200"))), "55000")).orElseThrow(); // COMPLETE

        assertThat(mon.getBaseSnapshotDate()).isEqualTo(THU);
        // FRI(PARTIAL) 행 대비(2000 · -5000 · 3000)가 아니라 THU(COMPLETE) 행 대비
        assertThat(detail(MON, securitiesId, "005930").getDayChangeAmount()).isEqualByComparingTo("12000");
        assertThat(detail(MON, securitiesId, null).getDayChangeAmount()).isEqualByComparingTo("-10000");
        assertThat(detail(MON, cashId, null).getDayChangeAmount()).isEqualByComparingTo("5000");
        // 상세 변화액 합 = 총자산 변화액
        assertThat(mon.getDayChangeAmount()).isEqualByComparingTo("7000");

        // PARTIAL 날의 detail도 같은 기준(직전 COMPLETE = THU)
        assertThat(detail(FRI, securitiesId, "005930").getDayChangeAmount()).isEqualByComparingTo("10000");
        assertThat(detail(FRI, securitiesId, null).getDayChangeAmount()).isEqualByComparingTo("-5000");
    }

    @Test
    @DisplayName("detail day_change_amount: 직전 COMPLETE가 없으면(이전 기록이 PARTIAL뿐) NULL")
    void detailDayChange_noPreviousComplete() {
        saveService.save(userId, input(FRI, account("95000",
                HoldingPrice.collected("005930", d("10"), d("71000")),
                HoldingPrice.failed("000660", d("3"))), "52000"));                                                          // PARTIAL
        saveService.save(userId, input(MON, account("90000", HoldingPrice.collected("005930", d("10"), d("71200"))), "55000")); // COMPLETE

        assertThat(detail(MON, securitiesId, "005930").getDayChangeAmount()).isNull();
        assertThat(detail(MON, securitiesId, null).getDayChangeAmount()).isNull();
        assertThat(detail(MON, cashId, null).getDayChangeAmount()).isNull();
    }

    @Test
    @DisplayName("같은 등급(PARTIAL → PARTIAL)으로 다시 저장하면 덮어쓰고, 이번에 실패한 자산의 기존 detail 행은 지운다")
    void overwrite_sameGrade_removesStaleDetails() {
        // 1회차: 증권 성공 · 수기 실패 → PARTIAL
        saveService.save(userId, new SnapshotInput(MON, MON.atTime(16, 5),
                List.of(account("100000", HoldingPrice.collected("005930", d("10"), d("71200")))),
                List.of(new Manual(cashId, AssetType.CASH, null))));
        // 2회차: 증권 실패 · 수기 성공 → PARTIAL
        AssetSnapshot rerun = saveService.save(userId,
                input(MON, Account.of(securitiesId, AccountStatus.FAILED), "50000")).orElseThrow();

        assertThat(rerun.getStatus()).isEqualTo(SnapshotStatus.PARTIAL);
        assertThat(rerun.getSecuritiesAmount()).isNull();
        assertThat(details(MON)).hasSize(1);
        assertThat(detail(MON, securitiesId, null)).isNull();
        assertThat(detail(MON, securitiesId, "005930")).isNull();
        assertThat(detail(MON, cashId, null)).isNotNull();
    }

    @Test
    @DisplayName("재실행: 기존 COMPLETE는 PARTIAL · FAILED로 덮어쓰지 않는다 (확정 2)")
    void overwrite_completeIsNotDowngraded() {
        saveService.save(userId, input(MON, account("100000", HoldingPrice.collected("005930", d("10"), d("71200"))), "50000"));

        AssetSnapshot kept = saveService.save(userId,
                input(MON, Account.of(securitiesId, AccountStatus.FAILED), "50000")).orElseThrow();

        assertThat(kept.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(kept.getTotalAsset()).isEqualByComparingTo("862000");
        assertThat(details(MON)).hasSize(3);
        assertThat(detail(MON, securitiesId, "005930").getQuantity()).isEqualByComparingTo("10"); // detail도 그대로
        assertThat(detail(MON, securitiesId, "005930").getPrice()).isEqualByComparingTo("71200");
    }

    @Test
    @DisplayName("재실행: PARTIAL은 COMPLETE로 덮어쓴다")
    void overwrite_partialUpgradedToComplete() {
        saveService.save(userId, input(MON, Account.of(securitiesId, AccountStatus.FAILED), "50000"));

        AssetSnapshot upgraded = saveService.save(userId,
                input(MON, account("100000", HoldingPrice.collected("005930", d("10"), d("71200"))), "50000")).orElseThrow();

        assertThat(upgraded.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(details(MON)).hasSize(3);
    }

    @Test
    @DisplayName("FAILED: 금액 NULL, detail 없음 / 대상 0개: 행을 만들지 않음")
    void failedAndEmpty() {
        AssetSnapshot failed = saveService.save(userId, new SnapshotInput(MON, MON.atTime(16, 5),
                List.of(Account.of(securitiesId, AccountStatus.EXCLUDED_AUTH)), List.of())).orElseThrow();

        assertThat(failed.getStatus()).isEqualTo(SnapshotStatus.FAILED);
        assertThat(failed.getTotalAsset()).isNull();
        assertThat(details(MON)).isEmpty();

        assertThat(saveService.save(userId, new SnapshotInput(THU, THU.atTime(16, 5), List.of(), List.of()))).isEmpty();
        assertThat(snapshotRepository.findByUserIdAndSnapshotDate(userId, THU)).isEmpty();
    }

    @Test
    @DisplayName("snapshot.status 메트릭 — status 태그만 (사용자 id 태그 없음)")
    void metric() {
        double before = count("COMPLETE");

        saveService.save(userId, input(MON, account("0"), "50000"));

        assertThat(count("COMPLETE") - before).isEqualTo(1.0);
        assertThat(meterRegistry.get("snapshot.status").meters())
                // 공통 태그 application 외에는 status만 — 사용자 id 등은 붙이지 않는다
                .allSatisfy(m -> assertThat(m.getId().getTags()).extracting(t -> t.getKey())
                        .containsExactlyInAnyOrder("application", "status"));
    }

    private double count(String status) {
        var counter = meterRegistry.find("snapshot.status").tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }
}
