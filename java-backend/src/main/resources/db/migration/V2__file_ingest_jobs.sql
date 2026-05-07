CREATE TABLE IF NOT EXISTS file_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id UUID NOT NULL REFERENCES batch_ingest_jobs(id) ON DELETE CASCADE,
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_batch_job_id ON file_ingest_jobs(batch_job_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_id ON file_ingest_jobs(source_file_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_status ON file_ingest_jobs(status);
