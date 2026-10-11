package com.project.assetory.connect.event;

import com.project.assetory.connect.domain.BusinessType;

/**
 * 금융기관 연동(재연결 포함)이 저장됐을 때 발행. 커밋 뒤(AFTER_COMMIT)에만 처리된다.
 * 지금은 최초 자산 수집(BACKFILL)만 듣고, 이후 "보유 종목 30일 시세 소급"(docs/05 §11.1)도 이 이벤트에 리스너로 붙인다.
 */
public record ConnectedAccountLinkedEvent(Long connectedAccountId, BusinessType businessType) {}
