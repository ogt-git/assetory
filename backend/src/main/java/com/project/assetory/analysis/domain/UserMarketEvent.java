package com.project.assetory.analysis.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
import com.project.assetory.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * docs/05 §5.3 user_market_events — 공유 분석(stock_event_analyses)에 내 비중 · 손익을 붙인 개인 이벤트 (FR-CA-12).
 * UNIQUE (user_id, analysis_id) — 사용자 이벤트 멱등성. 자산 기록 · 알림 · 결산이 이 테이블을 참조한다.
 * - event_date: 조회용 중복 저장. 생성 시 분석 행의 날짜를 복사한다(따로 받지 않아 어긋날 수 없음)
 * - 금액 3개(change_amount · position_ratio · profit_loss_rate): 소급 이벤트는 NULL — 과거 보유수량을 알 수 없다(0 채움 금지)
 * - applied_threshold: 감지 당시 실제 적용 임계치 (FR-DT-07)
 * - notify_status: 생성 시 NONE, 발송 결과는 알림 파이프라인(12주차)이 바꾼다. 소급 이벤트는 알림을 보내지 않는다
 */
@Entity
@Table(name = "user_market_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_market_events_user_analysis",
                columnNames = {"user_id", "analysis_id"}),
        // 05 §10은 (user_id, event_date DESC) — InnoDB가 역방향으로 읽을 수 있어 ASC로 둔다 (A3 결정 C-1, 문서 반영 필요)
        indexes = @Index(name = "idx_user_market_events_user_date", columnList = "user_id, event_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserMarketEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private StockEventAnalysis analysis;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    // 내 평가금액 변동 (원)
    @Column(name = "change_amount", precision = 18, scale = 2)
    private BigDecimal changeAmount;

    // 내 총자산 대비 비중 (퍼센트, PortfolioCalculator와 같은 단위)
    @Column(name = "position_ratio", precision = 5, scale = 2)
    private BigDecimal positionRatio;

    // 내 원금 대비 손익 (퍼센트)
    @Column(name = "profit_loss_rate", precision = 8, scale = 4)
    private BigDecimal profitLossRate;

    @Column(name = "applied_threshold", nullable = false, precision = 10, scale = 4)
    private BigDecimal appliedThreshold;

    @Column(name = "summary_provided", nullable = false)
    private boolean summaryProvided;

    @Column(name = "is_backfilled", nullable = false)
    private boolean backfilled;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_status", nullable = false, length = 20)
    private NotifyStatus notifyStatus;

    public static UserMarketEvent create(User user, StockEventAnalysis analysis, Direction direction,
                                         BigDecimal appliedThreshold, BigDecimal changeAmount,
                                         BigDecimal positionRatio, BigDecimal profitLossRate, boolean backfilled) {
        UserMarketEvent event = new UserMarketEvent();
        event.user = user;
        event.analysis = analysis;
        event.eventDate = analysis.getEventDate();
        event.direction = direction;
        event.appliedThreshold = appliedThreshold;
        event.changeAmount = changeAmount;
        event.positionRatio = positionRatio;
        event.profitLossRate = profitLossRate;
        event.backfilled = backfilled;
        // 요약이 이미 있는 분석(ANALYZED — D5)에 나중에 생긴 이벤트는 처음부터 제공됨 (Q-8 D3-②). 요약이 생기는 순간의 반영은 일괄 갱신(ANL-6)
        event.summaryProvided = analysis.getAnalysisStatus() == AnalysisStatus.ANALYZED;
        event.notifyStatus = NotifyStatus.NONE;
        return event;
    }
}
