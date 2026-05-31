CREATE TABLE IF NOT EXISTS graph_build_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'queued',
    stage VARCHAR(80) NOT NULL DEFAULT 'queued',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'ingest',
    graph_store VARCHAR(50) NOT NULL DEFAULT 'neo4j-http',
    graph_version VARCHAR(100),
    extract_model VARCHAR(200),
    fusion_model VARCHAR(200),
    local_graph_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_status_created_at
    ON graph_build_jobs(status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_source_file_id
    ON graph_build_jobs(source_file_id);

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_doc_id
    ON graph_build_jobs(doc_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_build_jobs_source_file_active
    ON graph_build_jobs(source_file_id)
    WHERE status IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS graph_sync_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id UUID NOT NULL UNIQUE REFERENCES source_files(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    graph_store VARCHAR(50) NOT NULL DEFAULT 'neo4j-http',
    graph_version VARCHAR(100),
    graph_document_key VARCHAR(200),
    content_fingerprint VARCHAR(128),
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    last_build_job_id UUID REFERENCES graph_build_jobs(id) ON DELETE SET NULL,
    last_built_at TIMESTAMPTZ,
    error_message TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_sync_state_doc_id
    ON graph_sync_state(doc_id);

CREATE INDEX IF NOT EXISTS idx_graph_sync_state_status
    ON graph_sync_state(status);
