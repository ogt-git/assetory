package com.project.assetory.connect.domain;

import com.project.assetory.connect.codef.CodefErrorType;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// docs/05 §4.3 sync_jobs — 수집 실행마다 1행 (FR-CN-07). error_message에는 사용자용 문구만 남긴다 (계좌번호·connectedId·응답 원문 금지)
@Entity
@Table(name = "sync_jobs",
        indexes = @Index(name = "idx_sync_jobs_account_target_date", columnList = "connected_account_id, target_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connected_account_id", nullable = false)
    private ConnectedAccount connectedAccount;

    // 수집 대상 기준일 (KST)
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private SyncJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncJobStatus status;

    @Column(name = "collected_count")
    private Integer collectedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 40)
    private CodefErrorType errorType;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public static SyncJob start(ConnectedAccount connectedAccount, SyncJobType jobType,
                                LocalDate targetDate, LocalDateTime startedAt) {
        SyncJob job = new SyncJob();
        job.connectedAccount = connectedAccount;
        job.jobType = jobType;
        job.targetDate = targetDate;
        job.status = SyncJobStatus.RUNNING;
        job.startedAt = startedAt;
        return job;
    }

    public void succeed(int collectedCount, LocalDateTime finishedAt) {
        this.status = SyncJobStatus.SUCCESS;
        this.collectedCount = collectedCount;
        this.finishedAt = finishedAt;
    }

    public void fail(CodefErrorType errorType, String errorMessage, LocalDateTime finishedAt) {
        this.status = SyncJobStatus.FAILED;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.finishedAt = finishedAt;
    }
}
