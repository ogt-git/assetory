package com.project.assetory.fake;

import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.e2e.FakeDartServer;
import com.project.assetory.e2e.FakeEcosServer;
import com.project.assetory.e2e.FakeKisServer;
import com.project.assetory.e2e.FakeNaverNewsServer;
import com.project.assetory.fake.DemoScenario.DemoArticle;
import com.project.assetory.fake.DemoScenario.DemoDisclosure;
import com.project.assetory.fake.DemoScenario.SeriesPoint;
import com.project.assetory.market.service.MacroIndicatorCollectResult;
import com.project.assetory.market.service.MacroIndicatorCollectService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * fake 프로필 근거 · 지수 데모 (B5) — 스프링 빈이 아니다. FakeSeedConfig가 만들고 DemoDataSeeder가 순서대로 부른다.
 * DB에 직접 넣지 않고, 가짜 서버(한투 · ECOS · DART · 네이버)에 데모 데이터를 등록한 뒤 **실제 수집 서비스**를 돌려 넣는다(확정 사항 1).
 * 수집 흐름(EvidenceCollectService) 호출은 시드 · 테스트에만 있다 — 운영 코드에는 호출부가 없다.
 */
@Slf4j
public class DemoEvidenceSeeder {

    // 업종코드 · ECOS 통계 — 운영 설정 기본값(assetory.kis.index-codes) · 수집 서비스와 같은 값
    private static final Map<String, String> INDEX_CODES = Map.of("KOSPI", "0001", "KOSDAQ", "1001");
    private static final Map<String, String[]> ECOS_SERIES = Map.of(
            "BASE_RATE", new String[]{"722Y001", "0101000"},
            "USD_KRW", new String[]{"731Y001", "0000001"});

    private final FakeKisServer kisServer;
    private final FakeEcosServer ecosServer;
    private final FakeDartServer dartServer;
    private final FakeNaverNewsServer naverServer;
    private final MacroIndicatorCollectService macroIndicatorCollectService;
    private final EvidenceCollectService evidenceCollectService;
    private final StockEventAnalysisRepository analysisRepository;

    public DemoEvidenceSeeder(FakeKisServer kisServer, FakeEcosServer ecosServer, FakeDartServer dartServer,
                              FakeNaverNewsServer naverServer, MacroIndicatorCollectService macroIndicatorCollectService,
                              EvidenceCollectService evidenceCollectService, StockEventAnalysisRepository analysisRepository) {
        this.kisServer = kisServer;
        this.ecosServer = ecosServer;
        this.dartServer = dartServer;
        this.naverServer = naverServer;
        this.macroIndicatorCollectService = macroIndicatorCollectService;
        this.evidenceCollectService = evidenceCollectService;
        this.analysisRepository = analysisRepository;
    }

    /**
     * 지수 · 지표 — 가짜 한투에 KOSPI · KOSDAQ, 가짜 ECOS에 기준금리 · 원/달러(데모 기간 전체) 등록 →
     * 실제 MacroIndicatorCollectService를 마지막 기록일과 demo1 이벤트일마다 실행(45일 창이 이벤트일 · 전일을 덮는다)
     * @return 저장 행 수 합계
     */
    public int seedIndicators(DemoScenario scenario) {
        scenario.indexes().forEach((code, series) -> kisServer.indexes(INDEX_CODES.get(code), latestFirst(series)));
        scenario.macro().forEach((code, series) -> {
            String[] stat = ECOS_SERIES.get(code);
            series.forEach(p -> ecosServer.value(stat[0], stat[1], p.date(), p.value()));
        });
        Set<LocalDate> runDates = new LinkedHashSet<>(scenario.detectionDates());
        runDates.add(scenario.calendar().get(scenario.calendar().size() - 1));
        int saved = 0;
        for (LocalDate date : runDates) {
            MacroIndicatorCollectResult result = macroIndicatorCollectService.collect(date);
            for (MacroIndicatorCollectResult.Outcome outcome : result.outcomes().values()) {
                if (!outcome.success()) {
                    log.warn("[FakeSeed] 지수 · 지표 수집 실패 date={} 결과={}", date, result.outcomes());
                }
                saved += outcome.saved();
            }
        }
        return saved;
    }

    /**
     * 근거 — 가짜 DART(고유번호 · 공시) · 네이버(기사) 등록 → 감지가 만든 demo1 이벤트 분석마다 실제 EvidenceCollectService 실행.
     * 감지(A6) 뒤에 불러야 분석 행이 있다(확정 사항 5)
     * @return 이벤트별 결과 요약 (로그용)
     */
    public List<String> seedEvidence(DemoScenario scenario) {
        scenario.corpCodes().forEach((ticker, corpCode) -> dartServer.corp(ticker, corpCode, DemoScenario.stockName(ticker)));
        for (DemoDisclosure d : scenario.disclosures()) {
            dartServer.disclosure(scenario.corpCode(d.ticker()), d.type(), d.receiptNo(), d.reportName(), d.receiptDate());
        }
        for (DemoArticle a : scenario.articles()) {
            naverServer.article(a.query(), a.title(), a.originallink(), a.link(), a.description(), a.pubDate());
        }
        List<String> summaries = new ArrayList<>();
        // 급락일에는 두 종목(035720 공시 · 069500 시장 동반 — Q-2)이 있어 날짜 키 맵이 아니라 목록으로 둔다
        for (Map.Entry<LocalDate, String> event : List.of(
                Map.entry(scenario.surgeDate(), DemoScenario.SURGE_TICKER),
                Map.entry(scenario.plungeDate(), DemoScenario.PLUNGE_TICKER),
                Map.entry(scenario.plungeDate(), DemoScenario.MARKET_MOVE_TICKER),
                Map.entry(scenario.recentSurgeDate(), DemoScenario.RECENT_SURGE_TICKER))) {
            StockEventAnalysis analysis = analysisRepository.findByTickerAndEventDate(event.getValue(), event.getKey()).orElse(null);
            if (analysis == null) {
                log.warn("[FakeSeed] 근거 수집 대상 분석 없음 ticker={} date={} — 감지 시드를 확인", event.getValue(), event.getKey());
                continue;
            }
            EvidenceCollectResult result = evidenceCollectService.collect(analysis.getId());
            summaries.add(event.getValue() + "@" + event.getKey() + "=" + result.status() + "(" + result.savedCount() + ")");
        }
        return summaries;
    }

    private static FakeKisServer.Day[] latestFirst(List<SeriesPoint> series) {
        FakeKisServer.Day[] days = new FakeKisServer.Day[series.size()];
        for (int i = 0; i < series.size(); i++) {
            SeriesPoint p = series.get(series.size() - 1 - i);
            days[i] = new FakeKisServer.Day(p.date(), p.value());
        }
        return days;
    }
}
