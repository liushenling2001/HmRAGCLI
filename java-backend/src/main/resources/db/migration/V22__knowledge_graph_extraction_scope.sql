ALTER TABLE graph_build_jobs
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

ALTER TABLE graph_extraction_batches
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

ALTER TABLE graph_candidate_records
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_depth_scope
    ON graph_build_jobs(extraction_depth, scope_type, scope_key);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_depth_scope
    ON graph_extraction_batches(doc_id, extraction_depth, scope_type, scope_key, status);

CREATE INDEX IF NOT EXISTS idx_graph_candidate_records_depth_scope
    ON graph_candidate_records(extraction_depth, scope_type, scope_key, status);
