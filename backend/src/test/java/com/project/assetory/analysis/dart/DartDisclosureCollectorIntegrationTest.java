package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.EventSource;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.exception.DartApiException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.project.assetory.analysis.dart.DartFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DART 공시 수집기 + B1 수집 흐름 (B2 Phase 4). 실제 DART는 부르지 않는다 — 클라이언트 · corp_code 캐시를 목으로 둔다.
 * 지금 운영 수집기는 DART 하나라, "DART 실패 = 실행한 수집기 전부 실패"다.
 */
class DartDisclosureCollectorIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final String CORP_CODE = "00999901"; // 테스트 전용 더미 고유번호
    private static final String ACCOUNT_NUMBER = "12345678901"; // 테스트 전용 더미 계좌번호

    @MockitoBean DartApiClient dartApiClient;
    @MockitoBean DartCorpCodeCache corpCodeCache;
    // 뉴스 수집기(B3)는 이 테스트에서 뺀다 — 목의 supports()는 false라 실행되지 않고, DART만 도는 상황을 유지한다
    @MockitoBean com.project.assetory.analysis.naver.NaverNewsCollector naverNewsCollector;

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
    @DisplayName("1. ETF → DART를 부르지 않는다 (고유번호 조회도 없음), 근거 0건 NO_EVIDENCE")
    void etf_noDartCall() {
        StockEventAnalysis analysis = newAnalysis();
        etfHolding(analysis.getTicker());

        EvidenceCollectResult result = service.collect(analysis.getId());

        verifyNoInteractions(dartApiClient, corpCodeCache);
        assertThat(result.runs()).isEmpty();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("2. 정상 → 유형 B · I · D를 이벤트일 전후 1일로 조회, COLLECTING · 근거 행 저장(published_at NULL · 뷰어 링크 · 접수번호)")
    void success_savedAsDisclosure() {
        StockEventAnalysis analysis = listed();
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("B"))).thenReturn(items("list-success.json"));
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("I"))).thenReturn(List.of());
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("D"))).thenReturn(items("list-duplicate.json"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        for (String type : List.of("B", "I", "D")) {
            verify(dartApiClient).list(CORP_CODE, DATE.minusDays(1), DATE.plusDays(1), type);
        }
        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.status()).isEqualTo(AnalysisStatus.COLLECTING);
        List<EventSource> rows = eventSourceRepository.findAllByAnalysisIdOrderByPublishedAt(analysis.getId());
        // B의 3건 + D의 중복 1건(B와 같은 접수번호) → 3건
        assertThat(rows).extracting(EventSource::getExternalId)
                .containsExactly("20261105900001", "20261104900002", "20261106900003");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getSourceType()).isEqualTo(SourceType.DISCLOSURE);
            assertThat(row.getSourceName()).isEqualTo("DART");
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + row.getExternalId());
            assertThat(row.getSnippet()).isNull();
        });
        assertThat(rows.get(0).getTitle()).isEqualTo("주요사항보고서(자기주식취득결정)");
        assertThat(reload(analysis).getEvidenceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("3. 모든 유형이 0건(013) → NO_EVIDENCE (정상 결과)")
    void noData_noEvidence() {
        StockEventAnalysis analysis = listed();
        when(dartApiClient.list(anyString(), any(), any(), anyString())).thenReturn(List.of());

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        assertThat(reload(analysis).getEvidenceCount()).isZero();
    }

    @Test
    @DisplayName("4. 키 오류(DART가 유일한 수집기) → 수집기 실패 · 저장 0 · PENDING 유지")
    void keyError_staysPending() {
        StockEventAnalysis analysis = listed();
        when(dartApiClient.list(anyString(), any(), any(), anyString()))
                .thenThrow(new DartApiException(DartErrorType.KEY_OR_ACCESS, "010"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(result.runs()).singleElement().satisfies(run -> {
            assertThat(run.sourceType()).isEqualTo(SourceType.DISCLOSURE);
            assertThat(run.success()).isFalse();
        });
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        // 키 오류는 같은 실행 안에서 다시 부르지 않는다 — 첫 유형에서 멈춘다
        verify(dartApiClient, times(1)).list(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("5. 유형 B 성공 · I 실패 → 수집기 전체 실패, B 근거도 저장하지 않고 PENDING (전부 성공 아니면 실패)")
    void partialTypeFailure_allOrNothing() {
        StockEventAnalysis analysis = listed();
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("B"))).thenReturn(items("list-success.json"));
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("I")))
                .thenThrow(new DartApiException(DartErrorType.SERVER_ERROR, "800"));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isZero();
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        verify(dartApiClient, never()).list(anyString(), any(), any(), eq("D"));
    }

    @Test
    @DisplayName("6. 고유번호 맵에 없는 종목(비상장 등) → 목록을 부르지 않고 NO_EVIDENCE (정상 0건)")
    void unknownCorp_noEvidence() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker())).thenReturn(Optional.empty());

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.NO_EVIDENCE);
        verify(dartApiClient, never()).list(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("6-b. 고유번호 맵을 받지 못하면 수집기 실패 → PENDING (0건으로 굳히지 않는다)")
    void corpCodeUnavailable_staysPending() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker()))
                .thenThrow(new DartApiException(DartErrorType.NETWORK_ERROR, null));

        EvidenceCollectResult result = service.collect(analysis.getId());

        assertThat(result.outcome()).isEqualTo(Outcome.ALL_FAILED);
        assertThat(reload(analysis).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("7. 수집이 끝난 분석을 다시 부르면 DART를 부르지 않는다 (B1 멱등)")
    void rerun_noCall() {
        StockEventAnalysis analysis = listed();
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("B"))).thenReturn(items("list-success.json"));
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("I"))).thenReturn(List.of());
        when(dartApiClient.list(eq(CORP_CODE), any(), any(), eq("D"))).thenReturn(List.of());
        service.collect(analysis.getId());

        EvidenceCollectResult second = service.collect(analysis.getId());

        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_NOT_PENDING);
        verify(dartApiClient, times(3)).list(anyString(), any(), any(), anyString());
        verify(corpCodeCache, times(1)).corpCodeOf(analysis.getTicker());
        assertThat(eventSourceRepository.countByAnalysisId(analysis.getId())).isEqualTo(3);
    }

    // ───────── 보조 ─────────

    /** 고유번호 맵에 있는 종목의 분석 */
    private StockEventAnalysis listed() {
        StockEventAnalysis analysis = newAnalysis();
        when(corpCodeCache.corpCodeOf(analysis.getTicker())).thenReturn(Optional.of(CORP_CODE));
        return analysis;
    }

    private static List<DartDisclosure> items(String fixtureName) {
        return DartListParser.parse(fixture(fixtureName)).items();
    }

    private StockEventAnalysis newAnalysis() {
        return analysisRepository.saveAndFlush(
                StockEventAnalysis.create(uniqueTicker(), "테스트 종목", DATE, new BigDecimal("-6.1")));
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
                    ConnectedAccount.create(user, institution, "cid-dart-" + UUID.randomUUID(), BusinessType.ST));
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
