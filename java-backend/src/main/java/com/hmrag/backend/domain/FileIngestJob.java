package com.hmrag.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "file_ingest_jobs")
public class FileIngestJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "batch_job_id", nullable = false)
    private UUID batchJobId;

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    @Column(nullable = false, length = 50)
    private String status = "pending";

    @Column(name = "job_stage", nullable = false, length = 50)
    private String jobStage = "parse";

    @Column(name = "progress_total", nullable = false)
    private int progressTotal;

    @Column(name = "progress_completed", nullable = false)
    private int progressCompleted;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "heartbeat_at")
    private OffsetDateTime heartbeatAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBatchJobId() { return batchJobId; }
    public void setBatchJobId(UUID batchJobId) { this.batchJobId = batchJobId; }
    public UUID getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(UUID sourceFileId) { this.sourceFileId = sourceFileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getJobStage() { return jobStage; }
    public void setJobStage(String jobStage) { this.jobStage = jobStage; }
    public int getProgressTotal() { return progressTotal; }
    public void setProgressTotal(int progressTotal) { this.progressTotal = progressTotal; }
    public int getProgressCompleted() { return progressCompleted; }
    public void setProgressCompleted(int progressCompleted) { this.progressCompleted = progressCompleted; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(OffsetDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
