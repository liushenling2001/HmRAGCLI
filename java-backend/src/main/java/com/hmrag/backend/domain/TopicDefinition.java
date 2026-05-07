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
@Table(name = "topic_definitions")
public class TopicDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Column(name = "parent_topic_id")
    private UUID parentTopicId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "scope_rules_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> scopeRulesJson = new HashMap<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "seed_queries_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> seedQueriesJson = new ArrayList<>();

    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false, length = 50)
    private String status = "active";

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> metadataJson = new HashMap<>();

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
    public UUID getParentTopicId() { return parentTopicId; }
    public void setParentTopicId(UUID parentTopicId) { this.parentTopicId = parentTopicId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getScopeRulesJson() { return scopeRulesJson; }
    public void setScopeRulesJson(Map<String, Object> scopeRulesJson) { this.scopeRulesJson = scopeRulesJson; }
    public List<String> getSeedQueriesJson() { return seedQueriesJson; }
    public void setSeedQueriesJson(List<String> seedQueriesJson) { this.seedQueriesJson = seedQueriesJson; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getMetadataJson() { return metadataJson; }
    public void setMetadataJson(Map<String, Object> metadataJson) { this.metadataJson = metadataJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
