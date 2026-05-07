package com.hmrag.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "batch_ingest_jobs")
public class BatchIngestJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;

    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @Column(nullable = false, length = 50)
    private String status = "pending";

    @Column(name = "total_files", nullable = false)
    private int totalFiles;

    @Column(name = "success_files", nullable = false)
    private int successFiles;

    @Column(name = "failed_files", nullable = false)
    private int failedFiles;

    @Column(name = "skipped_files", nullable = false)
    private int skippedFiles;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(UUID dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getSuccessFiles() { return successFiles; }
    public void setSuccessFiles(int successFiles) { this.successFiles = successFiles; }
    public int getFailedFiles() { return failedFiles; }
    public void setFailedFiles(int failedFiles) { this.failedFiles = failedFiles; }
    public int getSkippedFiles() { return skippedFiles; }
    public void setSkippedFiles(int skippedFiles) { this.skippedFiles = skippedFiles; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
