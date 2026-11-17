package com.project.assetory.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.ai.openai.OpenAiApiClient;
import com.project.assetory.analysis.dart.DartApiClient;
import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.naver.NaverNewsApiClient;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.analysis.service.StockEventDetailQueryService;
import com.project.assetory.connect.codef.CodefApiClient;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.market.ecos.EcosApiClient;
import com.project.assetory.market.kis.KisApiClient;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANL-9 원인 분석 상세 API (S-04, Q-9) — GET /api/stock-events/{ticker}/{date}.
 * 저장값만 읽는다(외부 호출 0, 쿼리 수 고정). 없는 이벤트 · 남의 이벤트는 같은 404. 토큰 · 한도 정보는 응답에 없다(FR-AI-10).
 * 테스트 전용 — 계정 · 종목 · 공시 제목 · 기사 · 요약 문구는 지어낸 더미 값이다(실제 공시 · 기사 원문 아님)
 */
class StockEventDetailApiIntegrationTest extends IntegrationTestSupport {

    private static final String PATH = "/api/stock-events/{ticker}/{date}";
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired UserRepository userRepository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserMarketEventRepository eventRepository;
    @Autowired EventSourceRepository sourceRepository;
    @Autowired StockEventDetailQueryService queryService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    private record Login(Long userId, String accessToken) {}

    private record Scenario(Login login, String ticker, LocalDate date, Long analysisId) {}

    @Test
    @DisplayName("정상(ANALYZED) — 내 이벤트 · 분석 · 시장 비교(KOSPI · KOSDAQ) · 근거 · 지난 이벤트가 모두 저장값으로 온다")
    void analyzed_allBlocks() throws Exception {
        Scenario s = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약 — 같은 시점에 공시가 있었습니다.", 2, false);
        disclosure(s, s.date(), "테스트 전용 공시 제목");
        news(s, s.date().atTime(10, 0), "테스트 전용 기사");
        pastEvent(s, s.date().minusDays(20), "6.1000");
        pastEvent(s, s.date().minusDays(10), "-5.5000");

        JsonNode data = detail(s);

        JsonNode event = data.get("event");
        assertThat(event.get("ticker").asText()).isEqualTo(s.ticker());
        assertThat(event.get("stockName").asText()).isEqualTo("테스트 종목");
        assertThat(event.get("eventDate").asText()).isEqualTo(s.date().toString());
        assertThat(event.get("direction").asText()).isEqualTo("DOWN");
        assertThat(event.get("changeRate").decimalValue()).isEqualByComparingTo("-7.23");
        assertThat(event.get("changeAmount").decimalValue()).isEqualByComparingTo("-124000.00");
        assertThat(event.get("positionRatio").decimalValue()).isEqualByComparingTo("34.00");
        assertThat(event.get("profitLossRate").decimalValue()).isEqualByComparingTo("-12.0000");
        assertThat(event.get("backfilled").asBoolean()).isFalse();

        JsonNode analysis = data.get("analysis");
        assertThat(analysis.get("analysisStatus").asText()).isEqualTo("ANALYZED");
        assertThat(analysis.get("evidenceStatus").asText()).isEqualTo("RELATED_FOUND");
        assertThat(analysis.get("aiSummary").asText()).startsWith("테스트 전용 요약");

        JsonNode market = data.get("market");
        assertThat(market.get("indexCode").asText()).isEqualTo("KOSPI");
        assertThat(market.get("indexChangeRate").decimalValue()).isEqualByComparingTo("-0.8");
        assertThat(market.get("excessRate").decimalValue()).isEqualByComparingTo("-6.43");
        assertThat(market.get("kospiChangeRate").decimalValue()).isEqualByComparingTo("-0.8");
        assertThat(market.get("kosdaqChangeRate").decimalValue()).isEqualByComparingTo("-1.1");

        assertThat(data.get("evidenceCounts").get("recorded").asInt()).isEqualTo(2);
        assertThat(data.get("evidenceCounts").get("disclosure").asInt()).isEqualTo(1);
        assertThat(data.get("evidenceCounts").get("news").asInt()).isEqualTo(1);

        JsonNode disclosure = data.get("disclosures").get(0);
        assertThat(disclosure.get("title").asText()).isEqualTo("테스트 전용 공시 제목");
        assertThat(disclosure.get("sourceName").asText()).isEqualTo("DART");
        assertThat(disclosure.get("publishedDate").asText()).isEqualTo(s.date().toString());
        assertThat(disclosure.get("publishedAt").isNull()).isTrue();
        assertThat(disclosure.get("url").asText()).startsWith("https://");

        JsonNode news = data.get("news").get(0);
        assertThat(news.get("title").asText()).isEqualTo("테스트 전용 기사");
        assertThat(news.get("snippet").asText()).isEqualTo("테스트 전용 요약문");
        assertThat(news.get("sourceName").asText()).isEqualTo("news.example.test");
        assertThat(news.get("publishedDate").asText()).isEqualTo(s.date().toString());

        JsonNode past = data.get("pastEvents");
        assertThat(past).hasSize(2);
        assertThat(past.get(0).get("eventDate").asText()).isEqualTo(s.date().minusDays(10).toString());
        assertThat(past.get(0).get("direction").asText()).isEqualTo("DOWN");
        assertThat(past.get(0).get("changeRate").decimalValue()).isEqualByComparingTo("-5.5");
        assertThat(past.get(1).get("eventDate").asText()).isEqualTo(s.date().minusDays(20).toString());
    }

