package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 공시 → 근거 (B2 확정 사항 6, 05 §5.2)
 * - title: report_nm 앞뒤 공백만 제거(정정 표시 [기재정정] 등 원문 유지 — 확정 사항 3)
 * - published_at: NULL — DART는 시각을 주지 않는다. 날짜는 external_id(접수번호) 앞 8자리 (01 §14 #8)
 * - url: DART 공시 뷰어 링크 / external_id: 접수번호 / snippet · relevance_rank: NULL
 * - 같은 접수번호는 처음 것 1건만 (유형별 호출 결과를 합칠 때 겹칠 수 있다)
 */
public final class DartEvidenceMapper {

    static final String SOURCE_NAME = "DART";
    static final String VIEWER_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    private DartEvidenceMapper() {
    }

    public static List<CollectedEvidence> toEvidences(List<DartDisclosure> disclosures) {
        Set<String> seen = new LinkedHashSet<>();
        List<CollectedEvidence> evidences = new ArrayList<>();
        for (DartDisclosure d : disclosures) {
            if (!seen.add(d.receiptNo())) continue;
            evidences.add(new CollectedEvidence(SourceType.DISCLOSURE, d.reportName().strip(), SOURCE_NAME, null,
                    VIEWER_URL + d.receiptNo(), null, null, d.receiptNo()));
        }
        return List.copyOf(evidences);
    }
}
