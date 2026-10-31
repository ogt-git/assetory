package com.project.assetory.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.domain.SecurityType;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotInput.Account;
import com.project.assetory.snapshot.domain.SnapshotInput.AccountStatus;
import com.project.assetory.snapshot.domain.SnapshotInput.HoldingPrice;
import com.project.assetory.snapshot.domain.SnapshotInput.Manual;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.snapshot.service.SnapshotSaveService;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/snapshots/changes — 전일 · 전월 · 전년 · 누적 증감 (FR-AN-01 · 02 · 03 · 06). 숫자와 플래그만 (문구 · LLM 없음)
class SnapshotApiIntegrationTest extends IntegrationTestSupport {

    private static final String CHANGES = "/api/snapshots/changes";
    private static final LocalDate PREV = LocalDate.of(2026, 9, 30);
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
    private static final List<String> COMPARISON_FIELDS = List.of("available", "baseDate", "baseTotal", "changeAmount",
            "changeRate", "marketChangeAmount", "otherChangeAmount", "assetClassChanges");

    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private record Login(Long userId, String accessToken) {}

    private Login login() throws Exception {
        String email = signupNew();
        Long userId = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow().getId();
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Login(userId, objectMapper.readTree(body).get("data").get("accessToken").asText());
    }

    // COMPLETE 기록 (테스트 전용 더미 값) — 증권 = total − 현금 1,000,000, 예적금 0
    private void complete(Long userId, LocalDate date, String total, LocalDate base, String dayChange, String rate, String market) {
        BigDecimal t = new BigDecimal(total);
        BigDecimal cash = new BigDecimal("1000000");
        SnapshotJudgement judgement = new SnapshotJudgement(SnapshotStatus.COMPLETE, t, t.subtract(cash), BigDecimal.ZERO, cash, List.of());
        DayChange day = base == null ? DayChange.NONE : new DayChange(base, new BigDecimal(dayChange), new BigDecimal(rate));
        MarketChange marketChange = base == null ? MarketChange.NONE
                : new MarketChange(new BigDecimal(market), new BigDecimal(dayChange).subtract(new BigDecimal(market)));
        transactionTemplate.executeWithoutResult(status -> {
            AssetSnapshot snapshot = AssetSnapshot.create(userRepository.getReferenceById(userId), date);
            snapshot.apply(judgement, date.atTime(16, 5), day, marketChange);
            snapshotRepository.save(snapshot);
        });
    }