    @ParameterizedTest(name = "[{index}] {0}이면 요약은 null")
    @ValueSource(strings = {"PENDING", "COLLECTING", "NO_EVIDENCE", "SUMMARY_SKIPPED", "FAILED"})
    @DisplayName("상태별 응답 — ANALYZED가 아니면 ai_summary가 저장돼 있어도 aiSummary는 null, 상태는 그대로")
    void summaryOnlyWhenAnalyzed(String status) throws Exception {
        Scenario s = scenario(status, null, "테스트 전용 남은 요약", 0, false);

        JsonNode analysis = detail(s).get("analysis");

        assertThat(analysis.get("analysisStatus").asText()).isEqualTo(status);
        assertThat(analysis.get("aiSummary").isNull()).isTrue();
        assertThat(analysis.get("evidenceStatus").isNull()).isTrue();
    }

    @Test
    @DisplayName("PENDING(공시 재시도 중, 뉴스만 저장) — 근거 상태 · 지수 비교 없음, 저장된 뉴스는 보인다")
    void pendingPartial() throws Exception {
        Scenario s = scenario("PENDING", null, null, 0, false);
        jdbc.update("update stock_event_analyses set market_change_rate = null, excess_rate = null where id = ?", s.analysisId());
        jdbc.update("delete from macro_indicators where indicator_code = 'KOSDAQ' and base_date = ?", s.date());
        news(s, s.date().atTime(11, 0), "테스트 전용 기사");

        JsonNode data = detail(s);

        assertThat(data.get("analysis").get("evidenceStatus").isNull()).isTrue();
        assertThat(data.get("market").get("indexChangeRate").isNull()).isTrue();
        assertThat(data.get("market").get("excessRate").isNull()).isTrue();
        assertThat(data.get("market").get("kosdaqChangeRate").isNull()).isTrue();
        assertThat(data.get("market").get("kospiChangeRate").decimalValue()).isEqualByComparingTo("-0.8");
        assertThat(data.get("evidenceCounts").get("recorded").asInt()).isZero();
        assertThat(data.get("evidenceCounts").get("news").asInt()).isEqualTo(1);
        assertThat(data.get("news")).hasSize(1);
        assertThat(data.get("disclosures")).isEmpty();
    }

    @Test
    @DisplayName("NO_EVIDENCE — 근거 목록은 빈 배열, 지난 이벤트도 없으면 빈 배열")
    void noEvidence() throws Exception {
        Scenario s = scenario("NO_EVIDENCE", "MARKET_MOVE", null, 0, false);

        JsonNode data = detail(s);

        assertThat(data.get("analysis").get("evidenceStatus").asText()).isEqualTo("MARKET_MOVE");
        assertThat(data.get("disclosures")).isEmpty();
        assertThat(data.get("news")).isEmpty();
        assertThat(data.get("pastEvents")).isEmpty();
    }

