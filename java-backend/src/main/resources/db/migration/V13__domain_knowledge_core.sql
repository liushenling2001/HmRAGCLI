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
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_memory_packs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES domain_definitions(id) ON DELETE CASCADE,
    topic_id UUID REFERENCES topic_definitions(id) ON DELETE SET NULL,
    refine_job_id UUID REFERENCES domain_refine_jobs(id) ON DELETE SET NULL,
    artifact_type VARCHAR(50) NOT NULL DEFAULT 'draft',
    status VARCHAR(50) NOT NULL DEFAULT 'ready',
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    key_points_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_markdown TEXT,
    model_profile VARCHAR(100),
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

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_domain_id
    ON domain_memory_packs(domain_id);

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_topic_id
    ON domain_memory_packs(topic_id);

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_refine_job_id
    ON domain_memory_packs(refine_job_id);

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_artifact_type
    ON domain_memory_packs(artifact_type);

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_created_at
    ON domain_memory_packs(created_at DESC);
