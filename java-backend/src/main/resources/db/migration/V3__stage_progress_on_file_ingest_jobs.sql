ALTER TABLE file_ingest_jobs
    ADD COLUMN IF NOT EXISTS progress_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_completed INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_file_ingest_jobs_batch_file_stage
    ON file_ingest_jobs(batch_job_id, source_file_id, job_stage);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_created_at
    ON file_ingest_jobs(source_file_id, created_at DESC);
