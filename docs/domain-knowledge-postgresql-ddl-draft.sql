-- PostgreSQL DDL draft for domain knowledge compilation system
-- This draft is designed to coexist with the current HmRAGCLI Java backend schema.

CREATE TABLE IF NOT EXISTS domain_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    goal TEXT,
    scope_rules_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    seed_queries_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    include_data_sources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_data_sources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority INT NOT NULL DEFAULT 0,
    auto_refresh_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_refresh_cron VARCHAR(100),
    active_model_profile VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    created_by VARCHAR(100),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS topic_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES domain_definitions(id) ON DELETE CASCADE,
    parent_topic_id UUID REFERENCES topic_definitions(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    scope_rules_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    seed_queries_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_refine_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type VARCHAR(50) NOT NULL,
    domain_id UUID NOT NULL REFERENCES domain_definitions(id) ON DELETE CASCADE,
    topic_id UUID REFERENCES topic_definitions(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'queued',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'user',
    model_profile VARCHAR(100),
    scope_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    input_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evidence_packs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type VARCHAR(50) NOT NULL,
    owner_id UUID NOT NULL,
    primary_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    support_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    quote_spans_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    context_window_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    section_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    citation_ready_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_briefs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES domain_definitions(id) ON DELETE CASCADE,
    refine_job_id UUID REFERENCES domain_refine_jobs(id) ON DELETE SET NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    summary TEXT,
    domain_boundary TEXT,
    core_concepts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    core_topics_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    key_claims_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    key_metrics_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    conflicts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    timeline_summary_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_pack_id UUID REFERENCES evidence_packs(id) ON DELETE SET NULL,
    llm_context_summary TEXT,
    source_coverage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    compiled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS claim_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type VARCHAR(50) NOT NULL,
    owner_id UUID NOT NULL,
    claim_type VARCHAR(50) NOT NULL,
    claim_text TEXT NOT NULL,
    confidence NUMERIC(4,3),
    valid_scope TEXT,
    effective_time_range VARCHAR(255),
    priority INT NOT NULL DEFAULT 0,
    conflict_set_id UUID,
    evidence_pack_id UUID REFERENCES evidence_packs(id) ON DELETE SET NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conflict_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type VARCHAR(50) NOT NULL,
    owner_id UUID NOT NULL,
    conflict_type VARCHAR(50) NOT NULL,
    summary TEXT NOT NULL,
    side_a_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    side_b_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    possible_reason TEXT,
    resolution_hint TEXT,
    evidence_pack_id UUID REFERENCES evidence_packs(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE claim_sets
    ADD CONSTRAINT fk_claim_sets_conflict_set
    FOREIGN KEY (conflict_set_id) REFERENCES conflict_sets(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS topic_dossiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES domain_definitions(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES topic_definitions(id) ON DELETE CASCADE,
    refine_job_id UUID REFERENCES domain_refine_jobs(id) ON DELETE SET NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    title VARCHAR(500) NOT NULL,
    summary TEXT,
    scope_text TEXT,
    key_points_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    exceptions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    conflicts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    timeline_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    claim_group_id UUID,
    evidence_pack_id UUID REFERENCES evidence_packs(id) ON DELETE SET NULL,
    llm_context_summary TEXT,
    source_coverage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    compiled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domain_definitions_status
    ON domain_definitions(status);

CREATE INDEX IF NOT EXISTS idx_domain_definitions_priority
    ON domain_definitions(priority DESC);

CREATE INDEX IF NOT EXISTS idx_domain_definitions_auto_refresh_enabled
    ON domain_definitions(auto_refresh_enabled);

CREATE INDEX IF NOT EXISTS idx_topic_definitions_domain_id
    ON topic_definitions(domain_id);

CREATE INDEX IF NOT EXISTS idx_topic_definitions_parent_topic_id
    ON topic_definitions(parent_topic_id);

CREATE INDEX IF NOT EXISTS idx_topic_definitions_status
    ON topic_definitions(status);

CREATE INDEX IF NOT EXISTS idx_domain_refine_jobs_domain_id
    ON domain_refine_jobs(domain_id);

CREATE INDEX IF NOT EXISTS idx_domain_refine_jobs_topic_id
    ON domain_refine_jobs(topic_id);

CREATE INDEX IF NOT EXISTS idx_domain_refine_jobs_status
    ON domain_refine_jobs(status);

CREATE INDEX IF NOT EXISTS idx_domain_refine_jobs_job_type
    ON domain_refine_jobs(job_type);

CREATE INDEX IF NOT EXISTS idx_domain_refine_jobs_created_at
    ON domain_refine_jobs(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_evidence_packs_owner
    ON evidence_packs(owner_type, owner_id);

CREATE INDEX IF NOT EXISTS idx_domain_briefs_domain_id
    ON domain_briefs(domain_id);

CREATE INDEX IF NOT EXISTS idx_domain_briefs_status
    ON domain_briefs(status);

CREATE INDEX IF NOT EXISTS idx_domain_briefs_compiled_at
    ON domain_briefs(compiled_at DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_claim_sets_owner
    ON claim_sets(owner_type, owner_id);

CREATE INDEX IF NOT EXISTS idx_claim_sets_claim_type
    ON claim_sets(claim_type);

CREATE INDEX IF NOT EXISTS idx_claim_sets_priority
    ON claim_sets(priority DESC);

CREATE INDEX IF NOT EXISTS idx_conflict_sets_owner
    ON conflict_sets(owner_type, owner_id);

CREATE INDEX IF NOT EXISTS idx_conflict_sets_conflict_type
    ON conflict_sets(conflict_type);

CREATE INDEX IF NOT EXISTS idx_topic_dossiers_domain_id
    ON topic_dossiers(domain_id);

CREATE INDEX IF NOT EXISTS idx_topic_dossiers_topic_id
    ON topic_dossiers(topic_id);

CREATE INDEX IF NOT EXISTS idx_topic_dossiers_status
    ON topic_dossiers(status);

CREATE INDEX IF NOT EXISTS idx_topic_dossiers_compiled_at
    ON topic_dossiers(compiled_at DESC NULLS LAST);
