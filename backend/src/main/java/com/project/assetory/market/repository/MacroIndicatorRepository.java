package com.project.assetory.market.repository;

import com.project.assetory.market.domain.IndicatorCode;
import com.project.assetory.market.domain.MacroIndicator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

// 시장 데이터(사용자 무관). 저장은 MacroIndicatorWriter(업서트)로만 한다
public interface MacroIndicatorRepository extends JpaRepository<MacroIndicator, Long> {

    Optional<MacroIndicator> findByIndicatorCodeAndBaseDate(IndicatorCode indicatorCode, LocalDate baseDate);
}
