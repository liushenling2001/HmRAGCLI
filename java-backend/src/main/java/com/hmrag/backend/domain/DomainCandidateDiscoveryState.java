package com.hmrag.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "domain_candidate_discovery_state")
public class DomainCandidateDiscoveryState {

    @Id
    @Column(name = "trigger_source", nullable = false, length = 50)
    private String triggerSource;

    @Column(name = "cursor_window_start")
    private OffsetDateTime cursorWindowStart;

    @Column(name = "cursor_window_end")
    private OffsetDateTime cursorWindowEnd;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "coverage_completed_at")
    private OffsetDateTime coverageCompletedAt;

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

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public OffsetDateTime getCursorWindowStart() {
        return cursorWindowStart;
    }

    public void setCursorWindowStart(OffsetDateTime cursorWindowStart) {
        this.cursorWindowStart = cursorWindowStart;
    }

    public OffsetDateTime getCursorWindowEnd() {
        return cursorWindowEnd;
    }

    public void setCursorWindowEnd(OffsetDateTime cursorWindowEnd) {
        this.cursorWindowEnd = cursorWindowEnd;
    }

    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(OffsetDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public OffsetDateTime getCoverageCompletedAt() {
        return coverageCompletedAt;
    }

    public void setCoverageCompletedAt(OffsetDateTime coverageCompletedAt) {
        this.coverageCompletedAt = coverageCompletedAt;
    }
}
