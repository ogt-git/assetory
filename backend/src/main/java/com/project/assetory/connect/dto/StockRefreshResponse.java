package com.project.assetory.connect.dto;

import java.time.LocalDateTime;
import java.util.List;

// 증권 수동 새로고침 결과 — 연동 계정별 결과와 다음 요청 가능 시각. 기관 인증 정보는 넣지 않는다
public record StockRefreshResponse(List<AccountResult> accounts, LocalDateTime syncedAt, LocalDateTime nextAvailableAt) {

    // status: SUCCESS / FAILED / LOCKED(다른 동기화 진행 중)
    public record AccountResult(Long connectionId, String institutionName, String status, int collectedCount,
                                String message) {}

    public boolean hasFailure() {
        return accounts.stream().anyMatch(a -> "FAILED".equals(a.status()));
    }
}
