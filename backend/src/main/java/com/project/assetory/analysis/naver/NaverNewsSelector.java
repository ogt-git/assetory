package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.evidence.CollectedEvidence;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기사 → 근거 (B3 확정 사항 3 · 4 · 5)
 * - 기간: publishedAt의 KST 날짜가 [from, to] 안 (경계일 포함). 게시 시각으로 자르지 않는다 — 장 마감 후 구분은 표시 단계(FR-CA-08)
 * - 제목 필터: 비교용으로만 태그 제거 · 엔티티 디코드 후 종목명 포함 여부. TODO(01-§14 #10) 검색어 · 노이즈 규칙은 B0 N4 · N5로 확정
 * - 변환: NEWS / title · snippet = title · description 원문(trim만) / source_name = 원문 링크 host 그대로 /
 *   url · external_id = originallink, 비면 link / relevance_rank NULL(순위를 매기지 않는다)
 * - 같은 URL은 처음 것 1건
 * 뉴스는 LLM 입력 대상이 아니다(SourceType.NEWS) — 이 근거는 표시 전용이다 (B0 A안)
 */
public final class NaverNewsSelector {

    private NaverNewsSelector() {
    }

    public static List<CollectedEvidence> toEvidences(List<NaverNewsItem> items, String stockName, LocalDate from, LocalDate to) {
        Set<String> seen = new LinkedHashSet<>();
        List<CollectedEvidence> evidences = new ArrayList<>();
        for (NaverNewsItem item : items) {
            if (!withinWindow(item, from, to) || !titleMentions(item, stockName)) continue;
            String url = urlOf(item);
            if (url != null && !seen.add(url)) continue;
            evidences.add(new CollectedEvidence(SourceType.NEWS, strip(item.title()), hostOf(url), item.publishedAt(),
                    url, strip(item.description()), null, url));
        }
        return List.copyOf(evidences);
    }

    static boolean withinWindow(NaverNewsItem item, LocalDate from, LocalDate to) {
        LocalDate date = item.publishedAt().toLocalDate();
        return !date.isBefore(from) && !date.isAfter(to);
    }

    static boolean titleMentions(NaverNewsItem item, String stockName) {
        return stockName != null && !stockName.isBlank() && NaverHtml.plainText(item.title()).contains(stockName.strip());
    }

    private static String urlOf(NaverNewsItem item) {
        String original = strip(item.originallink());
        if (original != null && !original.isEmpty()) return original;
        String link = strip(item.link());
        return link == null || link.isEmpty() ? null : link;
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
