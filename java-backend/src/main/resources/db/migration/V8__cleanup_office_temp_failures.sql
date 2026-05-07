-- Normalize legacy Office temporary files (~$*) that were previously marked as failures.
-- These files should be ignored/skipped and must not pollute failure dashboards.
UPDATE source_files
SET
    ingest_status = 'ignored',
    processing_stage = 'ignored',
    classification_status = 'skipped',
    parse_status = 'skipped',
    extract_status = 'skipped',
    index_status = 'skipped',
    error_message = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE file_name LIKE '~$%'
  AND (
    ingest_status = 'failed'
    OR processing_stage = 'rejected'
    OR classification_status = 'failed'
  );

