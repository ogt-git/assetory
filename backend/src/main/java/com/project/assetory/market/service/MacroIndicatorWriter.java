package com.project.assetory.market.service;

import com.project.assetory.market.domain.MacroIndicator;
import com.project.assetory.market.domain.MacroIndicatorRow;
import com.project.assetory.market.repository.MacroIndicatorRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 지표 행 업서트 — stock_prices와 같은 방식(있으면 찾아 값 갱신, 없으면 새로). UNIQUE (indicator_code, base_date).
 * 별도 빈이라 @Transactional이 프록시로 적용된다 — 호출 1회(= 지표 하나의 목록)가 독립된 짧은 트랜잭션 1개이고,
 * 중간에 실패하면 그 목록 전체가 롤백된다 (B4 확정 사항 6). 같은 값이면 Hibernate 변경 감지로 UPDATE가 나가지 않는다
 */
@Component
public class MacroIndicatorWriter {

    private final MacroIndicatorRepository repository;

    public MacroIndicatorWriter(MacroIndicatorRepository repository) {
        this.repository = repository;
    }

    /** @return 저장(신규 + 갱신 대상) 행 수 */
    @Transactional
    public int upsertAll(List<MacroIndicatorRow> rows) {
        for (MacroIndicatorRow row : rows) {
            MacroIndicator indicator = repository.findByIndicatorCodeAndBaseDate(row.code(), row.baseDate())
                    .orElseGet(() -> MacroIndicator.create(row.code(), row.baseDate()));
            indicator.update(row.value(), row.changeRate());
            repository.save(indicator);
        }
        return rows.size();
    }
}
