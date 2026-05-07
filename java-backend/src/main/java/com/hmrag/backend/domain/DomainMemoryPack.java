package com.hmrag.backend.domain;

import com.hmrag.backend.persistence.JsonMapConverter;
import com.hmrag.backend.persistence.JsonStringListConverter;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "domain_memory_packs")
public class DomainMemoryPack {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Column(name = "topic_id")
    private UUID topicId;

    @Column(name = "refine_job_id")
    private UUID refineJobId;

    @Column(name = "artifact_type", nullable = false, length = 50)
    private String artifactType = "draft";

    @Column(nullable = false, length = 50)
    private String status = "ready";

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "key_points_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> keyPointsJson = new ArrayList<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> evidenceRefsJson = new ArrayList<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "source_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> sourceSnapshotJson = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "structured_content_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> structuredContentJson = new HashMap<>();

    @Column(name = "content_markdown", columnDefinition = "text")
    private String contentMarkdown;

    @Column(name = "model_profile", length = 100)
    private String modelProfile;

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
    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }
    public UUID getTopicId() { return topicId; }
    public void setTopicId(UUID topicId) { this.topicId = topicId; }
    public UUID getRefineJobId() { return refineJobId; }
    public void setRefineJobId(UUID refineJobId) { this.refineJobId = refineJobId; }
    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<String> getKeyPointsJson() { return keyPointsJson; }
    public void setKeyPointsJson(List<String> keyPointsJson) { this.keyPointsJson = keyPointsJson; }
    public List<String> getEvidenceRefsJson() { return evidenceRefsJson; }
    public void setEvidenceRefsJson(List<String> evidenceRefsJson) { this.evidenceRefsJson = evidenceRefsJson; }
    public Map<String, Object> getSourceSnapshotJson() { return sourceSnapshotJson; }
    public void setSourceSnapshotJson(Map<String, Object> sourceSnapshotJson) { this.sourceSnapshotJson = sourceSnapshotJson; }
    public Map<String, Object> getStructuredContentJson() { return structuredContentJson; }
    public void setStructuredContentJson(Map<String, Object> structuredContentJson) { this.structuredContentJson = structuredContentJson; }
    public String getContentMarkdown() { return contentMarkdown; }
    public void setContentMarkdown(String contentMarkdown) { this.contentMarkdown = contentMarkdown; }
    public String getModelProfile() { return modelProfile; }
    public void setModelProfile(String modelProfile) { this.modelProfile = modelProfile; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
