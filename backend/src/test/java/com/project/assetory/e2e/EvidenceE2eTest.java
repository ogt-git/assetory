package com.project.assetory.e2e;

import com.project.assetory.analysis.dart.DartApiClient;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult;
import com.project.assetory.analysis.service.EvidenceCollectResult.Outcome;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.ecos.EcosApiClient;
import com.project.assetory.market.service.MacroIndicatorCollectResult;
import com.project.assetory.market.service.MacroIndicatorCollectService;
import com.project.assetory.support.IntegrationTestSupport;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static com.project.assetory.e2e.FakeKisServer.Day;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 근거 · 지표 E2E (B5 Phase 3) — 실제 DartApiClient · NaverNewsApiClient · EcosApiClient · KisApiClient가
 * 가짜 서버(FakeDart · FakeNaverNews · FakeEcos · FakeKis)로 진짜 HTTP 요청을 보낸다. MockRestServiceServer로는 못 보던
 * URL 조립 · 헤더 · 경로 키 · 한글 인코딩 · ZIP 수신을 확인한다. 키는 모두 테스트 전용 더미 값이다.
 * 고유번호 캐시(DartCorpCodeCache)는 컨텍스트에 하나라 같은 날 1회만 받는다 — ZIP 수신 확인을 먼저(@Order(1)) 하고,
 * 회사 목록은 모든 테스트에서 같게 둔다.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvidenceE2eTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 테스트 전용 더미 키 — 실제 키가 아니다. 서버는 받지만 애플리케이션 밖(로그 · 메트릭 · 예외)으로는 나가면 안 된다
    private static final String DART_KEY = "fake-only-e2e-dart-key-31c7";
    private static final String NAVER_ID = "fake-only-e2e-naver-id-8a20";
    private static final String NAVER_SECRET = "fake-only-e2e-naver-secret-5b94";
    private static final String ECOS_KEY = "fake-only-e2e-ecos-key-77e1";
    private static final String KIS_KEY = "fake-only-e2e-kis-key";
    private static final String KIS_SECRET = "fake-only-e2e-kis-secret";

    private static final String TICKER = "999901";        // 테스트 전용 더미 종목코드
    private static final String CORP_CODE = "00999901";   // 테스트 전용 더미 고유번호
    private static final String NAME = "테스트 전자";       // 공백 포함 — 검색어 인코딩 확인용

    private static final FakeDartServer DART = new FakeDartServer(DART_KEY);
    private static final FakeNaverNewsServer NAVER = new FakeNaverNewsServer(NAVER_ID, NAVER_SECRET);
    private static final FakeEcosServer ECOS = new FakeEcosServer(ECOS_KEY);
    private static final FakeKisServer KIS = new FakeKisServer();

    @DynamicPropertySource
    static void fakeServers(DynamicPropertyRegistry registry) {
        registry.add("assetory.dart.base-url", DART::baseUrl);
        registry.add("assetory.dart.api-key", () -> DART_KEY);
        registry.add("assetory.naver.base-url", NAVER::baseUrl);
        registry.add("assetory.naver.client-id", () -> NAVER_ID);
        registry.add("assetory.naver.client-secret", () -> NAVER_SECRET);
        registry.add("assetory.ecos.base-url", ECOS::baseUrl);
        registry.add("assetory.ecos.api-key", () -> ECOS_KEY);
        registry.add("assetory.kis.base-url", KIS::baseUrl);
        registry.add("assetory.kis.app-key", () -> KIS_KEY);
        registry.add("assetory.kis.app-secret", () -> KIS_SECRET);
        registry.add("assetory.kis.call-interval-ms", () -> "0");
    }

    @AfterAll
    static void stopServers() {
        DART.stop();
        NAVER.stop();
        ECOS.stop();
        KIS.stop();
    }

    @Autowired EvidenceCollectService evidenceCollectService;
    @Autowired MacroIndicatorCollectService macroIndicatorCollectService;
    @Autowired DartApiClient dartApiClient;
    @Autowired EcosApiClient ecosApiClient;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired EventSourceRepository eventSourceRepository;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DART.reset();
        NAVER.reset();
        ECOS.reset();
        KIS.reset();
        DART.corp(TICKER, CORP_CODE, "테스트전자");
    }

    @Test
    @Order(1)
    @DisplayName("4. 고유번호 ZIP을 실제 HTTP로 받아 해석 — 1회 수신 뒤 캐시, 그 고유번호로 공시 목록 조회")
    void corpCodeZip_overHttp() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        DART.disclosure(CORP_CODE, "B", "20261102900001", "주요사항보고서(테스트 결정)", date);

        evidenceCollectService.collect(newAnalysis(date).getId());
        evidenceCollectService.collect(newAnalysis(date.plusDays(1)).getId());

        assertThat(DART.requests("corp-code")).isEqualTo(1);
        assertThat(DART.lastQuery("corp-code")).contains("crtfc_key=" + DART_KEY);
        assertThat(DART.lastQuery("list")).contains("corp_code=" + CORP_CODE);
        assertThat(eventSourceRepository.findAll()).extracting(EventSource::getExternalId).contains("20261102900001");
    }

    @Test
    @Order(2)
    @DisplayName("1 · 5. 공시 + 뉴스 → COLLECTING, DISCLOSURE · NEWS 행, 뉴스 제목 원문 그대로 · 공백 섞인 종목명이 서버에서 원래대로 디코딩")
    void disclosureAndNews_collecting() {
        LocalDate date = LocalDate.of(2026, 11, 5);
        DART.disclosure(CORP_CODE, "B", "20261105900001", "주요사항보고서(자기주식취득결정)", date);
        NAVER.article(NAME, "<b>테스트 전자</b>, 자사주 &quot;매입&quot;", "https://press.example.com/1",
                "https://n.news.naver.com/1", "테스트 요약", ZonedDateTime.of(2026, 11, 5, 16, 20, 0, 0, KST));
        NAVER.article(NAME, "업종 동향", "https://press.example.com/2", "https://n.news.naver.com/2", "요약",
                ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, KST));
        StockEventAnalysis analysis = newAnalysis(date);

        EvidenceCollectResult result = evidenceCollectService.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
        List<EventSource> rows = eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId());
        assertThat(rows).extracting(EventSource::getSourceType).containsExactly(SourceType.NEWS, SourceType.DISCLOSURE);
        assertThat(rows.get(0).getTitle()).isEqualTo("<b>테스트 전자</b>, 자사주 &quot;매입&quot;");
        assertThat(rows.get(0).getSourceName()).isEqualTo("press.example.com");
        assertThat(rows.get(1).getUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261105900001");
        assertThat(NAVER.lastQuery()).isEqualTo(NAME);
        assertThat(DART.requests("list")).isEqualTo(3); // 유형 B · I · D
    }

    @Test
    @Order(3)
    @DisplayName("2. DART 키 오류(010) → 목록 1회만 부르고 멈춤, 뉴스는 성공 → B1 규칙 그대로 COLLECTING(뉴스만)")
    void dartKeyError_newsOnly() {
        LocalDate date = LocalDate.of(2026, 11, 9);
        DART.mode(FakeDartServer.Mode.BAD_KEY);
        NAVER.article(NAME, "<b>테스트 전자</b> 급등", "https://press.example.com/9", "https://n.news.naver.com/9", "요약",
                ZonedDateTime.of(2026, 11, 9, 11, 0, 0, 0, KST));
        StockEventAnalysis analysis = newAnalysis(date);

        EvidenceCollectResult result = evidenceCollectService.collect(analysis.getId());

        assertThat(DART.requests("list")).isEqualTo(1);
        assertThat(result.runs()).filteredOn(r -> r.sourceType() == SourceType.DISCLOSURE)
                .singleElement().satisfies(r -> assertThat(r.success()).isFalse());
        assertThat(eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId()))
                .extracting(EventSource::getSourceType).containsExactly(SourceType.NEWS);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @Order(4)
    @DisplayName("3. 모든 수집기 실패(DART 010 · 네이버 401) → PENDING, 저장 0")
    void allFail_pending() {
        LocalDate date = LocalDate.of(2026, 11, 10);
        DART.mode(FakeDartServer.Mode.BAD_KEY);
        NAVER.mode(FakeNaverNewsServer.Mode.UNAUTHORIZED);
        StockEventAnalysis analysis = newAnalysis(date);

        EvidenceCollectResult result = evidenceCollectService.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(NAVER.requests()).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("6. 지수 · 지표 수집 — ECOS 경로 키 · 한투 토큰이 실려 가고 macro_indicators에 KOSPI · KOSDAQ · BASE_RATE · USD_KRW 저장")
    void macroIndicators_overHttp() {
        LocalDate base = LocalDate.of(2019, 3, 13);
        KIS.indexes("0001", new Day(base, new BigDecimal("2150.00")), new Day(base.minusDays(1), new BigDecimal("2100.00")));
        KIS.indexes("1001", new Day(base, new BigDecimal("750.00")), new Day(base.minusDays(1), new BigDecimal("740.00")));
        ECOS.value("722Y001", "0101000", base.minusDays(1), new BigDecimal("1.75"));
        ECOS.value("722Y001", "0101000", base, new BigDecimal("1.75"));
        ECOS.value("731Y001", "0000001", base.minusDays(1), new BigDecimal("1130"));
        ECOS.value("731Y001", "0000001", base, new BigDecimal("1135.65"));

        MacroIndicatorCollectResult result = macroIndicatorCollectService.collect(base);

        for (IndicatorCode code : IndicatorCode.values()) {
            assertThat(result.outcome(code).success()).as(code.name()).isTrue();
        }
        assertThat(ECOS.requests()).isEqualTo(2);
        assertThat(KIS.requests("index-daily")).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                select concat(indicator_code, ':', value, ':', coalesce(change_rate, 'null')) from macro_indicators
                where base_date = ? order by indicator_code""", String.class, base))
                .containsExactly("BASE_RATE:1.7500:null", "KOSDAQ:750.0000:1.3514", "KOSPI:2150.0000:2.3810", "USD_KRW:1135.6500:0.5000");
    }

    @Test
    @Order(6)
    @DisplayName("7. 키 유출 없음 — 서버는 키를 받았지만 로그 · 메트릭 태그 · 던져진 예외 메시지에는 더미 키가 없다")
    void noKeyLeak(CapturedOutput output) {
        DART.mode(FakeDartServer.Mode.BAD_KEY);
        ECOS.mode(FakeEcosServer.Mode.BAD_KEY);
        NAVER.mode(FakeNaverNewsServer.Mode.UNAUTHORIZED);
        evidenceCollectService.collect(newAnalysis(LocalDate.of(2026, 11, 11)).getId());
        Throwable dart = catchThrowable(() -> dartApiClient.list(CORP_CODE, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "B"));
        Throwable ecos = catchThrowable(() -> ecosApiClient.search("731Y001", "0000001", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12)));
        DART.mode(FakeDartServer.Mode.NETWORK_DOWN);
        ECOS.mode(FakeEcosServer.Mode.NETWORK_DOWN);
        Throwable dartDown = catchThrowable(() -> dartApiClient.list(CORP_CODE, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "B"));
        Throwable ecosDown = catchThrowable(() -> ecosApiClient.search("731Y001", "0000001", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12)));

        assertThat(DART.lastQuery("list")).contains(DART_KEY); // 서버는 키를 받았다
        List<String> keys = List.of(DART_KEY, NAVER_ID, NAVER_SECRET, ECOS_KEY);
        for (Throwable t : List.of(dart, ecos, dartDown, ecosDown)) {
            assertThat(t).isNotNull();
            for (Throwable c = t; c != null; c = c.getCause()) {
                for (String key : keys) assertThat(c.toString()).doesNotContain(key);
            }
        }
        for (String key : keys) {
            assertThat(output.getAll()).doesNotContain(key);
            for (Meter meter : meterRegistry.getMeters()) {
                assertThat(meter.getId().toString()).doesNotContain(key);
            }
        }
    }

    private StockEventAnalysis newAnalysis(LocalDate date) {
        // 같은 (종목, 날짜) 분석은 한 번만 만들 수 있어 종목명은 고정, 날짜로 구분한다. 이전 실행이 남긴 행은 지운다
        jdbc.update("delete from event_sources where analysis_id in (select id from stock_event_analyses where ticker = ? and event_date = ?)",
                TICKER, date);
        jdbc.update("delete from stock_event_analyses where ticker = ? and event_date = ?", TICKER, date);
        return analysisRepository.saveAndFlush(StockEventAnalysis.create(TICKER, NAME, date, new BigDecimal("-6.1")));
    }

    private StockEventAnalysis reload(StockEventAnalysis analysis) {
        return analysisRepository.findById(analysis.getId()).orElseThrow();
    }
}
