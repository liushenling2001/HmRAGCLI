-- HmRAG offline DB hotfix
-- Purpose: fix legacy schema missing file_ingest_jobs.job_stage and stage-progress columns
-- Safe to run multiple times (idempotent)

BEGIN;

ALTER TABLE IF EXISTS file_ingest_jobs
    ADD COLUMN IF NOT EXISTS job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    ADD COLUMN IF NOT EXISTS progress_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_completed INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE file_ingest_jobs
SET job_stage = 'parse'
WHERE job_stage IS NULL OR btrim(job_stage) = '';

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_batch_job_id
    ON file_ingest_jobs(batch_job_id);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_id
    ON file_ingest_jobs(source_file_id);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_status
    ON file_ingest_jobs(status);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_created_at
    ON file_ingest_jobs(source_file_id, created_at DESC);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'file_ingest_jobs'
          AND column_name = 'batch_job_id'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'file_ingest_jobs'
          AND column_name = 'source_file_id'
    )
    AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'file_ingest_jobs'
          AND column_name = 'job_stage'
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_file_ingest_jobs_batch_file_stage
            ON file_ingest_jobs(batch_job_id, source_file_id, job_stage);
    END IF;
END $$;

COMMIT;

