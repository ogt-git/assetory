package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.config.EvidenceProperties;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.evidence.EvidenceCollector;
import com.project.assetory.analysis.evidence.EvidenceTarget;
import com.project.assetory.analysis.exception.EvidenceCollectException;
import com.project.assetory.analysis.exception.NaverApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 네이버 뉴스 근거 수집기 (FR-CA-02, B3). 키가 없어도 빈으로 등록된다 — 호출 시 실패해 분석이 PENDING으로 남는다(확정 사항 1).
 * 뉴스는 LLM 입력 대상이 아니다(SourceType.NEWS) — 원문 그대로 저장하고 표시 전용으로 쓴다(B0 A안).
 * - ETF는 수집하지 않는다
 * - 검색어 = 종목명, 제목 필터(비교용 태그 제거 · 엔티티 디코드) — TODO(01-§14 #10) B0 N4 · N5로 확정
 * - 기간 [이벤트일 − days-before, 이벤트일 + days-after] KST 달력일(assetory.evidence.news). 게시 시각으로 자르지 않는다(#7)
 * - 상한에 걸려 기간 시작까지 못 내려가면 실패가 아니라 받은 것까지로 끝내고 WARN(종목 · 이벤트일 · 받은 건수만, 확정 사항 6)
 * - 한 페이지라도 실패하면 전체 실패 — 일부 근거만 돌려주지 않는다(확정 사항 7)
 */
@Slf4j
@Component
public class NaverNewsCollector implements EvidenceCollector {

    private final NaverNewsApiClient client;
    private final EvidenceProperties evidenceProperties;

    public NaverNewsCollector(NaverNewsApiClient client, EvidenceProperties evidenceProperties) {
        this.client = client;
        this.evidenceProperties = evidenceProperties;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.NEWS;
    }

    @Override
    public boolean supports(EvidenceTarget target) {
        return !target.etf();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceTarget target) {
        EvidenceProperties.Window window = evidenceProperties.news();
        LocalDate from = target.eventDate().minusDays(window.daysBefore());
        LocalDate to = target.eventDate().plusDays(window.daysAfter());
        NaverNewsPages pages;
        try {
            pages = client.fetchSince(target.stockName(), from);
        } catch (NaverApiException e) {
            // NaverApiException 메시지는 유형 · HTTP 상태 · errorCode뿐이라 cause로 붙여도 키가 없다
            throw new EvidenceCollectException(SourceType.NEWS, e.getErrorType().name(), e);
        }
        if (pages.limitReached()) {
            log.warn("[NAVER] 상한 도달 — 기간 시작까지 못 내려감 ticker={} eventDate={} received={}",
                    target.ticker(), target.eventDate(), pages.items().size());
        }
        return NaverNewsSelector.toEvidences(pages.items(), target.stockName(), from, to);
    }
}
