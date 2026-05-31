ALTER TABLE graph_build_jobs
    ADD COLUMN IF NOT EXISTS graph_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_graph_batch_id
    ON graph_build_jobs(graph_batch_id);

CREATE TABLE IF NOT EXISTS graph_fusion_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    graph_batch_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'queued',
    stage VARCHAR(80) NOT NULL DEFAULT 'queued',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'manual',
    fusion_mode VARCHAR(80) NOT NULL DEFAULT 'deterministic',
    output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_fusion_jobs_status_created_at
    ON graph_fusion_jobs(status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_graph_fusion_jobs_graph_batch_id
    ON graph_fusion_jobs(graph_batch_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_fusion_jobs_batch_active
    ON graph_fusion_jobs(graph_batch_id)
    WHERE graph_batch_id IS NOT NULL AND status IN ('queued', 'running');
