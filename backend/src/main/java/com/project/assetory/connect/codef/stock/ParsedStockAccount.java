package com.project.assetory.connect.codef.stock;

import java.math.BigDecimal;
import java.util.List;

/**
 * CODEF 증권 종합자산 응답 파싱 결과.
 * complete=false면 응답을 신뢰할 수 없다 (예수금·종목 수량·평가금액 누락, 종목 조회 실패 건·종목코드 누락·목록 형식 오류) — 저장하지 않는다.
 */
public record ParsedStockAccount(
        BigDecimal depositReceived,  // 예수금 (resDepositReceived)
        List<ParsedHolding> holdings,
        boolean complete,
        int invalidCount,            // resResultCode=0 또는 종목코드 없음
        int soldOutCount,            // 수량·매입·평가 모두 0 — 전량 매도
        int foreignCount             // 해외 종목 — 수집하지 않음 (FR-CN-03)
) {}