    @Test
    @DisplayName("소급 이벤트 — backfilled = true, 금액 3개는 null 그대로(0으로 채우지 않음)")
    void backfilled() throws Exception {
        Scenario s = scenario("SUMMARY_SKIPPED", "RELATED_FOUND", null, 1, true);

        JsonNode event = detail(s).get("event");

        assertThat(event.get("backfilled").asBoolean()).isTrue();
        assertThat(event.get("changeAmount").isNull()).isTrue();
        assertThat(event.get("positionRatio").isNull()).isTrue();
        assertThat(event.get("profitLossRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("뉴스 보관 기간 지남 — 뉴스 행 없음 · 기록 건수 유지 → news는 빈 배열, evidenceCounts로 구분 가능 (D8)")
    void newsExpired() throws Exception {
        Scenario s = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 3, false);
        disclosure(s, s.date(), "테스트 전용 공시 제목");

        JsonNode data = detail(s);

        assertThat(data.get("news")).isEmpty();
        JsonNode counts = data.get("evidenceCounts");
        assertThat(counts.get("recorded").asInt()).isEqualTo(3);
        assertThat(counts.get("disclosure").asInt()).isEqualTo(1);
        assertThat(counts.get("news").asInt()).isZero();
    }

    @Test
    @DisplayName("afterClose (ANL-8) — 같은 날 접수 공시는 null, 다음 날 접수는 true / 같은 날 뉴스 10:00은 false, 16:00은 true")
    void afterClose() throws Exception {
        Scenario s = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 4, false);
        disclosure(s, s.date(), "테스트 전용 같은 날 공시");
        disclosure(s, s.date().plusDays(1), "테스트 전용 다음 날 공시");
        news(s, s.date().atTime(10, 0), "테스트 전용 장중 기사");
        news(s, s.date().atTime(16, 0), "테스트 전용 장 마감 후 기사");

        JsonNode data = detail(s);

        assertThat(data.get("disclosures").get(0).get("afterClose").isNull()).isTrue();
        assertThat(data.get("disclosures").get(1).get("afterClose").asBoolean()).isTrue();
        assertThat(data.get("news").get(0).get("afterClose").asBoolean()).isFalse();
        assertThat(data.get("news").get(1).get("afterClose").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("정렬 — 공개 날짜 오름차순, 같은 날은 시각 오름차순, 날짜를 모르는 자료는 맨 뒤")
    void ordering() throws Exception {
        Scenario s = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 6, false);
        disclosure(s, s.date().plusDays(1), "테스트 전용 공시 B");
        disclosure(s, s.date().minusDays(1), "테스트 전용 공시 A");
        news(s, s.date().atTime(16, 0), "테스트 전용 기사 2");
        news(s, null, "테스트 전용 기사 시각 없음");
        news(s, s.date().plusDays(1).atTime(9, 0), "테스트 전용 기사 3");
        news(s, s.date().atTime(10, 0), "테스트 전용 기사 1");

        JsonNode data = detail(s);

        assertThat(titles(data.get("disclosures"))).containsExactly("테스트 전용 공시 A", "테스트 전용 공시 B");
        assertThat(titles(data.get("news"))).containsExactly("테스트 전용 기사 1", "테스트 전용 기사 2",
                "테스트 전용 기사 3", "테스트 전용 기사 시각 없음");
        JsonNode unknown = data.get("news").get(3);
        assertThat(unknown.get("publishedDate").isNull()).isTrue();
        assertThat(unknown.get("afterClose").isNull()).isTrue();
    }

    @Test
    @DisplayName("남의 이벤트 · 없는 날짜는 같은 404 — 존재 여부를 알 수 없다")
    void notFound_sameBody() throws Exception {
        Scenario mine = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 0, false);
        Login other = newLogin();

        JsonNode othersView = errorBody(request(other, mine.ticker(), mine.date().toString()).andExpect(status().isNotFound()));
        JsonNode noDate = errorBody(request(mine.login(), mine.ticker(), mine.date().plusDays(1).toString())
                .andExpect(status().isNotFound()));

        assertThat(othersView.get("errorCode").asText()).isEqualTo("STOCK_EVENT_NOT_FOUND");
        assertThat(othersView.get("errorCode")).isEqualTo(noDate.get("errorCode"));
        assertThat(othersView.get("message")).isEqualTo(noDate.get("message"));
        assertThat(othersView.get("data").isNull()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] date={0}")
    @ValueSource(strings = {"2026-13-01", "20261116", "2026-02-30"})
    @DisplayName("날짜 형식 오류는 400")
    void badDate(String date) throws Exception {
        request(newLogin(), "000660", date).andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "[{index}] ticker={0}")
    @ValueSource(strings = {"00066a", "00066", "0006-0", "0006600"})
    @DisplayName("ticker 형식 오류(소문자 · 5자리 · 특수문자 · 7자리)는 400")
    void badTicker(String ticker) throws Exception {
        request(newLogin(), ticker, "2026-11-16").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없음은 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH, "000660", "2026-11-16")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비노출 — 토큰 · 분석 시각 키가 없고, 한도 · 예산 · 토큰 관련 문자열이 없다 (FR-AI-10, AGENTS 5-D)")
    void noUsageExposure() throws Exception {
        Scenario s = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 1, false);
        jdbc.update("update stock_event_analyses set llm_prompt_tokens = 120, llm_completion_tokens = 45, analyzed_at = ? where id = ?",
                LocalDateTime.now(), s.analysisId());
        news(s, s.date().atTime(10, 0), "테스트 전용 기사");

        String body = request(s.login(), s.ticker(), s.date().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> keys = new ArrayList<>();
        collectKeys(objectMapper.readTree(body), keys);
        assertThat(keys).doesNotContain("llmPromptTokens", "llmCompletionTokens", "analyzedAt");
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("한도", "quota", "budget", "token");
    }

    // 스파이 빈(@MockitoSpyBean)을 쓰면 이 클래스만 별도 컨텍스트가 생겨 테스트 MySQL 연결 수 한도를 넘는다 → 의존성 구조로 확인한다.
    // 서비스가 외부 클라이언트를 들고 있지 않으면 조회 중에 부를 수 없다
    @Test
    @DisplayName("외부 API 호출 없음 — 조회 서비스가 DART · 네이버 · 한투 · ECOS · OpenAI · CODEF 클라이언트에 의존하지 않는다")
    void noExternalClientDependency() {
        List<Class<?>> clients = List.of(DartApiClient.class, NaverNewsApiClient.class, KisApiClient.class,
                EcosApiClient.class, OpenAiApiClient.class, CodefApiClient.class);
        for (Constructor<?> constructor : StockEventDetailQueryService.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).doesNotContainAnyElementsOf(clients);
        }
        for (Field field : StockEventDetailQueryService.class.getDeclaredFields()) {
            assertThat(clients).doesNotContain(field.getType());
        }
    }

    @Test
    @DisplayName("쿼리 수 고정 — 근거 · 지난 이벤트가 늘어도 같은 수, 4회 이하 (N+1 없음)")
    void queryCount() {
        Scenario small = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 1, false);
        disclosure(small, small.date(), "테스트 전용 공시");
        Scenario large = scenario("ANALYZED", "RELATED_FOUND", "테스트 전용 요약", 5, false);
        for (int i = 0; i < 3; i++) {
            disclosure(large, large.date().minusDays(i), "테스트 전용 공시 " + i);
        }
        news(large, large.date().atTime(10, 0), "테스트 전용 기사 1");
        news(large, large.date().atTime(11, 0), "테스트 전용 기사 2");
        for (int i = 1; i <= 3; i++) {
            pastEvent(large, large.date().minusDays(i * 7L), "5.5000");
        }

        long smallCount = countStatements(small);
        long largeCount = countStatements(large);

        assertThat(smallCount).isLessThanOrEqualTo(4);
        assertThat(largeCount).isEqualTo(smallCount);
    }

