package com.project.assetory.market.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * docs/05 §4.10 macro_indicators — 지수 · 거시지표 일별 값 (FR-MK-02 · 03). 사용자와 무관한 시장 데이터.
 * UNIQUE (indicator_code, base_date) 기준 업서트 — 매 실행 최근 N일을 다시 받아 덮어쓰므로 16:00 잠정값은 다음 실행에서 고쳐진다(B4 확정 사항 2).
 * - indicator_code: 05 §4.10은 VARCHAR(30)이다. Hibernate 6은 @Enumerated(STRING)을 MySQL ENUM으로 만들어 VARCHAR로 고정한다
 * - change_rate: NULL 허용 — BASE_RATE는 값만 저장(05에 NULL 허용 명시 없음 → 문서 반영 필요)
 * - created_at · updated_at: BaseTimeEntity (05 §4.10에 없음 → 문서 반영 필요)
 */
@Entity
@Table(name = "macro_indicators",
        uniqueConstraints = @UniqueConstraint(name = "uk_macro_indicators_code_date", columnNames = {"indicator_code", "base_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MacroIndicator extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "indicator_code", nullable = false, length = 30)
    private IndicatorCode indicatorCode;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    @Column(name = "change_rate", precision = 8, scale = 4)
    private BigDecimal changeRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IndicatorSource source;

    public static MacroIndicator create(IndicatorCode code, LocalDate baseDate) {
        MacroIndicator indicator = new MacroIndicator();
        indicator.indicatorCode = code;
        indicator.baseDate = baseDate;
        indicator.source = code.source();
        return indicator;
    }

    /** 값 · 변동률만 바꾼다(잠정 → 확정). 지표 · 날짜 · 소스는 그대로 */
    public void update(BigDecimal value, BigDecimal changeRate) {
        this.value = value;
        this.changeRate = changeRate;
    }
}
