package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.config.EvidenceProperties;
import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import com.project.assetory.analysis.evidence.EvidenceCollector;
import com.project.assetory.analysis.evidence.EvidenceTarget;
import com.project.assetory.analysis.exception.DartApiException;
import com.project.assetory.analysis.exception.EvidenceCollectException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DART 공시 근거 수집기 (FR-CA-01, B2). 키가 없어도 빈으로 등록된다 — 호출 시 실패해 분석이 PENDING으로 남는다(확정 사항 2).
 * - ETF는 수집하지 않는다
 * - 고유번호 맵에 없는 종목(비상장 등) → 정상 0건 / 맵을 받지 못함 → 실패
 * - 접수일 범위 [이벤트일 − days-before, 이벤트일 + days-after] (assetory.evidence.disclosure, 게시 시각으로 자르지 않음 — #7)
 * - 공시 유형(assetory.dart.disclosure-types)마다 1회. 한 유형 · 한 페이지라도 실패하면 전체 실패 — 일부 근거만 돌려주지 않는다(확정 사항 7).
 *   첫 실패에서 멈춘다(남은 유형을 부르지 않는다 — 키 오류면 같은 실행에서 같은 오류를 반복하지 않게)
 */
@Component
public class DartDisclosureCollector implements EvidenceCollector {

    private final DartApiClient client;
    private final DartCorpCodeCache corpCodeCache;
    private final DartProperties dartProperties;
    private final EvidenceProperties evidenceProperties;

    public DartDisclosureCollector(DartApiClient client, DartCorpCodeCache corpCodeCache,
                                   DartProperties dartProperties, EvidenceProperties evidenceProperties) {
        this.client = client;
        this.corpCodeCache = corpCodeCache;
        this.dartProperties = dartProperties;
        this.evidenceProperties = evidenceProperties;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.DISCLOSURE;
    }

    @Override
    public boolean supports(EvidenceTarget target) {
        return !target.etf();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceTarget target) {
        try {
            Optional<String> corpCode = corpCodeCache.corpCodeOf(target.ticker());
            if (corpCode.isEmpty()) {
                return List.of();
            }
            EvidenceProperties.Window window = evidenceProperties.disclosure();
            LocalDate from = target.eventDate().minusDays(window.daysBefore());
            LocalDate to = target.eventDate().plusDays(window.daysAfter());
            List<DartDisclosure> disclosures = new ArrayList<>();
            for (String type : dartProperties.disclosureTypes()) {
                disclosures.addAll(client.list(corpCode.get(), from, to, type));
            }
            return DartEvidenceMapper.toEvidences(disclosures);
        } catch (DartApiException e) {
            // DartApiException 메시지는 유형 · DART status뿐이라 cause로 붙여도 키 · URL이 없다
            // retryable — 서버 오류 · 요청 한도 · 네트워크는 재시도 대상(Q-6 D2), 키 오류 · 키 없음 · 잘못된 요청 · 페이지 상한은 아님
            throw new EvidenceCollectException(SourceType.DISCLOSURE, e.getErrorType().name(), e, e.getErrorType().isRetryable());
        }
    }
}
