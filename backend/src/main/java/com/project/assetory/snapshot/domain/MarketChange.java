package com.project.assetory.snapshot.domain;

import java.math.BigDecimal;

/**
 * 직전 COMPLETE 대비 시장가격 영향 · 기타 자산 변화 (asset_snapshots.market_change_amount · other_change_amount, 05 §4.7).
 * 둘 다 NULL이거나 둘 다 값이 있다. other는 원인을 뜻하지 않는 차이값이다.
 */
public record MarketChange(BigDecimal market, BigDecimal other) {

    public static final MarketChange NONE = new MarketChange(null, null);
}
