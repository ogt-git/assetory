package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.project.assetory.analysis.dart.DartFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;

// 공시 → 근거 변환 (B2 확정 사항 6)
class DartEvidenceMapperTest {

    @Test
    @DisplayName("공시 → 근거: DISCLOSURE · title 앞뒤 공백만 제거 · DART · published_at NULL · 뷰어 링크 · 접수번호")
    void toEvidence() {
        List<CollectedEvidence> evidences = DartEvidenceMapper.toEvidences(
                DartListParser.parse(fixture("list-success.json")).items());

        assertThat(evidences).hasSize(3);
        CollectedEvidence first = evidences.get(0);
        assertThat(first.type()).isEqualTo(SourceType.DISCLOSURE);
        assertThat(first.title()).isEqualTo("주요사항보고서(자기주식취득결정)");
        assertThat(first.sourceName()).isEqualTo("DART");
        assertThat(first.publishedAt()).isNull();
        assertThat(first.url()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261105900001");
        assertThat(first.snippet()).isNull();
        assertThat(first.relevanceRank()).isNull();
        assertThat(first.externalId()).isEqualTo("20261105900001").hasSize(14);
    }

    @Test
    @DisplayName("정정 공시([기재정정] 등)도 제목 그대로 근거에 넣는다 (확정 사항 3)")
    void correctionKept() {
        List<CollectedEvidence> evidences = DartEvidenceMapper.toEvidences(
                DartListParser.parse(fixture("list-success.json")).items());

        assertThat(evidences).extracting(CollectedEvidence::title).contains("[기재정정]주요사항보고서(유상증자결정)");
    }

    @Test
    @DisplayName("같은 접수번호가 여러 번 오면 1건(처음 것) — 유형별 호출 결과를 합칠 때도 같은 규칙")
    void duplicateReceiptNo_once() {
        List<DartDisclosure> twice = DartListParser.parse(fixture("list-duplicate.json")).items();
        List<DartDisclosure> merged = new java.util.ArrayList<>(twice);
        merged.addAll(DartListParser.parse(fixture("list-success.json")).items());

        assertThat(DartEvidenceMapper.toEvidences(twice)).hasSize(1);
        assertThat(DartEvidenceMapper.toEvidences(merged)).extracting(CollectedEvidence::externalId)
                .containsExactly("20261105900001", "20261104900002", "20261106900003");
    }

    @Test
    @DisplayName("빈 목록 → 빈 근거")
    void empty() {
        assertThat(DartEvidenceMapper.toEvidences(List.of())).isEmpty();
    }
}
