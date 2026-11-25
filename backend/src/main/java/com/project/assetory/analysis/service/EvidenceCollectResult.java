package com.project.assetory.analysis.service;

import com.project.assetory.analysis.domain.AnalysisStatus;
import com.project.assetory.analysis.domain.SourceType;

import java.util.List;

/**
 * 근거 수집 1회 결과 — 테스트 · 로그용. 사용자 응답으로 나가지 않는다.
 *
 * @param runs          실행한 수집기별 결과(supports()가 false인 수집기는 빠진다)
 * @param savedCount    수집이 끝났을 때 이 분석의 근거 행 수(= evidence_count). 끝나지 않았으면 0
 * @param excludedCount 값 검증에서 제외한 건수
 * @param status        호출이 끝난 뒤의 analysis_status (분석이 없으면 null)
 */
public record EvidenceCollectResult(Outcome outcome, List<CollectorRun> runs, int savedCount, int excludedCount,
                                    AnalysisStatus status) {

    public enum Outcome {
        /** 저장 · 상태 변경까지 끝남 (NO_EVIDENCE 또는 COLLECTING) */
        COMPLETED,
        /** 실행한 수집기가 모두 실패 — 아무것도 바꾸지 않고 PENDING 유지, 다음 실행 때 재시도 */
        ALL_FAILED,
        /** 공시 수집기가 재시도 가능한 오류로 실패 — 다른 근거 · 시장 구분은 저장, 상태는 PENDING 유지(재시도 대상, Q-6 D2) */
        PENDING_RETRY,
        /** 저장 트랜잭션 실패 — 롤백, PENDING 유지 */
        SAVE_FAILED,
        /** 다른 실행이 락을 잡고 있음 */
        SKIPPED_LOCKED,
        /** 이미 수집이 끝난 분석 (멱등) */
        SKIPPED_NOT_PENDING,
        NOT_FOUND
    }

    /**
     * @param collected 성공 시 수집기가 돌려준 건수(검증 · 중복 제거 전). 실패면 0
     * @param retryable 실패일 때 다시 시도하면 될 수 있는지(성공이면 false) — 공시 수집기는 Q-6 D2 판단에 쓴다
     */
    public record CollectorRun(SourceType sourceType, boolean success, int collected, boolean retryable) {
    }

    static EvidenceCollectResult skipped(Outcome outcome, AnalysisStatus status) {
        return new EvidenceCollectResult(outcome, List.of(), 0, 0, status);
    }
}
