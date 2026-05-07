package com.hmrag.backend.domain;

import com.hmrag.backend.persistence.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "domain_refine_jobs")
public class DomainRefineJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "job_type", nullable = false, length = 50)
    private String jobType;

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Column(name = "topic_id")
    private UUID topicId;

    @Column(nullable = false, length = 50)
    private String status = "queued";

    @Column(name = "trigger_source", nullable = false, length = 50)
    private String triggerSource = "user";

    @Column(name = "model_profile", length = 100)
    private String modelProfile;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "scope_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> scopeSnapshotJson = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "input_summary_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> inputSummaryJson = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "output_summary_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> outputSummaryJson = new HashMap<>();

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
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }
    public UUID getTopicId() { return topicId; }
    public void setTopicId(UUID topicId) { this.topicId = topicId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getModelProfile() { return modelProfile; }
    public void setModelProfile(String modelProfile) { this.modelProfile = modelProfile; }
    public Map<String, Object> getScopeSnapshotJson() { return scopeSnapshotJson; }
    public void setScopeSnapshotJson(Map<String, Object> scopeSnapshotJson) { this.scopeSnapshotJson = scopeSnapshotJson; }
    public Map<String, Object> getInputSummaryJson() { return inputSummaryJson; }
    public void setInputSummaryJson(Map<String, Object> inputSummaryJson) { this.inputSummaryJson = inputSummaryJson; }
    public Map<String, Object> getOutputSummaryJson() { return outputSummaryJson; }
    public void setOutputSummaryJson(Map<String, Object> outputSummaryJson) { this.outputSummaryJson = outputSummaryJson; }
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
