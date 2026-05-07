CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS data_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name VARCHAR(255) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    root_path VARCHAR(2000) NOT NULL UNIQUE,
    include_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    recursive BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS source_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    file_path VARCHAR(2000) NOT NULL,
    relative_path VARCHAR(1000),
    file_name VARCHAR(500) NOT NULL,
    file_ext VARCHAR(20),
    file_size BIGINT,
    mtime TIMESTAMPTZ,
    file_hash VARCHAR(128),
    discover_status VARCHAR(50) NOT NULL DEFAULT 'active',
    ingest_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    processing_stage VARCHAR(50) NOT NULL DEFAULT 'discovered',
    classification_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    parse_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    extract_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    index_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    duplicate_of_doc_id UUID,
    is_exact_duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    is_possible_duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    last_scan_at TIMESTAMPTZ,
    last_ingest_at TIMESTAMPTZ,
    error_message TEXT,
    doc_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_source_files_data_source_path ON source_files(data_source_id, file_path);
CREATE INDEX IF NOT EXISTS idx_source_files_data_source_id ON source_files(data_source_id);
CREATE INDEX IF NOT EXISTS idx_source_files_ingest_status ON source_files(ingest_status);

CREATE TABLE IF NOT EXISTS scan_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    total_files INT NOT NULL DEFAULT 0,
    new_files INT NOT NULL DEFAULT 0,
    changed_files INT NOT NULL DEFAULT 0,
    missing_files INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    trigger_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    total_files INT NOT NULL DEFAULT 0,
    success_files INT NOT NULL DEFAULT 0,
    failed_files INT NOT NULL DEFAULT 0,
    skipped_files INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
