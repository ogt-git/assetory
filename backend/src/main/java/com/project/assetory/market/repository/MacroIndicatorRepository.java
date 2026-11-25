package com.project.assetory.market.repository;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 시장 데이터(사용자 무관). 저장은 MacroIndicatorWriter(업서트)로만 한다
public interface MacroIndicatorRepository extends JpaRepository<MacroIndicator, Long> {

    Optional<MacroIndicator> findByIndicatorCodeAndBaseDate(IndicatorCode indicatorCode, LocalDate baseDate);

    // 같은 날 여러 지표를 한 번에 (원인 분석 상세의 KOSPI · KOSDAQ — Q-9). 없는 지표는 행이 없다
    List<MacroIndicator> findAllByIndicatorCodeInAndBaseDate(Collection<IndicatorCode> indicatorCodes, LocalDate baseDate);
}
