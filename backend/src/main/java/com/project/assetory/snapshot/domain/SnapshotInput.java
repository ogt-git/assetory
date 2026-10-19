package com.project.assetory.snapshot.domain;

import com.project.assetory.asset.domain.AssetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 사용자의 그날 수집 결과 — 판정 입력 (새 테이블 없음, 배치 실행 안에서 메모리로 넘긴다 — 05 §11.2).
 * 정기 배치 한 실행의 결과만 담는다. 수동 새로고침 결과를 섞지 않는다. DISCONNECTED 연동은 이미 빠진 상태로 들어온다.
 * null은 "값 없음"이다 — 0으로 바꾸지 않고 그 대상의 실패로 본다 (AGENTS 5-C).
 *
 * @param collectedAt 실제 수집 완료 시각 (asset_snapshots.collected_at)
 * @param accounts    연동 증권 계좌별 결과 (판정 분모: ACTIVE + AUTH_FAILED · EXPIRED)
 * @param manualAssets 삭제되지 않은 수기 자산 — 배치 시작 시점의 balance
 */
public record SnapshotInput(
        LocalDate snapshotDate,
        LocalDateTime collectedAt,
        List<Account> accounts,
        List<Manual> manualAssets
) {

    public enum AccountStatus {
        SUCCESS,        // 예수금 · 보유 종목을 받음
        FAILED,         // 수집 실패
        EMPTY_RESULT,   // CODEF 결과 없음 — 스냅샷에서는 누락으로 본다 (임시, CODEF 샘플 확인 후 재검토)
        EXCLUDED_AUTH,  // AUTH_FAILED · EXPIRED 등 — 분모에 포함되지만 수집하지 않음
        LOCK_FAILED     // 동기화 락을 얻지 못함 (1회 재시도 후)
    }

    public enum PriceStatus {
        COLLECTED,  // 그날 종가
        HALTED,     // 거래정지 — 직전 거래일 종가로 평가 (없으면 실패)
        FAILED
    }

    /** 증권 계좌 1개. assetId = 증권 계좌 assets 행(예수금 행) */
    public record Account(Long assetId, AccountStatus status, BigDecimal deposit, List<HoldingPrice> holdings) {

        public static Account success(Long assetId, BigDecimal deposit, List<HoldingPrice> holdings) {
            return new Account(assetId, AccountStatus.SUCCESS, deposit, holdings);
        }

        public static Account of(Long assetId, AccountStatus status) {
            return new Account(assetId, status, null, List.of());
        }
    }

    /**
     * 보유 종목 1개 — 수량은 이번 실행에서 받은 값, price는 COLLECTED면 그날 종가 · HALTED면 직전 거래일 종가
     */
    public record HoldingPrice(String ticker, BigDecimal quantity, PriceStatus priceStatus, BigDecimal price) {

        public static HoldingPrice collected(String ticker, BigDecimal quantity, BigDecimal closePrice) {
            return new HoldingPrice(ticker, quantity, PriceStatus.COLLECTED, closePrice);
        }

        public static HoldingPrice halted(String ticker, BigDecimal quantity, BigDecimal previousClosePrice) {
            return new HoldingPrice(ticker, quantity, PriceStatus.HALTED, previousClosePrice);
        }

        public static HoldingPrice failed(String ticker, BigDecimal quantity) {
            return new HoldingPrice(ticker, quantity, PriceStatus.FAILED, null);
        }
    }

    /** 수기 자산 1개 — asset_type으로 자산군을 나눈다 (05 §4.4) */
    public record Manual(Long assetId, AssetType assetType, BigDecimal balance) {}
}
