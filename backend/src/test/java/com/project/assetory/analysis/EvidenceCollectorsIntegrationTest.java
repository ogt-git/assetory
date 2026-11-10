package com.project.assetory.analysis;

import com.project.assetory.analysis.dart.DartApiClient;
import com.project.assetory.analysis.dart.DartCorpCodeCache;
import com.project.assetory.analysis.dart.DartDisclosure;
import com.project.assetory.analysis.dart.DartErrorType;
import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.exception.DartApiException;
import com.project.assetory.analysis.exception.NaverApiException;
import com.project.assetory.analysis.naver.NaverErrorType;
import com.project.assetory.analysis.naver.NaverNewsApiClient;
import com.project.assetory.analysis.naver.NaverNewsItem;
import com.project.assetory.analysis.naver.NaverNewsPages;
import com.project.assetory.analysis.repository.EventSourceRepository;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectResult;
import com.project.assetory.analysis.service.EvidenceCollectResult.Outcome;
import com.project.assetory.analysis.service.EvidenceCollectService;
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
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 운영 수집기 2개(DART 공시 · 네이버 뉴스)와 B1 수집 흐름 (B3 Phase 3). 실제 외부 API는 부르지 않는다 — 클라이언트 · 캐시를 목으로 둔다.
 * 수집기 하나만 실패한 경우는 B1 규칙(성공한 근거만 저장하고 진행)을 그대로 고정한다 — 한계는 보고서에 기록(확정 사항 9).
 */
class EvidenceCollectorsIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final String CORP_CODE = "00999901"; // 테스트 전용 더미 고유번호
    private static final String STOCK_NAME = "테스트전자"; // 테스트 전용 더미 종목명
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호

    @MockitoBean DartApiClient dartApiClient;
    @MockitoBean DartCorpCodeCache corpCodeCache;
    @MockitoBean NaverNewsApiClient naverNewsApiClient;

    @Autowired EvidenceCollectService service;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired EventSourceRepository eventSourceRepository;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;

    @Test
    @DisplayName("1. ETF → 공시 · 뉴스 수집기 모두 부르지 않는다, NO_EVIDENCE")
    void etf_noCalls() {
        StockEventAnalysis analysis = newAnalysis();
        etfHolding(analysis.getTicker());

        EvidenceCollectResult result = service.collect(analysis.getId());

        verifyNoInteractions(dartApiClient, corpCodeCache, naverNewsApiClient);
        assertThat(result.runs()).isEmpty();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("2. 공시 + 뉴스 → COLLECTING, 두 유형 행 저장 · 뉴스는 기간 시작일로 검색 · 원문 그대로 · 제목에 종목명 없는 기사 제외")
    void both_collecting() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of(disclosure("20261105900001", "주요사항보고서(자기주식취득결정)")));
        when(naverNewsApiClient.fetchSince(STOCK_NAME, DATE.minusDays(1))).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b>, 자사주 &quot;매입&quot;", "https://press.example.com/1", LocalDateTime.of(2026, 11, 5, 16, 20)),
                news("반도체 업황", "https://press.example.com/2", LocalDateTime.of(2026, 11, 5, 10, 0)),
                news("<b>테스트전자</b> 2주 전", "https://press.example.com/3", LocalDateTime.of(2026, 10, 22, 9, 0))), false));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.COLLECTING);
        assertThat(result.runs()).extracting(EvidenceCollectResult.CollectorRun::sourceType)
                .containsExactlyInAnyOrder(SourceType.DISCLOSURE, SourceType.NEWS);
        List<EventSource> rows = rows(analysis);
        assertThat(rows).extracting(EventSource::getSourceType).containsExactly(SourceType.NEWS, SourceType.DISCLOSURE);
        EventSource news = rows.get(0);
        assertThat(news.getTitle()).isEqualTo("<b>테스트전자</b>, 자사주 &quot;매입&quot;");
        assertThat(news.getSourceName()).isEqualTo("press.example.com");
        assertThat(news.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 20));
        assertThat(news.getExternalId()).isEqualTo("https://press.example.com/1");
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("3. 뉴스만 실패 → 공시만 저장, COLLECTING")
    void newsFails_disclosureSaved() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of(disclosure("20261105900001", "주요사항보고서(자기주식취득결정)")));
        when(naverNewsApiClient.fetchSince(anyString(), any()))
                .thenThrow(new NaverApiException(NaverErrorType.RATE_LIMIT, 429, null));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(rows(analysis)).extracting(EventSource::getSourceType).containsExactly(SourceType.DISCLOSURE);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
    }

    @Test
    @DisplayName("4. DART만 실패 → 뉴스만 저장, COLLECTING — B1 규칙 그대로(공시는 다시 수집되지 않는다: 배치 연결 때 결정)")
    void dartFails_newsSaved_b1Limitation() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker())).thenReturn(Optional.of(CORP_CODE));
        when(dartApiClient.list(anyString(), any(), any(), anyString()))
                .thenThrow(new DartApiException(DartErrorType.SERVER_ERROR, "800"));
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b> 급등", "https://press.example.com/1", LocalDateTime.of(2026, 11, 5, 14, 0))), false));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(rows(analysis)).extracting(EventSource::getSourceType).containsExactly(SourceType.NEWS);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.COLLECTING);
        // 한계 고정: 다시 불러도 PENDING이 아니라 공시를 다시 수집하지 않는다
        assertThat(service.collect(analysis.getId()).outcome()).isEqualTo(Outcome.SKIPPED_NOT_PENDING);
    }

    @Test
    @DisplayName("5. 둘 다 실패 → PENDING, 저장 0")
    void bothFail_pending() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker()))
                .thenThrow(new DartApiException(DartErrorType.NETWORK_ERROR, null));
        when(naverNewsApiClient.fetchSince(anyString(), any()))
                .thenThrow(new NaverApiException(NaverErrorType.AUTH, 401, null));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("6. 둘 다 0건 → NO_EVIDENCE (기간 밖 기사뿐인 경우 포함)")
    void bothEmpty_noEvidence() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of());
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b> 한 달 전", "https://press.example.com/1", LocalDateTime.of(2026, 10, 5, 9, 0))), false));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("6-b. 상한에 걸려 기간까지 못 내려가도 실패가 아니다 — 받은 것 중 기간 안 기사만 저장")
    void limitReached_notFailure() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of());
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b> 오늘", "https://press.example.com/9", LocalDateTime.of(2026, 11, 6, 9, 0))), true));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.runs()).allMatch(EvidenceCollectResult.CollectorRun::success);
        assertThat(rows(analysis)).extracting(EventSource::getExternalId).containsExactly("https://press.example.com/9");
    }

    @Test
    @DisplayName("7. 재실행 → 두 수집기 모두 부르지 않는다 (B1 멱등)")
    void rerun_noCalls() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of());
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(), false));
        service.collect(analysis.getId());

        EvidenceCollectResult second = service.collect(analysis.getId());

        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_NOT_PENDING);
        verify(naverNewsApiClient, org.mockito.Mockito.times(1)).fetchSince(anyString(), any());
        verify(corpCodeCache, org.mockito.Mockito.times(1)).corpCodeOf(analysis.getTicker());
    }

    @Test
    @DisplayName("8. 저장된 NEWS 행은 LLM 입력 불가 · DISCLOSURE 행은 가능 (B0 A안)")
    void newsNotLlmInput() {
        StockEventAnalysis analysis = newAnalysis();
        disclosures(List.of(disclosure("20261105900001", "주요사항보고서(자기주식취득결정)")));
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b> 급등", "https://press.example.com/1", LocalDateTime.of(2026, 11, 5, 14, 0))), false));

        service.collect(analysis.getId());

        assertThat(rows(analysis)).allSatisfy(row -> assertThat(row.getSourceType().isLlmInputAllowed())
                .isEqualTo(row.getSourceType() != SourceType.NEWS));
        assertThat(rows(analysis)).extracting(EventSource::getSourceType).contains(SourceType.NEWS, SourceType.DISCLOSURE);
    }

    @Test
    @DisplayName("고유번호가 없는 종목 → 공시 0건(목록 미호출), 뉴스는 그대로 수집")
    void unknownCorp_newsOnly() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker())).thenReturn(Optional.empty());
        when(naverNewsApiClient.fetchSince(anyString(), any())).thenReturn(new NaverNewsPages(List.of(
                news("<b>테스트전자</b> 급등", "https://press.example.com/1", LocalDateTime.of(2026, 11, 5, 14, 0))), false));

        service.collect(analysis.getId());

        verify(dartApiClient, never()).list(anyString(), any(), any(), anyString());
        assertThat(rows(analysis)).extracting(EventSource::getSourceType).containsExactly(SourceType.NEWS);
    }

    // ───────── 보조 ─────────

    private void disclosures(List<DartDisclosure> items) {
        when(corpCodeCache.corpCodeOf(anyString())).thenReturn(Optional.of(CORP_CODE));
        when(dartApiClient.list(anyString(), any(), any(), anyString())).thenReturn(List.of());
        when(dartApiClient.list(anyString(), any(), any(), org.mockito.ArgumentMatchers.eq("B"))).thenReturn(items);
    }

    private static DartDisclosure disclosure(String receiptNo, String reportName) {
        return new DartDisclosure(CORP_CODE, STOCK_NAME, "999901", reportName, receiptNo, STOCK_NAME,
                LocalDate.parse(receiptNo.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE), "");
    }

    private static NaverNewsItem news(String title, String url, LocalDateTime publishedAt) {
        return new NaverNewsItem(title, url, url, "테스트 요약", publishedAt);
    }

    private List<EventSource> rows(StockEventAnalysis analysis) {
        return eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId());
    }

    private StockEventAnalysis newAnalysis() {
        return analysisRepository.saveAndFlush(StockEventAnalysis.create(uniqueTicker(), STOCK_NAME, DATE, new BigDecimal("-6.1")));
    }

    private StockEventAnalysis reload(StockEventAnalysis analysis) {
        return analysisRepository.findById(analysis.getId()).orElseThrow();
    }

    private void etfHolding(String ticker) {
        String email = uniqueEmail();
        User user = userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
        tx.executeWithoutResult(status -> {
            Institution institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-collectors-" + UUID.randomUUID(), BusinessType.ST));
            Asset asset = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER));
            Holding h = Holding.create(asset, ticker);
            h.update("테스트 ETF", SecurityType.ETF, BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("999"),
                    new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("99.9999"));
            holdingRepository.save(h);
        });
    }

    // 테스트 전용 가짜 종목코드 (다른 테스트와 겹치지 않게 매번 새로)
    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
