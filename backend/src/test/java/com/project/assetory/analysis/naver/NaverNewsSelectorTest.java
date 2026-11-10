package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.project.assetory.analysis.naver.NaverFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;

// 기사 → 근거 (B3 확정 사항 3 · 4 · 5) — 저장값은 원문 그대로, 비교만 태그 제거 · 엔티티 디코드
class NaverNewsSelectorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 4);
    private static final LocalDate TO = LocalDate.of(2026, 11, 6);

    @Test
    @DisplayName("근거 변환: NEWS · title · snippet 원문 그대로 · source_name = 원문 링크 host · published_at KST · relevance_rank NULL")
    void toEvidence() {
        List<CollectedEvidence> evidences = select("테스트전자");

        CollectedEvidence first = evidences.get(0);
        assertThat(first.type()).isEqualTo(SourceType.NEWS);
        assertThat(first.title()).isEqualTo("<b>테스트전자</b>, 자사주 매입 &quot;발표&quot;");
        assertThat(first.snippet()).isEqualTo("<b>테스트전자</b>가 5일 자기주식 취득을 결정했다고 &amp; 공시했다.");
        assertThat(first.sourceName()).isEqualTo("news.example-press.co.kr");
        assertThat(first.publishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 20));
        assertThat(first.url()).isEqualTo("https://news.example-press.co.kr/article/1001");
        assertThat(first.externalId()).isEqualTo(first.url());
        assertThat(first.relevanceRank()).isNull();
    }

    @Test
    @DisplayName("originallink가 비면 link를 url · external_id · source_name(host)에 쓴다")
    void blankOriginalLink_usesLink() {
        CollectedEvidence second = select("테스트전자").get(1);

        assertThat(second.url()).isEqualTo("https://n.news.naver.com/mnews/article/999/0000001002");
        assertThat(second.externalId()).isEqualTo(second.url());
        assertThat(second.sourceName()).isEqualTo("n.news.naver.com");
    }

    @Test
    @DisplayName("제목 필터: 태그를 뺀 제목에 종목명이 있어야 남는다 — 요약에만 있는 기사는 제외")
    void titleFilter() {
        List<CollectedEvidence> evidences = select("테스트전자");

        // 3번째 기사(제목 "반도체 업황 점검")는 요약에만 종목명이 있어 빠진다
        assertThat(evidences).extracting(CollectedEvidence::title).noneMatch(t -> t.contains("반도체 업황"));
    }

    @Test
    @DisplayName("제목 필터: 태그 · 엔티티가 섞여 있어도 디코드한 글자로 비교한다(저장값은 그대로)")
    void titleFilter_entities() {
        NaverNewsItem item = item("<b>AT&amp;T</b> 실적 &#39;서프라이즈&#39;", "https://e.com/1",
                LocalDateTime.of(2026, 11, 5, 9, 0));
        NaverNewsItem split = item("<b>테스트</b>전자 상승", "https://e.com/2", LocalDateTime.of(2026, 11, 5, 9, 0));

        assertThat(NaverNewsSelector.toEvidences(List.of(item), "AT&T", FROM, TO))
                .singleElement().satisfies(e -> assertThat(e.title()).isEqualTo("<b>AT&amp;T</b> 실적 &#39;서프라이즈&#39;"));
        assertThat(NaverNewsSelector.toEvidences(List.of(split), "테스트전자", FROM, TO)).hasSize(1);
        assertThat(NaverNewsSelector.toEvidences(List.of(item), "테스트전자", FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("같은 URL은 1건(처음 것)")
    void duplicateUrl_once() {
        assertThat(select("테스트전자")).extracting(CollectedEvidence::url)
                .containsExactly("https://news.example-press.co.kr/article/1001",
                        "https://n.news.naver.com/mnews/article/999/0000001002");
    }

    @Test
    @DisplayName("기간 [from, to] KST 달력일 — 경계일은 포함, 밖은 제외 (게시 시각으로 자르지 않음)")
    void window() {
        List<NaverNewsItem> items = List.of(
                item("테스트전자 전날 자정 직전", "https://e.com/a", LocalDateTime.of(2026, 11, 3, 23, 59)),
                item("테스트전자 시작일 0시", "https://e.com/b", LocalDateTime.of(2026, 11, 4, 0, 0)),
                item("테스트전자 장 마감 뒤", "https://e.com/c", LocalDateTime.of(2026, 11, 5, 18, 0)),
                item("테스트전자 끝날 마지막", "https://e.com/d", LocalDateTime.of(2026, 11, 6, 23, 59)),
                item("테스트전자 다음 날", "https://e.com/e", LocalDateTime.of(2026, 11, 7, 0, 0)));

        assertThat(NaverNewsSelector.toEvidences(items, "테스트전자", FROM, TO)).extracting(CollectedEvidence::url)
                .containsExactly("https://e.com/b", "https://e.com/c", "https://e.com/d");
    }

    @Test
    @DisplayName("source_name = host 그대로(www 포함) · url이 깨져 host가 없으면 null(B1 값 검증에서 제외된다)")
    void sourceNameHost() {
        NaverNewsItem www = item("테스트전자", "https://www.example.co.kr/n/1", LocalDateTime.of(2026, 11, 5, 9, 0));
        NaverNewsItem broken = new NaverNewsItem("테스트전자", "not a url", "", "d", LocalDateTime.of(2026, 11, 5, 9, 0));

        assertThat(NaverNewsSelector.toEvidences(List.of(www), "테스트전자", FROM, TO).get(0).sourceName())
                .isEqualTo("www.example.co.kr");
        assertThat(NaverNewsSelector.toEvidences(List.of(broken), "테스트전자", FROM, TO).get(0).sourceName()).isNull();
    }

    private static List<CollectedEvidence> select(String stockName) {
        return NaverNewsSelector.toEvidences(NaverNewsParser.parse(fixture("news-success.json")).items(), stockName, FROM, TO);
    }

    private static NaverNewsItem item(String title, String url, LocalDateTime publishedAt) {
        return new NaverNewsItem(title, url, url, "테스트 요약", publishedAt);
    }
}
