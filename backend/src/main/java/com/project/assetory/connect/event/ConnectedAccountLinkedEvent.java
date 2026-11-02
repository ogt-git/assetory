package com.project.assetory.connect.event;

import com.project.assetory.connect.domain.BusinessType;

/**
 * 금융기관 연동(재연결 포함)이 저장됐을 때 발행. 커밋 뒤(AFTER_COMMIT)에만 처리된다.
 * InitialSyncListener가 최초 자산 수집(BACKFILL)을 하고, 성공하면 같은 작업 안에서 보유 종목 시세를 소급한다(docs/05 §11.1).
 * 소급은 수집 결과(보유 종목)가 있어야 대상이 정해지므로 별도 리스너로 나누지 않는다 — 리스너끼리는 실행 순서가 보장되지 않는다.
 */
public record ConnectedAccountLinkedEvent(Long connectedAccountId, BusinessType businessType) {}
