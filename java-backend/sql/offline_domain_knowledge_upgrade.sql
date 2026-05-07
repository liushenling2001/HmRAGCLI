-- HmRAG offline upgrade script for domain knowledge, candidate discovery,
-- and rolling historical coverage state.
--
-- Usage:
--   psql -h 127.0.0.1 -U postgres -d hmrag -f D:\workspace\HmRAGCLI\java-backend\sql\offline_domain_knowledge_upgrade.sql
--
-- Scope:
--   1. domain_definitions / topic_definitions / domain_refine_jobs / domain_memory_packs
--   2. domain_candidates
--   3. domain_candidate_discovery_state
--   4. supporting indexes for offline candidate discovery performance

\echo === Step 1: domain knowledge core tables ===

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

\echo === Step 2: candidate domain tables ===

CREATE TABLE IF NOT EXISTS domain_candidates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'suggested',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'auto',
    review_note TEXT,
    accepted_domain_id UUID REFERENCES domain_definitions(id) ON DELETE SET NULL,
    discovery_window_start TIMESTAMPTZ,
    discovery_window_end TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_candidate_discovery_state (
    trigger_source VARCHAR(50) PRIMARY KEY,
    cursor_window_start TIMESTAMPTZ,
    cursor_window_end TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    coverage_completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

\echo === Step 3: domain knowledge indexes ===

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

CREATE INDEX IF NOT EXISTS idx_domain_candidates_status
    ON domain_candidates(status);

CREATE INDEX IF NOT EXISTS idx_domain_candidates_trigger_source
    ON domain_candidates(trigger_source);

CREATE INDEX IF NOT EXISTS idx_domain_candidates_created_at
    ON domain_candidates(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_domain_candidate_discovery_state_last_run_at
    ON domain_candidate_discovery_state(last_run_at DESC);

\echo === Step 4: supporting indexes for candidate discovery over large offline libraries ===

CREATE INDEX IF NOT EXISTS idx_documents_observed_at
    ON documents ((COALESCE(updated_at, created_at)) DESC);

CREATE INDEX IF NOT EXISTS idx_documents_title_lower
    ON documents (lower(title));

CREATE INDEX IF NOT EXISTS idx_documents_source_filename_lower
    ON documents (lower(source_filename));

CREATE INDEX IF NOT EXISTS idx_knowledge_units_observed_at
    ON knowledge_units ((COALESCE(updated_at, created_at)) DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_units_subject_lower
    ON knowledge_units (lower(subject));

CREATE INDEX IF NOT EXISTS idx_knowledge_units_action_lower
    ON knowledge_units (lower(action));

CREATE INDEX IF NOT EXISTS idx_knowledge_units_organization_lower
    ON knowledge_units (lower(organization));

CREATE INDEX IF NOT EXISTS idx_knowledge_units_indicator_lower
    ON knowledge_units (lower(indicator));

CREATE INDEX IF NOT EXISTS idx_chunks_created_at
    ON chunks(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunks_title_lower
    ON chunks (lower(title));

\echo === Step 5: planner stats ===

ANALYZE domain_definitions;
ANALYZE topic_definitions;
ANALYZE domain_refine_jobs;
ANALYZE domain_memory_packs;
ANALYZE domain_candidates;
ANALYZE domain_candidate_discovery_state;
ANALYZE documents;
ANALYZE knowledge_units;
ANALYZE chunks;

\echo === Step 6: verification ===

SELECT to_regclass('public.domain_definitions') AS domain_definitions_table;
SELECT to_regclass('public.domain_candidates') AS domain_candidates_table;
SELECT to_regclass('public.domain_candidate_discovery_state') AS domain_candidate_discovery_state_table;

SELECT indexname
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN (
      'domain_definitions',
      'topic_definitions',
      'domain_refine_jobs',
      'domain_memory_packs',
      'domain_candidates',
      'domain_candidate_discovery_state',
      'documents',
      'knowledge_units',
      'chunks'
  )
ORDER BY tablename, indexname;
