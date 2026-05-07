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
@Table(name = "domain_definitions")
public class DomainDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String goal;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "scope_rules_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<String, Object> scopeRulesJson = new HashMap<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "seed_queries_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> seedQueriesJson = new ArrayList<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "include_data_sources_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> includeDataSourcesJson = new ArrayList<>();

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "exclude_data_sources_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<String> excludeDataSourcesJson = new ArrayList<>();

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "auto_refresh_enabled", nullable = false)
    private boolean autoRefreshEnabled;

    @Column(name = "auto_refresh_cron", length = 100)
    private String autoRefreshCron;

    @Column(name = "active_model_profile", length = 100)
    private String activeModelProfile;

    @Column(nullable = false, length = 50)
    private String status = "draft";

    @Column(name = "created_by", length = 100)
    private String createdBy;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public Map<String, Object> getScopeRulesJson() { return scopeRulesJson; }
    public void setScopeRulesJson(Map<String, Object> scopeRulesJson) { this.scopeRulesJson = scopeRulesJson; }
    public List<String> getSeedQueriesJson() { return seedQueriesJson; }
    public void setSeedQueriesJson(List<String> seedQueriesJson) { this.seedQueriesJson = seedQueriesJson; }
    public List<String> getIncludeDataSourcesJson() { return includeDataSourcesJson; }
    public void setIncludeDataSourcesJson(List<String> includeDataSourcesJson) { this.includeDataSourcesJson = includeDataSourcesJson; }
    public List<String> getExcludeDataSourcesJson() { return excludeDataSourcesJson; }
    public void setExcludeDataSourcesJson(List<String> excludeDataSourcesJson) { this.excludeDataSourcesJson = excludeDataSourcesJson; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isAutoRefreshEnabled() { return autoRefreshEnabled; }
    public void setAutoRefreshEnabled(boolean autoRefreshEnabled) { this.autoRefreshEnabled = autoRefreshEnabled; }
    public String getAutoRefreshCron() { return autoRefreshCron; }
    public void setAutoRefreshCron(String autoRefreshCron) { this.autoRefreshCron = autoRefreshCron; }
    public String getActiveModelProfile() { return activeModelProfile; }
    public void setActiveModelProfile(String activeModelProfile) { this.activeModelProfile = activeModelProfile; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Map<String, Object> getMetadataJson() { return metadataJson; }
    public void setMetadataJson(Map<String, Object> metadataJson) { this.metadataJson = metadataJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
