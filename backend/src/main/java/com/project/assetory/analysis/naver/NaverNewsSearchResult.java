package com.project.assetory.analysis.naver;

import java.util.List;

/**
 * 뉴스 검색 응답 1회분 — lastBuildDate는 쓰지 않아 담지 않는다
 *
 * @param total 전체 검색 결과 수 · start 이번 시작 위치 · display 이번에 받은 수
 */
public record NaverNewsSearchResult(long total, int start, int display, List<NaverNewsItem> items) {
}
