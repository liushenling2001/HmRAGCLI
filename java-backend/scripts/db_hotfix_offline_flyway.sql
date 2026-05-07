-- Offline emergency hotfix for Flyway initializer stuck/failure
-- 1) Ensure file_ingest_jobs exists and has required columns
-- 2) Ensure flyway_schema_history exists with a baseline row
-- Safe to run multiple times

BEGIN;

CREATE TABLE IF NOT EXISTS file_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id UUID,
    source_file_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    progress_total INT NOT NULL DEFAULT 0,
    progress_completed INT NOT NULL DEFAULT 0,
    heartbeat_at TIMESTAMPTZ,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE IF EXISTS file_ingest_jobs
    ADD COLUMN IF NOT EXISTS job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    ADD COLUMN IF NOT EXISTS progress_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_completed INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

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

CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL PRIMARY KEY,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT now(),
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL
);

INSERT INTO flyway_schema_history (
    installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
)
SELECT
    1, '9', 'offline baseline', 'BASELINE', '<< Flyway Baseline >>', NULL, current_user, 0, TRUE
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history);

COMMIT;

