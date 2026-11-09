package com.project.assetory.analysis.evidence;

import com.project.assetory.analysis.domain.SourceType;
import com.project.assetory.analysis.exception.EvidenceCollectException;

import java.util.List;

/**
 * 근거 수집기 — 확장 지점 (NFR-SC-03, AGENTS 8장). 구현체는 소스마다 1개(DART: B2 · 네이버 뉴스: B3 · ECOS: B4)이고
 * 스프링 빈으로 등록하면 수집 흐름이 List&lt;EvidenceCollector&gt;로 모두 받아 돌린다.
 *
 * 규칙
 * - 외부 오류(타임아웃 · 인증 실패 · 응답 해석 불가)는 EvidenceCollectException으로 던진다. 결과 0건은 오류가 아니라 빈 목록이다
 * - 예외 메시지 · 로그에 키 · 요청 URL(키 포함 가능) · 응답 원문을 넣지 않는다
 * - 외부 호출 1회 타임아웃은 assetory.evidence.call-timeout(10초). 수집기 전체 실행 시간 합이
 *   공유 분석 락 TTL(lock:analysis:*, 5분 — 05 §9)보다 작아야 한다 — 수집 중 락이 풀려 같은 분석을 두 번 수집하지 않게
 * - 게시 시각으로 자르지 않는다. 장 마감 후 자료 구분은 표시 단계 몫이다 (01 §14 #7, FR-CA-08)
 */
public interface EvidenceCollector {

    SourceType sourceType();

    /** 이 대상을 수집하는지 — 예: ETF는 공시 · 뉴스 수집기가 false (FR-CA-01 · 02) */
    boolean supports(EvidenceTarget target);

    List<CollectedEvidence> collect(EvidenceTarget target) throws EvidenceCollectException;
}