    // ───────────────────────── 준비 도우미 (테스트 전용 더미 값) ─────────────────────────

    // 분석 · 내 이벤트 · 같은 날 KOSPI(-0.8) · KOSDAQ(-1.1). 날짜는 다른 테스트의 지수 행과 겹치지 않게 먼 미래 임의 날짜
    private Scenario scenario(String status, String evidenceStatus, String aiSummary, int evidenceCount, boolean backfilled) {
        Login login;
        try {
            login = newLogin();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String ticker = "Q9" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);
        LocalDate date = LocalDate.of(2041, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 3000));
        StockEventAnalysis analysis = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(ticker, "테스트 종목", date, new BigDecimal("-7.2300")));
        jdbc.update("""
                update stock_event_analyses set market_index_code = 'KOSPI', market_change_rate = -0.8000, excess_rate = -6.4300,
                       evidence_count = ?, evidence_status = ?, analysis_status = ?, ai_summary = ? where id = ?""",
                evidenceCount, evidenceStatus, status, aiSummary, analysis.getId());
        saveEvent(login.userId(), analysis, backfilled);
        indicator("KOSPI", date, "-0.8000");
        indicator("KOSDAQ", date, "-1.1000");
        return new Scenario(login, ticker, date, analysis.getId());
    }

    private void saveEvent(Long userId, StockEventAnalysis analysis, boolean backfilled) {
        tx.executeWithoutResult(st -> eventRepository.save(UserMarketEvent.create(userRepository.getReferenceById(userId),
                analysis, Direction.of(analysis.getChangeRate()), new BigDecimal("5"),
                backfilled ? null : new BigDecimal("-124000.00"), backfilled ? null : new BigDecimal("34.00"),
                backfilled ? null : new BigDecimal("-12.0000"), backfilled)));
    }

    private void pastEvent(Scenario s, LocalDate date, String changeRate) {
        StockEventAnalysis past = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(s.ticker(), "테스트 종목", date, new BigDecimal(changeRate)));
        saveEvent(s.login().userId(), past, false);
    }

    private void indicator(String code, LocalDate date, String changeRate) {
        String source = code.startsWith("KOS") ? "KIS" : "ECOS";
        jdbc.update("""
                insert into macro_indicators (indicator_code, base_date, value, change_rate, source, created_at, updated_at)
                values (?, ?, 1000.0000, ?, ?, now(), now())
                on duplicate key update change_rate = values(change_rate)""", code, date, new BigDecimal(changeRate), source);
    }

    // 공시 — 시각 없음(DART), 날짜는 접수번호 앞 8자리
    private void disclosure(Scenario s, LocalDate receiptDate, String title) {
        String receiptNo = receiptDate.format(RECEIPT_DATE) + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(st -> sourceRepository.insertIfAbsent(s.analysisId(), "DISCLOSURE", title, "DART", null,
                "https://dart.example.test/" + receiptNo, null, null, receiptNo, LocalDateTime.now()));
    }

    private void news(Scenario s, LocalDateTime publishedAt, String title) {
        String url = "https://news.example.test/" + UUID.randomUUID();
        tx.executeWithoutResult(st -> sourceRepository.insertIfAbsent(s.analysisId(), "NEWS", title, "news.example.test",
                publishedAt, url, "테스트 전용 요약문", 1, url, LocalDateTime.now()));
    }

    private Login newLogin() throws Exception {
        String email = signupNew();
        Long userId = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow().getId();
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Login(userId, objectMapper.readTree(body).get("data").get("accessToken").asText());
    }

    private ResultActions request(Login login, String ticker, String date) throws Exception {
        return mockMvc.perform(get(PATH, ticker, date).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()));
    }

    private JsonNode detail(Scenario s) throws Exception {
        String body = request(s.login(), s.ticker(), s.date().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode errorBody(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long countStatements(Scenario s) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            queryService.getDetail(s.login().userId(), s.ticker(), s.date());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static List<String> titles(JsonNode array) {
        List<String> titles = new ArrayList<>();
        array.forEach(n -> titles.add(n.get("title").asText()));
        return titles;
    }

    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                keys.add(name);
                collectKeys(node.get(name), keys);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }
}
