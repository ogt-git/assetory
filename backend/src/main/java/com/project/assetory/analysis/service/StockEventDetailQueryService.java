package com.project.assetory.analysis.service;

import com.project.assetory.analysis.config.AnalysisProperties;
import com.project.assetory.analysis.dart.DartReceiptNo;
import com.project.assetory.analysis.domain.AfterCloseCalculator;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.dto.StockEventDetailResponse;
import com.project.assetory.analysis.exception.StockEventNotFoundException;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.market.config.MarketCloseProperties;
import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.repository.MacroIndicatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 원인 분석 상세 조회 (S-04, ANL-9, Q-9) — 저장값만 읽는다(AGENTS 5-C). 외부 API · LLM 클라이언트를 쓰지 않는다.
 * 쿼리는 근거 · 지난 이벤트 수와 무관하게 4회: ① 내 이벤트 + 분석 ② 근거 전체 ③ 같은 날 KOSPI · KOSDAQ ④ 지난 이벤트.
 * afterClose(ANL-8)만 저장값으로 계산한다 — 입력이 모두 저장값인 순수 계산
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockEventDetailQueryService {

    private static final List<IndicatorCode> INDICES = List.of(IndicatorCode.KOSPI, IndicatorCode.KOSDAQ);

    private final UserMarketEventRepository userMarketEventRepository;
    private final EventSourceRepository eventSourceRepository;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final AnalysisProperties analysisProperties;
    private final MarketCloseProperties marketCloseProperties;

    public StockEventDetailResponse getDetail(Long userId, String ticker, LocalDate date) {
        UserMarketEvent event = userMarketEventRepository.findWithAnalysisByUserIdAndTickerAndEventDate(userId, ticker, date)
                .orElseThrow(StockEventNotFoundException::new);
        StockEventAnalysis analysis = event.getAnalysis();
        List<EventSource> sources = eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId());
        Map<IndicatorCode, BigDecimal> indexRates = indexRates(date);
        LocalTime closeTime = marketCloseProperties.closeTime(date);

        List<StockEventDetailResponse.Disclosure> disclosures = sources.stream()
                .filter(s -> s.getSourceType() == SourceType.DISCLOSURE)
                .map(s -> new Timed(s, receiptDate(s)))
                .sorted(TIMELINE)
                .map(t -> new StockEventDetailResponse.Disclosure(t.source().getTitle(), t.source().getSourceName(), t.date(),
                        t.source().getPublishedAt(), t.source().getUrl(), afterClose(t, date, closeTime)))
                .toList();
        List<StockEventDetailResponse.News> news = sources.stream()
                .filter(s -> s.getSourceType() == SourceType.NEWS)
                .map(s -> new Timed(s, s.getPublishedAt() == null ? null : s.getPublishedAt().toLocalDate()))
                .sorted(TIMELINE)
                .map(t -> new StockEventDetailResponse.News(t.source().getTitle(), t.source().getSnippet(),
                        t.source().getSourceName(), t.date(), t.source().getPublishedAt(), t.source().getUrl(),
                        afterClose(t, date, closeTime)))
                .toList();
        List<StockEventDetailResponse.PastEvent> pastEvents = userMarketEventRepository
                .findPastEvents(userId, ticker, date, Limit.of(analysisProperties.pastEventsMax())).stream()
                .map(p -> new StockEventDetailResponse.PastEvent(p.eventDate(), p.direction(), p.changeRate()))
                .toList();

        return new StockEventDetailResponse(
                new StockEventDetailResponse.Event(analysis.getTicker(), analysis.getStockName(), event.getEventDate(),
                        event.getDirection(), analysis.getChangeRate(), event.getChangeAmount(), event.getPositionRatio(),
                        event.getProfitLossRate(), event.isBackfilled()),
                new StockEventDetailResponse.Analysis(analysis.getAnalysisStatus(), analysis.getEvidenceStatus(),
                        analysis.getAnalysisStatus() == AnalysisStatus.ANALYZED ? analysis.getAiSummary() : null),
                new StockEventDetailResponse.Market(analysis.getMarketIndexCode(), analysis.getMarketChangeRate(),
                        analysis.getExcessRate(), indexRates.get(IndicatorCode.KOSPI), indexRates.get(IndicatorCode.KOSDAQ)),
                new StockEventDetailResponse.EvidenceCounts(analysis.getEvidenceCount(), disclosures.size(), news.size()),
                disclosures, news, pastEvents);
    }

    // 정렬 대상 — 공개 날짜(공시는 접수일, 뉴스는 게시 시각의 날짜)를 한 번만 구해 둔다
    private record Timed(EventSource source, LocalDate date) {}

    // 공개 날짜 오름차순(모르면 맨 뒤) → 같은 날은 시각 오름차순(시각 없으면 그날 맨 뒤 — 장 마감 전 자료처럼 위에 놓이지 않게) → id
    private static final Comparator<Timed> TIMELINE = Comparator
            .comparing(Timed::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(t -> t.source().getPublishedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(t -> t.source().getId());

    private static LocalDate receiptDate(EventSource source) {
        return DartReceiptNo.isValid(source.getExternalId()) ? DartReceiptNo.dateOf(source.getExternalId()) : null;
    }

    // 날짜 · 시각을 모두 모르면 판정하지 않는다(null) — 화면은 시각 미제공으로 보여준다
    private static Boolean afterClose(Timed timed, LocalDate eventDate, LocalTime closeTime) {
        LocalDateTime publishedAt = timed.source().getPublishedAt();
        if (publishedAt == null && timed.date() == null) {
            return null;
        }
        return AfterCloseCalculator.afterClose(timed.date(), publishedAt, eventDate, closeTime);
    }

    private Map<IndicatorCode, BigDecimal> indexRates(LocalDate date) {
        Map<IndicatorCode, BigDecimal> rates = new EnumMap<>(IndicatorCode.class);
        for (MacroIndicator indicator : macroIndicatorRepository.findAllByIndicatorCodeInAndBaseDate(INDICES, date)) {
            if (indicator.getChangeRate() != null) {
                rates.put(indicator.getIndicatorCode(), indicator.getChangeRate());
            }
        }
        return rates;
    }
}
