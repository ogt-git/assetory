package com.project.assetory.analysis.evidence;

import com.project.assetory.analysis.domain.SourceType;

import java.time.LocalDateTime;

/**
 * 수집기가 돌려주는 근거 1건 — 저장 전 값. event_sources 컬럼과 1:1 (05 §5.2). 저장 전에 EvidenceSanitizer를 거친다.
 *
 * @param publishedAt 발표 · 게시 시각. 시각을 주지 않는 소스(DART)는 NULL — 날짜를 추측해 채우지 않는다 (01 §14 #8)
 * @param snippet     원문 일부. 뉴스는 API 값을 고치지 않고 그대로 담는다 (B0 A안)
 * @param externalId  중복 기준 — 공시 접수번호 · 기사 원문 URL
 */
public record CollectedEvidence(SourceType type, String title, String sourceName, LocalDateTime publishedAt,
                                String url, String snippet, Integer relevanceRank, String externalId) {
}
