package com.project.assetory.analysis.naver;

import java.time.LocalDateTime;

/**
 * 뉴스 검색 items[] 1건 — title · description · 링크는 API가 준 원문 그대로(<b> 태그 · HTML 엔티티 포함). 고치지 않는다(B0 A안, 검색 특약).
 *
 * @param publishedAt pubDate(RFC 1123)를 KST로 바꾼 시각
 */
public record NaverNewsItem(String title, String originallink, String link, String description, LocalDateTime publishedAt) {
}
