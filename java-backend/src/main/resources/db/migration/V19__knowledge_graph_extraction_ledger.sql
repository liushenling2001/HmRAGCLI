CREATE TABLE IF NOT EXISTS graph_extraction_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    graph_version VARCHAR(100) NOT NULL,
    extraction_profile VARCHAR(80) NOT NULL DEFAULT 'default',
    batch_no INT NOT NULL,
    batch_count INT NOT NULL DEFAULT 0,
    batch_key VARCHAR(128) NOT NULL UNIQUE,
    input_hash VARCHAR(128) NOT NULL,
    chunk_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    entity_mentions INT NOT NULL DEFAULT 0,
    facts INT NOT NULL DEFAULT 0,
    relations INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(120),
    lease_until TIMESTAMPTZ,
    error_message TEXT,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_doc_status
    ON graph_extraction_batches(doc_id, status, batch_no);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_source_file
    ON graph_extraction_batches(source_file_id, batch_no);

CREATE TABLE IF NOT EXISTS graph_extraction_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES graph_extraction_batches(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'running',
    raw_request TEXT,
    raw_response TEXT,
    parsed_output_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_attempts_batch_created
    ON graph_extraction_attempts(batch_id, created_at DESC);

CREATE TABLE IF NOT EXISTS graph_candidate_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES graph_extraction_batches(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES graph_extraction_attempts(id) ON DELETE SET NULL,
    candidate_key VARCHAR(260) NOT NULL UNIQUE,
    candidate_type VARCHAR(40) NOT NULL,
    stable_local_id VARCHAR(500),
    payload_hash VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_candidate_records_batch_type
    ON graph_candidate_records(batch_id, candidate_type, status);
