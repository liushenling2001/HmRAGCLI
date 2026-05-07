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
@Table(name = "domain_candidates")
public class DomainCandidate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "keywords_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> keywordsJson = new ArrayList<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> evidenceRefsJson = new ArrayList<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "source_snapshot_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> sourceSnapshotJson = new HashMap<>();

    @Column(nullable = false, length = 50)
    private String status = "suggested";

    @Column(name = "trigger_source", nullable = false, length = 50)
    private String triggerSource = "auto";

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "accepted_domain_id")
    private UUID acceptedDomainId;

    @Column(name = "discovery_window_start")
    private OffsetDateTime discoveryWindowStart;

    @Column(name = "discovery_window_end")
    private OffsetDateTime discoveryWindowEnd;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getKeywordsJson() { return keywordsJson; }
    public void setKeywordsJson(List<String> keywordsJson) { this.keywordsJson = keywordsJson; }
    public List<String> getEvidenceRefsJson() { return evidenceRefsJson; }
    public void setEvidenceRefsJson(List<String> evidenceRefsJson) { this.evidenceRefsJson = evidenceRefsJson; }
    public Map<String, Object> getSourceSnapshotJson() { return sourceSnapshotJson; }
    public void setSourceSnapshotJson(Map<String, Object> sourceSnapshotJson) { this.sourceSnapshotJson = sourceSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public UUID getAcceptedDomainId() { return acceptedDomainId; }
    public void setAcceptedDomainId(UUID acceptedDomainId) { this.acceptedDomainId = acceptedDomainId; }
    public OffsetDateTime getDiscoveryWindowStart() { return discoveryWindowStart; }
    public void setDiscoveryWindowStart(OffsetDateTime discoveryWindowStart) { this.discoveryWindowStart = discoveryWindowStart; }
    public OffsetDateTime getDiscoveryWindowEnd() { return discoveryWindowEnd; }
    public void setDiscoveryWindowEnd(OffsetDateTime discoveryWindowEnd) { this.discoveryWindowEnd = discoveryWindowEnd; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
