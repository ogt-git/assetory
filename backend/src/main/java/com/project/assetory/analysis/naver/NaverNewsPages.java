package com.project.assetory.analysis.naver;

import java.util.List;

/**
 * 최신순으로 여러 페이지 받은 결과
 *
 * @param items        받은 기사 전부(최신순, 기간 필터 전)
 * @param limitReached 기간 시작까지 내려가기 전에 상한(max-pages 또는 start 1,000)에 걸려 멈췄는지 — 실패가 아니다(B3 확정 사항 6)
 */
public record NaverNewsPages(List<NaverNewsItem> items, boolean limitReached) {
}
