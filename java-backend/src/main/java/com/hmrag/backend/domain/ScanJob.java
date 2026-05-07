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
@Table(name = "scan_jobs")
public class ScanJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;

    @Column(nullable = false, length = 50)
    private String status = "pending";

    @Column(name = "total_files", nullable = false)
    private int totalFiles;

    @Column(name = "new_files", nullable = false)
    private int newFiles;

    @Column(name = "changed_files", nullable = false)
    private int changedFiles;

    @Column(name = "missing_files", nullable = false)
    private int missingFiles;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getNewFiles() { return newFiles; }
    public void setNewFiles(int newFiles) { this.newFiles = newFiles; }
    public int getChangedFiles() { return changedFiles; }
    public void setChangedFiles(int changedFiles) { this.changedFiles = changedFiles; }
    public int getMissingFiles() { return missingFiles; }
    public void setMissingFiles(int missingFiles) { this.missingFiles = missingFiles; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