    private JsonNode changes(Login login) throws Exception {
        String body = mockMvc.perform(get(CHANGES).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private static void assertUnavailable(JsonNode comparison) {
        assertThat(comparison.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(COMPARISON_FIELDS);
        assertThat(comparison.get("available").asBoolean()).isFalse();
        COMPARISON_FIELDS.stream().filter(field -> !field.equals("available"))
                .forEach(field -> assertThat(comparison.get(field).isNull()).as(field).isTrue());
    }

    @Test
    @DisplayName("정상 응답 — ApiResponse로 감싼 구조, 전일 = 저장값, 누적 = 첫 COMPLETE ~ 최근, 기간 미달인 전월 · 전년은 available=false")
    void changes_structureAndValues() throws Exception {
        Login login = login();
        complete(login.userId(), PREV, "3000000", null, null, null, null);
        complete(login.userId(), AS_OF, "3100000", PREV, "100000", "3.3333", "40000");

        JsonNode data = changes(login);

        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "asOfDate", "totalAsset", "latestRecord", "day", "month", "year", "sinceFirstRecord");
        assertThat(data.get("asOfDate").asText()).isEqualTo("2026-10-01");
        assertThat(data.get("totalAsset").decimalValue()).isEqualByComparingTo("3100000");
        assertThat(data.get("latestRecord").get("date").asText()).isEqualTo("2026-10-01");
        assertThat(data.get("latestRecord").get("status").asText()).isEqualTo("COMPLETE");

        JsonNode day = data.get("day");
        assertThat(day.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(COMPARISON_FIELDS);
        assertThat(day.get("available").asBoolean()).isTrue();
        assertThat(day.get("baseDate").asText()).isEqualTo("2026-09-30");
        assertThat(day.get("baseTotal").decimalValue()).isEqualByComparingTo("3000000");
        assertThat(day.get("changeAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(day.get("changeRate").decimalValue()).isEqualByComparingTo("3.3333");
        assertThat(day.get("marketChangeAmount").decimalValue()).isEqualByComparingTo("40000");
        assertThat(day.get("otherChangeAmount").decimalValue()).isEqualByComparingTo("60000");
        assertThat(day.get("assetClassChanges").get("securities").decimalValue()).isEqualByComparingTo("100000");
        assertThat(day.get("assetClassChanges").get("deposit").decimalValue()).isEqualByComparingTo("0");
        assertThat(day.get("assetClassChanges").get("cash").decimalValue()).isEqualByComparingTo("0");

        JsonNode since = data.get("sinceFirstRecord");
        assertThat(since.get("available").asBoolean()).isTrue();
        assertThat(since.get("baseDate").asText()).isEqualTo("2026-09-30");
        assertThat(since.get("changeAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(since.get("changeRate").decimalValue()).isEqualByComparingTo("3.3333");
        assertThat(since.get("marketChangeAmount").decimalValue()).isEqualByComparingTo("40000");

        assertUnavailable(data.get("month"));
        assertUnavailable(data.get("year"));
    }

    @Test
    @DisplayName("기록 0건 → 200, 전부 available=false (오류가 아닌 정상 상태)")
    void noRecords() throws Exception {
        JsonNode data = changes(login());

        assertThat(data.get("asOfDate").isNull()).isTrue();
        assertThat(data.get("totalAsset").isNull()).isTrue();
        assertThat(data.get("latestRecord").isNull()).isTrue();
        for (String period : List.of("day", "month", "year", "sinceFirstRecord")) {
            assertUnavailable(data.get(period));
        }
    }

    @Test
    @DisplayName("인증 없음 → 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(CHANGES)).andExpect(status().isUnauthorized());
    }

    // ================= GET /api/snapshots?month= · GET /api/snapshots/{date} (FR-AS-06) =================

    private static final String RECORDS = "/api/snapshots";
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호
    private static final List<String> RECORD_FIELDS = List.of("date", "status", "missing", "totalAsset", "referenceDate",
            "dayChangeAmount", "dayChangeRate", "marketChangeAmount", "otherChangeAmount", "hasEvent");

    @Autowired SnapshotSaveService saveService;
    @Autowired AssetRepository assetRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;

    // 증권 계좌 + 수기 현금으로 9/1 · 9/3 COMPLETE, 9/2 PARTIAL(증권 실패)을 정기 배치와 같은 경로로 만든다. 반환: 증권 자산 id
    private Long septemberWithDetails(Login login) {
        Long[] ids = new Long[2];
        transactionTemplate.executeWithoutResult(status -> {
            var user = userRepository.getReferenceById(login.userId());
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            var account = connectedAccountRepository.save(ConnectedAccount.create(
                    user, institution, "cid-api-" + UUID.randomUUID(), BusinessType.ST));
            ids[0] = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER)).getId();
            ids[1] = assetRepository.save(Asset.manual(
                    user, AssetType.CASH, "생활비", new BigDecimal("500000"))).getId();
        });
        var cash = new Manual(ids[1],
                AssetType.CASH, new BigDecimal("500000"));
        save(login, LocalDate.of(2026, 9, 1), Account.success(ids[0],
                new BigDecimal("100000"), List.of(HoldingPrice.collected(
                        "T6A001", new BigDecimal("10"), new BigDecimal("1000")))), cash);
        save(login, LocalDate.of(2026, 9, 2), Account.of(ids[0],
                AccountStatus.FAILED), cash);
        save(login, LocalDate.of(2026, 9, 3), Account.success(ids[0],
                new BigDecimal("110000"), List.of(HoldingPrice.collected(
                        "T6A001", new BigDecimal("10"), new BigDecimal("1100")))), cash);
        return ids[0];
    }

    private void save(Login login, LocalDate date, Account account,
                      Manual cash) {
        saveService.save(login.userId(), new SnapshotInput(
                date, date.atTime(16, 5), List.of(account), List.of(cash)));
    }

    private ResultActions authedGet(Login login, String path, String... params)
            throws Exception {
        var request = get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken());
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private JsonNode okData(ResultActions result) throws Exception {
        JsonNode root = objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private void assertError(ResultActions result, int status, String errorCode)
            throws Exception {
        String body = result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("errorCode").asText()).isEqualTo(errorCode);
    }

    @Test
    @DisplayName("기록 목록: 응답 구조 — 월 · 범위 · 요약(B4 Comparison 모양) · 상승/하락 · 내림차순 행, 누락 행은 직전 COMPLETE 값")
    void records_monthStructure() throws Exception {
        Login login = login();
        septemberWithDetails(login);

        JsonNode data = okData(authedGet(login, RECORDS, "month", "2026-09"));

        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("month", "firstRecordMonth", "latestRecordMonth",
                "summary", "risingDays", "fallingDays", "records");
        assertThat(data.get("month").asText()).isEqualTo("2026-09");
        assertThat(data.get("firstRecordMonth").asText()).isEqualTo("2026-09");
        assertThat(data.get("latestRecordMonth").asText()).isEqualTo("2026-09");
        assertThat(data.get("summary").fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(COMPARISON_FIELDS);
        assertThat(data.get("summary").get("changeAmount").decimalValue()).isEqualByComparingTo("11000"); // 621,000 − 610,000
        assertThat(data.get("risingDays").asInt()).isEqualTo(1);
        assertThat(data.get("fallingDays").asInt()).isZero();

        JsonNode records = data.get("records");
        assertThat(records).hasSize(3);
        assertThat(records.get(0).fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(RECORD_FIELDS);
        assertThat(records.get(0).get("date").asText()).isEqualTo("2026-09-03");
        JsonNode partial = records.get(1);
        assertThat(partial.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(partial.get("missing").asBoolean()).isTrue();
        assertThat(partial.get("totalAsset").decimalValue()).isEqualByComparingTo("610000");   // 9/1 COMPLETE (부분 금액 500,000 아님)
        assertThat(partial.get("referenceDate").asText()).isEqualTo("2026-09-01");
        assertThat(partial.get("dayChangeAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("날짜별 상세: 응답 구조 — 자산군 · 자산별 묶음 · 종목, 계좌번호는 마스킹 값만(원문 없음)")
    void records_detailStructure() throws Exception {
        Login login = login();
        septemberWithDetails(login);

        String body = authedGet(login, RECORDS + "/2026-09-03").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(body).doesNotContain(ACCOUNT_NUMBER);
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("date", "status", "missing", "hasEvent",
                "baseDate", "totalAsset", "referenceDate", "dayChangeAmount", "dayChangeRate", "marketChangeAmount",
                "otherChangeAmount", "assetClasses", "assets");
        assertThat(data.get("baseDate").asText()).isEqualTo("2026-09-01");
        assertThat(data.get("assetClasses").get("securities").get("amount").decimalValue()).isEqualByComparingTo("11000");
        assertThat(data.get("assetClasses").get("securities").get("changeAmount").decimalValue()).isEqualByComparingTo("1000");
        JsonNode securities = data.get("assets").get(0);
        assertThat(securities.fieldNames()).toIterable().containsExactlyInAnyOrder("assetId", "assetType", "name",
                "accountNumberMasked", "amount", "dayChangeAmount", "holdings");
        assertThat(securities.get("assetType").asText()).isEqualTo("SECURITIES");
        assertThat(securities.get("accountNumberMasked").asText()).isNotBlank().isNotEqualTo(ACCOUNT_NUMBER);
        JsonNode holding = securities.get("holdings").get(0);
        assertThat(holding.fieldNames()).toIterable().containsExactlyInAnyOrder("ticker", "stockName", "quantity", "price",
                "amount", "dayChangeAmount");
        assertThat(holding.get("dayChangeAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(data.get("assets").get(1).get("accountNumberMasked").isNull()).isTrue(); // 수기 자산

        JsonNode partial = okData(authedGet(login, RECORDS + "/2026-09-02"));
        assertThat(partial.get("missing").asBoolean()).isTrue();
        assertThat(partial.get("assets")).isEmpty();
        assertThat(partial.get("assetClasses").isNull()).isTrue();
    }

    @Test
    @DisplayName("기록 목록: month 생략 → 최근 기록이 있는 달 / 기록 없는 달 → 200 빈 목록")
    void records_defaultMonthAndEmptyMonth() throws Exception {
        Login login = login();
        septemberWithDetails(login);

        assertThat(okData(authedGet(login, RECORDS)).get("month").asText()).isEqualTo("2026-09");
        JsonNode august = okData(authedGet(login, RECORDS, "month", "2026-08"));
        assertThat(august.get("records")).isEmpty();
        assertThat(august.get("summary").get("available").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("잘못된 값 → 400 INVALID_PARAMETER: month=2026-13 · date=2026-02-30 · date=abc")
    void records_invalidParameters() throws Exception {
        Login login = login();

        assertError(authedGet(login, RECORDS, "month", "2026-13"), 400, "INVALID_PARAMETER");
        assertError(authedGet(login, RECORDS + "/2026-02-30"), 400, "INVALID_PARAMETER");
        assertError(authedGet(login, RECORDS + "/abc"), 400, "INVALID_PARAMETER");
    }

    @Test
    @DisplayName("없는 날짜 · 다른 사용자의 날짜 → 404 SNAPSHOT_NOT_FOUND (같은 응답)")
    void records_notFound() throws Exception {
        Login owner = login();
        Login other = login();
        septemberWithDetails(owner);

        assertError(authedGet(owner, RECORDS + "/2026-09-04"), 404, "SNAPSHOT_NOT_FOUND");
        assertError(authedGet(other, RECORDS + "/2026-09-03"), 404, "SNAPSHOT_NOT_FOUND");
    }

    @Test
    @DisplayName("기록 목록 · 상세: 인증 없음 → 401")
    void records_unauthenticated() throws Exception {
        mockMvc.perform(get(RECORDS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RECORDS + "/2026-09-03")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("경로 충돌 없음 — /changes · /trend는 날짜 상세로 가지 않고 그대로 응답")
    void records_noPathConflict() throws Exception {
        Login login = login();
        septemberWithDetails(login);

        assertThat(okData(authedGet(login, CHANGES)).has("day")).isTrue();
        assertThat(okData(authedGet(login, "/api/snapshots/trend", "period", "1M")).has("points")).isTrue();
    }

    // ================= GET /api/snapshots/portfolio (FR-AS-03 · 04) =================

    private static final String PORTFOLIO = "/api/snapshots/portfolio";

    @Autowired HoldingRepository holdingRepository;

    // 현재 holdings(CODEF) — 평균 매입가 900, 평가액 · 손익은 일부러 엉뚱한 값(999)
    private void currentHolding(Long securitiesId) {
        transactionTemplate.executeWithoutResult(status -> {
            Holding holding = Holding.create(assetRepository.findById(securitiesId).orElseThrow(), "T6A001");
            holding.update("테스트종목A", SecurityType.STOCK, new BigDecimal("10"), new BigDecimal("900"),
                    new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("99.9999"));
            holdingRepository.save(holding);
        });
    }

    @Test
    @DisplayName("포트폴리오: 응답 구조 · 값 — 최근 COMPLETE(9/3) 기준, 평가 = 기록 값, 원금 = 기록 수량 × 평균 매입가, 비중 분모 = 총자산")
    void portfolio_structureAndValues() throws Exception {
        Login login = login();
        Long securitiesId = septemberWithDetails(login);
        currentHolding(securitiesId);

        String body = authedGet(login, PORTFOLIO).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(body).doesNotContain(ACCOUNT_NUMBER);
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "asOfDate", "latestRecord", "totalAsset", "assetClasses", "holdings");
        assertThat(data.get("asOfDate").asText()).isEqualTo("2026-09-03");
        assertThat(data.get("latestRecord").get("status").asText()).isEqualTo("COMPLETE");
        assertThat(data.get("totalAsset").decimalValue()).isEqualByComparingTo("621000");

        JsonNode classes = data.get("assetClasses");
        assertThat(classes).hasSize(3);
        assertThat(classes.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("assetClass", "amount", "ratio");
        assertThat(classes.get(0).get("assetClass").asText()).isEqualTo("SECURITIES");
        assertThat(classes.get(0).get("amount").decimalValue()).isEqualByComparingTo("11000");
        assertThat(classes.get(0).get("ratio").decimalValue()).isEqualByComparingTo("1.77");    // 11,000 / 621,000
        assertThat(classes.get(2).get("assetClass").asText()).isEqualTo("CASH");
        assertThat(classes.get(2).get("ratio").decimalValue()).isEqualByComparingTo("98.23");   // 610,000 / 621,000

        JsonNode holding = data.get("holdings").get(0);
        assertThat(holding.fieldNames()).toIterable().containsExactlyInAnyOrder("assetId", "institutionName",
                "accountNumberMasked", "ticker", "stockName", "quantity", "price", "evalAmount", "principal",
                "profitLoss", "profitLossRate", "ratio", "dayChangeAmount");
        assertThat(holding.get("assetId").asLong()).isEqualTo(securitiesId);
        assertThat(holding.get("institutionName").asText()).isEqualTo("삼성증권");
        assertThat(holding.get("accountNumberMasked").asText()).isNotBlank().isNotEqualTo(ACCOUNT_NUMBER);
        assertThat(holding.get("evalAmount").decimalValue()).isEqualByComparingTo("11000");      // CODEF 평가액 999 아님
        assertThat(holding.get("principal").decimalValue()).isEqualByComparingTo("9000");        // 10 × 900
        assertThat(holding.get("profitLoss").decimalValue()).isEqualByComparingTo("2000");
        assertThat(holding.get("profitLossRate").decimalValue()).isEqualByComparingTo("22.2222");
        assertThat(holding.get("ratio").decimalValue()).isEqualByComparingTo("1.77");
        assertThat(holding.get("dayChangeAmount").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("포트폴리오: 기록 0건 → 200, asOfDate · totalAsset null, 빈 목록")
    void portfolio_noRecords() throws Exception {
        JsonNode data = okData(authedGet(login(), PORTFOLIO));

        assertThat(data.get("asOfDate").isNull()).isTrue();
        assertThat(data.get("totalAsset").isNull()).isTrue();
        assertThat(data.get("latestRecord").isNull()).isTrue();
        assertThat(data.get("assetClasses")).isEmpty();
        assertThat(data.get("holdings")).isEmpty();
    }

    @Test
    @DisplayName("포트폴리오: 인증 없음 → 401")
    void portfolio_unauthenticated() throws Exception {
        mockMvc.perform(get(PORTFOLIO)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("포트폴리오: 다른 사용자 토큰 → 토큰 주인 데이터만 (userId 파라미터 무시)")
    void portfolio_ownDataOnly() throws Exception {
        Login owner = login();
        Login other = login();
        septemberWithDetails(owner);
        complete(other.userId(), AS_OF, "500000", null, null, null, null);

        JsonNode data = okData(authedGet(other, PORTFOLIO, "userId", String.valueOf(owner.userId())));

        assertThat(data.get("totalAsset").decimalValue()).isEqualByComparingTo("500000");
        assertThat(data.get("holdings")).isEmpty();
    }

    @Test
    @DisplayName("경로 충돌 없음 — /portfolio · /changes · /trend · 목록 · 날짜 상세가 각자 응답")
    void portfolio_noPathConflict() throws Exception {
        Login login = login();
        septemberWithDetails(login);

        assertThat(okData(authedGet(login, PORTFOLIO)).has("holdings")).isTrue();
        assertThat(okData(authedGet(login, CHANGES)).has("day")).isTrue();
        assertThat(okData(authedGet(login, "/api/snapshots/trend", "period", "1M")).has("points")).isTrue();
        assertThat(okData(authedGet(login, RECORDS, "month", "2026-09")).has("records")).isTrue();
        assertThat(okData(authedGet(login, RECORDS + "/2026-09-03")).has("assets")).isTrue();
    }

    // ================= GET /api/snapshots/trend (FR-AN-05) =================

    private static final String TREND = "/api/snapshots/trend";
    private static final List<String> POINT_FIELDS = List.of("date", "totalAsset", "securitiesAmount", "depositAmount",
            "cashAmount", "dayChangeAmount", "marketChangeAmount", "otherChangeAmount");

    private ResultActions trend(Login login, String period) throws Exception {
        var request = get(TREND).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken());
        if (period != null) {
            request.param("period", period);
        }
        return mockMvc.perform(request);
    }

    private JsonNode trendData(Login login, String period) throws Exception {
        String body = trend(login, period).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    @Test
    @DisplayName("추이: period 생략 → 1Y (S-01 최근 12개월)")
    void trend_defaultOneYear() throws Exception {
        Login login = login();
        complete(login.userId(), PREV, "3000000", null, null, null, null);
        complete(login.userId(), AS_OF, "3100000", PREV, "100000", "3.3333", "40000");

        JsonNode data = trendData(login, null);

        assertThat(data.get("period").asText()).isEqualTo("1Y");
        assertThat(data.get("fullPeriod").asBoolean()).isFalse(); // 기록이 1년 안 됨 → 첫 COMPLETE부터
        assertThat(data.get("points")).hasSize(2);
    }

    @Test
    @DisplayName("추이: period=1M 응답 구조 · 값 — 점은 저장값, summary는 B4 Comparison 모양")
    void trend_oneMonthStructure() throws Exception {
        Login login = login();
        LocalDate start = LocalDate.of(2026, 8, 31);
        complete(login.userId(), start, "2900000", null, null, null, null);
        complete(login.userId(), PREV, "3000000", start, "100000", "3.4483", "70000");
        complete(login.userId(), AS_OF, "3100000", PREV, "100000", "3.3333", "40000");

        JsonNode data = trendData(login, "1M");

        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("period", "asOfDate", "fullPeriod", "points", "summary");
        assertThat(data.get("period").asText()).isEqualTo("1M");
        assertThat(data.get("asOfDate").asText()).isEqualTo("2026-10-01");
        assertThat(data.get("fullPeriod").asBoolean()).isTrue();             // 목표 9/1 이하 COMPLETE(8/31) 있음
        JsonNode points = data.get("points");
        assertThat(points).hasSize(3);
        assertThat(points.get(0).fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(POINT_FIELDS);
        assertThat(points.get(0).get("date").asText()).isEqualTo("2026-08-31");
        assertThat(points.get(0).get("marketChangeAmount").isNull()).isTrue();  // 첫 기록 — 저장값 NULL 그대로
        assertThat(points.get(2).get("totalAsset").decimalValue()).isEqualByComparingTo("3100000");
        assertThat(points.get(2).get("securitiesAmount").decimalValue()).isEqualByComparingTo("2100000");
        assertThat(points.get(2).get("marketChangeAmount").decimalValue()).isEqualByComparingTo("40000");

        JsonNode summary = data.get("summary");
        assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(COMPARISON_FIELDS);
        assertThat(summary.get("available").asBoolean()).isTrue();
        assertThat(summary.get("baseDate").asText()).isEqualTo("2026-08-31");
        assertThat(summary.get("changeAmount").decimalValue()).isEqualByComparingTo("200000");
        assertThat(summary.get("marketChangeAmount").decimalValue()).isEqualByComparingTo("110000"); // 70,000 + 40,000
        assertThat(summary.get("otherChangeAmount").decimalValue()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("추이: period=3M · 1m · 1y · ONE_MONTH → 400 INVALID_PARAMETER")
    void trend_invalidPeriod() throws Exception {
        Login login = login();
        for (String invalid : List.of("3M", "1m", "1y", "ONE_MONTH")) {
            String body = trend(login, invalid).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(body).get("errorCode").asText()).as(invalid).isEqualTo("INVALID_PARAMETER");
        }
    }

    @Test
    @DisplayName("추이: 인증 없음 → 401")
    void trend_unauthenticated() throws Exception {
        mockMvc.perform(get(TREND).param("period", "1M")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("추이: 다른 사용자 토큰 → 토큰 주인 데이터만 (userId 파라미터 무시)")
    void trend_ownDataOnly() throws Exception {
        Login owner = login();
        Login other = login();
        complete(owner.userId(), PREV, "3000000", null, null, null, null);
        complete(owner.userId(), AS_OF, "3100000", PREV, "100000", "3.3333", "40000");
        complete(other.userId(), AS_OF, "500000", null, null, null, null);

        String body = mockMvc.perform(get(TREND).param("period", "ALL").param("userId", String.valueOf(owner.userId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(data.get("points")).hasSize(1);
        assertThat(data.get("points").get(0).get("totalAsset").decimalValue()).isEqualByComparingTo("500000");
        assertUnavailable(data.get("summary"));
    }

    @Test
    @DisplayName("추이: 기록 0건 → 200 + points [] + summary available=false")
    void trend_noRecords() throws Exception {
        JsonNode data = trendData(login(), "ALL");

        assertThat(data.get("period").asText()).isEqualTo("ALL");
        assertThat(data.get("asOfDate").isNull()).isTrue();
        assertThat(data.get("points").isArray()).isTrue();
        assertThat(data.get("points")).isEmpty();
        assertUnavailable(data.get("summary"));
    }

    @Test
    @DisplayName("다른 사용자 토큰 → 그 사용자 자기 데이터만 (사용자 식별은 토큰, userId 파라미터 무시)")
    void ownDataOnly() throws Exception {
        Login owner = login();
        Login other = login();
        complete(owner.userId(), PREV, "3000000", null, null, null, null);
        complete(owner.userId(), AS_OF, "3100000", PREV, "100000", "3.3333", "40000");
        complete(other.userId(), AS_OF, "500000", null, null, null, null);

        String body = mockMvc.perform(get(CHANGES).param("userId", String.valueOf(owner.userId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(data.get("totalAsset").decimalValue()).isEqualByComparingTo("500000");
        assertUnavailable(data.get("day"));
        assertUnavailable(data.get("sinceFirstRecord"));
        assertThat(changes(owner).get("totalAsset").decimalValue()).isEqualByComparingTo("3100000");
    }
}
