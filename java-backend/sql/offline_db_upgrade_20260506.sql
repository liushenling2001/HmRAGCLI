-- HmRAG offline incremental database upgrade script.
--
-- Target:
--   Upgrade an existing offline PostgreSQL database for the current Java backend.
--
-- Usage:
--   Stop the HmRAG Java backend first, then run:
--   psql -h 127.0.0.1 -U postgres -d hmrag -f D:\workspace\HmRAGCLI\java-backend\sql\offline_db_upgrade_20260506.sql
--
-- Notes:
--   1. This script is idempotent: it can be run more than once.
--   2. It does not delete user data.
--   3. pgvector must be installed in PostgreSQL before CREATE EXTENSION vector can succeed.
--   4. For large libraries, index creation can take time. Run while the application is stopped.

\echo === HmRAG offline DB upgrade 2026-05-06 started ===

SET lock_timeout = '10s';
SET statement_timeout = '0';

\echo === Step 1: required extensions ===

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

\echo === Step 2: legacy schema alignment ===

ALTER TABLE IF EXISTS source_files
    ALTER COLUMN file_size TYPE BIGINT;

ALTER TABLE IF EXISTS file_ingest_jobs
    ADD COLUMN IF NOT EXISTS stage VARCHAR(50),
    ADD COLUMN IF NOT EXISTS stage_progress_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_source_files_data_source_doc_id
    ON source_files(data_source_id, doc_id)
    WHERE doc_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_source_files_doc_id_created_at
    ON source_files(doc_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_status_heartbeat
    ON file_ingest_jobs(status, heartbeat_at DESC);

\echo === Step 3: pgvector storage columns and support indexes ===

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_dimensions_updated_at
    ON knowledge_unit_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_dimensions_updated_at
    ON chunk_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_updated_at
    ON knowledge_unit_embeddings(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_updated_at
    ON chunk_embeddings(updated_at DESC);

\echo === Step 4: backfill pgvector data from legacy vector_json ===

CREATE OR REPLACE PROCEDURE hmrag_backfill_ku_pgvector(batch_size integer DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT ctid
            FROM knowledge_unit_embeddings
            WHERE embedding_vector IS NULL
              AND vector_json IS NOT NULL
              AND jsonb_typeof(vector_json) = 'array'
              AND jsonb_array_length(vector_json) > 0
            LIMIT batch_size
        )
        UPDATE knowledge_unit_embeddings t
        SET embedding_vector = (t.vector_json::text)::vector
        FROM batch
        WHERE t.ctid = batch.ctid;

        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'knowledge_unit_embeddings migrated rows: %', v_rows;
        EXIT WHEN v_rows = 0;
        COMMIT;
    END LOOP;
END;
$$;

CREATE OR REPLACE PROCEDURE hmrag_backfill_chunk_pgvector(batch_size integer DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT ctid
            FROM chunk_embeddings
            WHERE embedding_vector IS NULL
              AND vector_json IS NOT NULL
              AND jsonb_typeof(vector_json) = 'array'
              AND jsonb_array_length(vector_json) > 0
            LIMIT batch_size
        )
        UPDATE chunk_embeddings t
        SET embedding_vector = (t.vector_json::text)::vector
        FROM batch
        WHERE t.ctid = batch.ctid;

        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'chunk_embeddings migrated rows: %', v_rows;
        EXIT WHEN v_rows = 0;
        COMMIT;
    END LOOP;
END;
$$;

CALL hmrag_backfill_ku_pgvector(1000);
CALL hmrag_backfill_chunk_pgvector(1000);

\echo === Step 5: query performance indexes ===

CREATE INDEX IF NOT EXISTS idx_documents_search_tsv_gin
    ON documents USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS idx_chunks_search_tsv_gin
    ON chunks USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS idx_knowledge_units_search_tsv_gin
    ON knowledge_units USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS idx_documents_title_trgm
    ON documents USING gin (lower(title) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_documents_source_filename_trgm
    ON documents USING gin (lower(source_filename) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_source_files_relative_path_trgm
    ON source_files USING gin (lower(relative_path) gin_trgm_ops);

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

\echo === Step 6: domain knowledge core tables ===

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

ALTER TABLE domain_definitions
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS goal TEXT,
    ADD COLUMN IF NOT EXISTS scope_rules_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS seed_queries_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS include_data_sources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS exclude_data_sources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS auto_refresh_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS auto_refresh_cron VARCHAR(100),
    ADD COLUMN IF NOT EXISTS active_model_profile VARCHAR(100),
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'draft',
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

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

ALTER TABLE topic_definitions
    ADD COLUMN IF NOT EXISTS parent_topic_id UUID,
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS scope_rules_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS seed_queries_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'active',
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

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

ALTER TABLE domain_refine_jobs
    ADD COLUMN IF NOT EXISTS topic_id UUID,
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'queued',
    ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(50) NOT NULL DEFAULT 'user',
    ADD COLUMN IF NOT EXISTS model_profile VARCHAR(100),
    ADD COLUMN IF NOT EXISTS scope_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS input_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

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
    structured_content_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_markdown TEXT,
    model_profile VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE domain_memory_packs
    ADD COLUMN IF NOT EXISTS topic_id UUID,
    ADD COLUMN IF NOT EXISTS refine_job_id UUID,
    ADD COLUMN IF NOT EXISTS artifact_type VARCHAR(50) NOT NULL DEFAULT 'draft',
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'ready',
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS key_points_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS structured_content_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS content_markdown TEXT,
    ADD COLUMN IF NOT EXISTS model_profile VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

\echo === Step 7: domain candidate discovery tables ===

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

ALTER TABLE domain_candidates
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'suggested',
    ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(50) NOT NULL DEFAULT 'auto',
    ADD COLUMN IF NOT EXISTS review_note TEXT,
    ADD COLUMN IF NOT EXISTS accepted_domain_id UUID,
    ADD COLUMN IF NOT EXISTS discovery_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS discovery_window_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS domain_candidate_discovery_state (
    trigger_source VARCHAR(50) PRIMARY KEY,
    cursor_window_start TIMESTAMPTZ,
    cursor_window_end TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    coverage_completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE domain_candidate_discovery_state
    ADD COLUMN IF NOT EXISTS cursor_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cursor_window_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_run_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

\echo === Step 8: domain indexes ===

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

CREATE INDEX IF NOT EXISTS idx_domain_memory_packs_status
    ON domain_memory_packs(status);

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

\echo === Step 9: clear stale non-terminal domain jobs after upgrade ===

UPDATE domain_refine_jobs
SET status = 'failed',
    finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP,
    error_message = COALESCE(error_message, 'Marked failed by offline DB upgrade because job was non-terminal during deployment.')
WHERE status IN ('queued', 'running', 'cancelling', 'paused')
  AND created_at < CURRENT_TIMESTAMP - INTERVAL '1 hour';

\echo === Step 10: refresh planner statistics ===

ANALYZE source_files;
ANALYZE file_ingest_jobs;
ANALYZE documents;
ANALYZE chunks;
ANALYZE knowledge_units;
ANALYZE knowledge_unit_embeddings;
ANALYZE chunk_embeddings;
ANALYZE domain_definitions;
ANALYZE topic_definitions;
ANALYZE domain_refine_jobs;
ANALYZE domain_memory_packs;
ANALYZE domain_candidates;
ANALYZE domain_candidate_discovery_state;

\echo === Step 11: verification ===

SELECT to_regclass('public.domain_definitions') AS domain_definitions;
SELECT to_regclass('public.topic_definitions') AS topic_definitions;
SELECT to_regclass('public.domain_refine_jobs') AS domain_refine_jobs;
SELECT to_regclass('public.domain_memory_packs') AS domain_memory_packs;
SELECT to_regclass('public.domain_candidates') AS domain_candidates;
SELECT to_regclass('public.domain_candidate_discovery_state') AS domain_candidate_discovery_state;

SELECT table_name, column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN (
      'source_files',
      'knowledge_unit_embeddings',
      'chunk_embeddings',
      'domain_definitions',
      'topic_definitions',
      'domain_refine_jobs',
      'domain_memory_packs',
      'domain_candidates',
      'domain_candidate_discovery_state'
  )
ORDER BY table_name, ordinal_position;

SELECT count(*) AS ku_vector_backfill_pending
FROM knowledge_unit_embeddings
WHERE embedding_vector IS NULL
  AND vector_json IS NOT NULL
  AND jsonb_typeof(vector_json) = 'array'
  AND jsonb_array_length(vector_json) > 0;

SELECT count(*) AS chunk_vector_backfill_pending
FROM chunk_embeddings
WHERE embedding_vector IS NULL
  AND vector_json IS NOT NULL
  AND jsonb_typeof(vector_json) = 'array'
  AND jsonb_array_length(vector_json) > 0;

SELECT schemaname, tablename, indexname
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN (
      'documents',
      'chunks',
      'knowledge_units',
      'source_files',
      'knowledge_unit_embeddings',
      'chunk_embeddings',
      'domain_definitions',
      'topic_definitions',
      'domain_refine_jobs',
      'domain_memory_packs',
      'domain_candidates',
      'domain_candidate_discovery_state'
  )
ORDER BY tablename, indexname;

\echo === HmRAG offline DB upgrade 2026-05-06 completed ===
