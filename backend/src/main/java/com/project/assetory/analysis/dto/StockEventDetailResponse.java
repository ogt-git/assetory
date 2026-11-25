package com.project.assetory.analysis.dto;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.EvidenceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 원인 분석 상세 (S-04, ANL-9, Q-9) — 저장값만 담는다. 사용자용 문구는 만들지 않는다(상태 · 건수만, 문구는 화면 템플릿).
 * LLM 토큰 · 한도 · 비용 정보는 넣지 않는다(FR-AI-10, AGENTS 5-D)
 */
@Schema(description = "원인 분석 상세 — 내 이벤트 · 공유 분석 · 시장 비교 · 근거(공시 · 같은 날 보도) · 지난 이벤트")
public record StockEventDetailResponse(
        Event event,
        Analysis analysis,
        Market market,
        EvidenceCounts evidenceCounts,
        @Schema(description = "공시 — 공개 날짜 · 시각 오름차순, 시각 없는 자료는 그날 맨 뒤, 날짜를 모르면 맨 뒤") List<Disclosure> disclosures,
        @Schema(description = "같은 날 보도(네이버 뉴스) — 정렬은 공시와 같다. 보관 기간이 지나 삭제되면 빈 목록") List<News> news,
        @Schema(description = "같은 종목의 지난 이벤트 — 이 이벤트보다 앞선 날짜, 최신순 최대 N건(설정값), 소급 포함") List<PastEvent> pastEvents
) {

    @Schema(name = "StockEventDetailEvent", description = "내 이벤트 — 소급 이벤트(backfilled)는 금액 3개가 null(과거 보유수량을 알 수 없음)")
    public record Event(
            @Schema(example = "000660") String ticker,
            @Schema(example = "테스트 종목") String stockName,
            @Schema(example = "2026-11-16") LocalDate eventDate,
            @Schema(description = "UP / DOWN", example = "DOWN") Direction direction,
            @Schema(description = "종목 변동률(%) — 16:00 배치의 잠정값", example = "-7.2300") BigDecimal changeRate,
            @Schema(description = "내 평가금액 변동(원). 소급이면 null", example = "-124000.00") BigDecimal changeAmount,
            @Schema(description = "내 총자산 대비 비중(%). 소급이면 null", example = "34.00") BigDecimal positionRatio,
            @Schema(description = "원금 대비 손익(%). 소급이면 null", example = "-12.0000") BigDecimal profitLossRate,
            @Schema(description = "연동 전 이벤트(소급) 여부", example = "false") boolean backfilled) {}

    @Schema(name = "StockEventDetailAnalysis", description = "공유 분석 — 요약은 ANALYZED일 때만")
    public record Analysis(
            @Schema(description = "PENDING / COLLECTING / ANALYZED / NO_EVIDENCE / SUMMARY_SKIPPED / FAILED", example = "ANALYZED")
            AnalysisStatus analysisStatus,
            @Schema(description = "RELATED_FOUND / MARKET_MOVE / NOT_FOUND. 판정 전이면 null", example = "RELATED_FOUND")
            EvidenceStatus evidenceStatus,
            @Schema(description = "원인 후보 요약. ANALYZED가 아니면 null", example = "같은 시점에 … 공시가 있었습니다.") String aiSummary) {}

    @Schema(name = "StockEventDetailMarket", description = "시장 비교 — 저장값만. 다른 지수 기준의 초과 변동은 계산하지 않는다")
    public record Market(
            @Schema(description = "비교 대상 지수 KOSPI / KOSDAQ. 시장 구분을 모르면 null", example = "KOSPI") String indexCode,
            @Schema(description = "대상 지수의 같은 날 변동률(%). 없으면 null", example = "-0.8000") BigDecimal indexChangeRate,
            @Schema(description = "시장 대비 초과 변동(%p). 없으면 null", example = "-6.4300") BigDecimal excessRate,
            @Schema(description = "KOSPI 같은 날 변동률(%) — 토글용. 없으면 null", example = "-0.8000") BigDecimal kospiChangeRate,
            @Schema(description = "KOSDAQ 같은 날 변동률(%) — 토글용. 없으면 null", example = "-1.1000") BigDecimal kosdaqChangeRate) {}

    @Schema(name = "StockEventDetailEvidenceCounts",
            description = "근거 건수 — recorded는 수집 때 기록한 건수, disclosure · news는 지금 남아 있는 행 수. "
                    + "news가 0이고 recorded > disclosure이면 뉴스 보관 기간이 지난 것")
    public record EvidenceCounts(
            @Schema(example = "3") int recorded,
            @Schema(example = "1") int disclosure,
            @Schema(example = "2") int news) {}

    @Schema(name = "StockEventDetailDisclosure", description = "공시 — DART는 공개 시각을 주지 않아 publishedAt이 null")
    public record Disclosure(
            @Schema(example = "주요사항보고서(자기주식취득결정)") String title,
            @Schema(example = "DART") String sourceName,
            @Schema(description = "접수일(접수번호 앞 8자리). 알 수 없으면 null", example = "2026-11-16") LocalDate publishedDate,
            @Schema(description = "공개 시각. DART는 null(시각 미제공)") LocalDateTime publishedAt,
            @Schema(example = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261116000001") String url,
            @Schema(description = "이벤트 날 장 마감 이후 공개 여부. 같은 날 · 시각 없음이면 null(판정 불가)") Boolean afterClose) {}

    @Schema(name = "StockEventDetailNews", description = "같은 날 보도 — 네이버 뉴스 검색 결과 그대로. sourceName은 원문 링크의 host")
    public record News(
            @Schema(example = "기사 제목") String title,
            @Schema(example = "기사 요약") String snippet,
            @Schema(example = "www.example.com") String sourceName,
            @Schema(description = "게시일. 알 수 없으면 null", example = "2026-11-16") LocalDate publishedDate,
            @Schema(description = "게시 시각. 알 수 없으면 null", example = "2026-11-16T10:32:00") LocalDateTime publishedAt,
            @Schema(example = "https://www.example.com/article/1") String url,
            @Schema(description = "이벤트 날 장 마감 이후 공개 여부. 판정할 수 없으면 null", example = "false") Boolean afterClose) {}

    @Schema(name = "StockEventDetailPastEvent", description = "지난 이벤트 한 줄")
    public record PastEvent(
            @Schema(example = "2026-10-28") LocalDate eventDate,
            @Schema(example = "UP") Direction direction,
            @Schema(example = "6.1000") BigDecimal changeRate) {}
}
