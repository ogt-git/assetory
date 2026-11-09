package com.project.assetory.analysis.domain;

import com.project.assetory.global.entity.BaseTimeEntity;
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
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * docs/05 §5.2 event_sources — 공유 분석(stock_event_analyses)의 근거. 종목 · 날짜의 사실이라 사용자와 무관하다.
 * UNIQUE (analysis_id, external_id) = 근거 중복 방지. 저장은 insert-if-absent(EventSourceRepository.insertIfAbsent)로만 한다.
 * - published_at: 발표 · 게시 시각. DART 공시는 시각을 주지 않아 NULL이고, 날짜는 external_id(접수번호) 앞 8자리다 (01 §14 #8).
 *   장 마감 후 자료 구분(FR-CA-08)은 저장이 아니라 표시 단계에서 이 값과 그날 15:30을 비교한다 (#7)
 * - external_id: 공시 접수번호 · 기사 원문 URL
 * - created_at: 뉴스 21일 보관 기준(삭제 배치는 B3) — 05 §5.2에 없는 감사 컬럼, 문서 반영 필요
 */
// 근거는 수집한 그대로 두고 고치지 않는다 — 수정 경로를 막아 둔다
@Immutable
@Entity
@Table(name = "event_sources",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_sources_analysis_external",
                columnNames = {"analysis_id", "external_id"}),
        indexes = @Index(name = "idx_event_sources_analysis_published", columnList = "analysis_id, published_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSource extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private StockEventAnalysis analysis;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "source_name", nullable = false, length = 100)
    private String sourceName;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String snippet;

    @Column(name = "relevance_rank")
    private Integer relevanceRank;

    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;
}
