package com.project.assetory.market.service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 시세 수집 1회 실행 결과 — 새 테이블 없이 메모리로 넘긴다 (스냅샷 판정 입력, Step 3~4).
 *
 * @param tickers           종목코드 → 결과 (종목코드 오름차순)
 * @param aborted           연속 실패로 남은 호출을 멈췄는지
 * @param closeGuardBlocked 장 마감 가드로 오늘 수집을 하지 않았는지
 */
public record StockPriceCollectResult(
        LocalDate tradeDate,
        Map<String, TickerResult> tickers,
        boolean aborted,
        boolean closeGuardBlocked,
        SecurityTypeResult securityTypes
) {

    public enum TickerStatus {
        COLLECTED,  // 기준 날짜 행 저장
        HALTED,     // 기준 날짜 행 없음 + 거래정지(tr_stop_yn=Y) — 행을 만들지 않는다
        FAILED      // 그 외 — 행을 만들지 않는다 (직전 값 복사 · 0 채움 금지)
    }

    public enum FailReason {
        CLOSE_GUARD,      // 장 마감 가드 전 — 오늘 현재가를 종가로 저장하지 않음
        API_ERROR,        // 시세 호출 실패 · 응답 해석 불가
        NO_ROW_FOR_DATE,  // 응답에 기준 날짜 행이 없고 거래정지도 아님(확인 불가 포함)
        NO_PREVIOUS_DAY,  // 직전 거래일 행이 없음(상장 첫날 등) — prev_close_price를 채울 수 없음
        NO_STOCK_NAME,    // 종목명을 holdings · 종목정보 어디서도 얻지 못함
        SAVE_ERROR,       // 저장 실패
        ABORTED           // 연속 실패로 호출하지 않음
    }

    public record TickerResult(TickerStatus status, FailReason reason) {
        static TickerResult collected() {
            return new TickerResult(TickerStatus.COLLECTED, null);
        }

        static TickerResult halted() {
            return new TickerResult(TickerStatus.HALTED, null);
        }

        static TickerResult failed(FailReason reason) {
            return new TickerResult(TickerStatus.FAILED, reason);
        }
    }

    // STOCK/ETF 판별 — classified: 채운 종목 수, unknownGroup: EF · ST 외 그룹(NULL 유지), failed: 종목정보 조회 실패
    public record SecurityTypeResult(int classified, int unknownGroup, int failed) {
        static final SecurityTypeResult SKIPPED = new SecurityTypeResult(0, 0, 0);
    }

    public long count(TickerStatus status) {
        return tickers.values().stream().filter(r -> r.status() == status).count();
    }
}
