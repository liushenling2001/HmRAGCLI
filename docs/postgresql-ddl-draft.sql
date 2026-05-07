CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500) NOT NULL,
    doc_type VARCHAR(50) NOT NULL,
    source_file VARCHAR(1000) NOT NULL,
    source_filename VARCHAR(500),
    source_org VARCHAR(255),
    author VARCHAR(255),
    publish_date DATE,
    effective_date DATE,
    expiry_date DATE,
    status VARCHAR(50) NOT NULL DEFAULT 'reference',
    language VARCHAR(20) DEFAULT 'zh',
    file_hash VARCHAR(128),
    parse_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_doc_type ON documents(doc_type);
CREATE INDEX IF NOT EXISTS idx_documents_publish_date ON documents(publish_date);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_source_org ON documents(source_org);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_documents_file_hash ON documents(file_hash) WHERE file_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_no INT NOT NULL,
    chunk_type VARCHAR(50) NOT NULL,
    title VARCHAR(500),
    content TEXT NOT NULL,
    page_no INT,
    start_offset INT,
    end_offset INT,
    token_count INT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunks_chunk_type ON chunks(chunk_type);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_chunks_doc_chunk_no ON chunks(doc_id, chunk_no);

CREATE TABLE IF NOT EXISTS knowledge_units (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_id UUID REFERENCES chunks(id) ON DELETE SET NULL,
    unit_type VARCHAR(50) NOT NULL,
    title VARCHAR(500),
    content TEXT NOT NULL,
    normalized_text TEXT,
    subject VARCHAR(255),
    action VARCHAR(255),
    organization VARCHAR(255),
    person VARCHAR(255),
    region VARCHAR(255),
    time_expr VARCHAR(255),
    event_date DATE,
    indicator VARCHAR(255),
    value_num NUMERIC(20,6),
    value_text VARCHAR(255),
    unit_name VARCHAR(100),
    effective_date DATE,
    expiry_date DATE,
    status VARCHAR(50) NOT NULL DEFAULT 'reference',
    priority INT NOT NULL DEFAULT 0,
    confidence NUMERIC(4,3),
    source_span VARCHAR(255),
    source_page INT,
    fields_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ku_doc_id ON knowledge_units(doc_id);
CREATE INDEX IF NOT EXISTS idx_ku_unit_type ON knowledge_units(unit_type);
CREATE INDEX IF NOT EXISTS idx_ku_subject ON knowledge_units(subject);
CREATE INDEX IF NOT EXISTS idx_ku_indicator ON knowledge_units(indicator);
CREATE INDEX IF NOT EXISTS idx_ku_region ON knowledge_units(region);
CREATE INDEX IF NOT EXISTS idx_ku_event_date ON knowledge_units(event_date);
CREATE INDEX IF NOT EXISTS idx_ku_status ON knowledge_units(status);

CREATE TABLE IF NOT EXISTS knowledge_unit_relations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_unit_id UUID NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    to_unit_id UUID NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    relation_type VARCHAR(50) NOT NULL,
    relation_note VARCHAR(500),
    confidence NUMERIC(4,3),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ku_rel_from ON knowledge_unit_relations(from_unit_id);
CREATE INDEX IF NOT EXISTS idx_ku_rel_to ON knowledge_unit_relations(to_unit_id);
CREATE INDEX IF NOT EXISTS idx_ku_rel_type ON knowledge_unit_relations(relation_type);

CREATE TABLE IF NOT EXISTS tag_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tag_type VARCHAR(50) NOT NULL,
    tag_name VARCHAR(100) NOT NULL,
    tag_code VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_tag_type_code ON tag_definitions(tag_type, tag_code);
CREATE INDEX IF NOT EXISTS idx_tag_type_name ON tag_definitions(tag_type, tag_name);

CREATE TABLE IF NOT EXISTS document_tags (
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tag_definitions(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (doc_id, tag_id)
);

CREATE TABLE IF NOT EXISTS knowledge_unit_tags (
    unit_id UUID NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tag_definitions(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (unit_id, tag_id)
);

CREATE TABLE IF NOT EXISTS table_sheets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    sheet_name VARCHAR(255) NOT NULL,
    description TEXT,
    header_row_no INT,
    time_field VARCHAR(255),
    dimension_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    metric_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_table_sheets_doc_id ON table_sheets(doc_id);

CREATE TABLE IF NOT EXISTS table_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    sheet_id UUID NOT NULL REFERENCES table_sheets(id) ON DELETE CASCADE,
    record_key VARCHAR(255),
    time_value VARCHAR(100),
    region VARCHAR(255),
    organization VARCHAR(255),
    dimensions_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_row_no INT,
    confidence NUMERIC(4,3),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_table_records_doc_id ON table_records(doc_id);
CREATE INDEX IF NOT EXISTS idx_table_records_sheet_id ON table_records(sheet_id);
CREATE INDEX IF NOT EXISTS idx_table_records_region ON table_records(region);
CREATE INDEX IF NOT EXISTS idx_table_records_time_value ON table_records(time_value);

CREATE TABLE IF NOT EXISTS ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    job_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ingest_jobs_doc_id ON ingest_jobs(doc_id);
CREATE INDEX IF NOT EXISTS idx_ingest_jobs_status ON ingest_jobs(status);

CREATE TABLE IF NOT EXISTS data_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name VARCHAR(255) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    root_path VARCHAR(2000) NOT NULL,
    include_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    recursive BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_data_sources_root_path ON data_sources(root_path);

CREATE TABLE IF NOT EXISTS source_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    file_path VARCHAR(2000) NOT NULL,
    relative_path VARCHAR(1000),
    file_name VARCHAR(500) NOT NULL,
    file_ext VARCHAR(20),
    file_size BIGINT,
    mtime TIMESTAMP,
    file_hash VARCHAR(128),
    discover_status VARCHAR(50) NOT NULL DEFAULT 'active',
    ingest_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    last_scan_at TIMESTAMP,
    last_ingest_at TIMESTAMP,
    error_message TEXT,
    doc_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_source_files_data_source_id ON source_files(data_source_id);
CREATE INDEX IF NOT EXISTS idx_source_files_file_path ON source_files(file_path);
CREATE INDEX IF NOT EXISTS idx_source_files_file_hash ON source_files(file_hash);
CREATE INDEX IF NOT EXISTS idx_source_files_ingest_status ON source_files(ingest_status);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_source_files_data_source_path ON source_files(data_source_id, file_path);

CREATE TABLE IF NOT EXISTS scan_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    total_files INT NOT NULL DEFAULT 0,
    new_files INT NOT NULL DEFAULT 0,
    changed_files INT NOT NULL DEFAULT 0,
    missing_files INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scan_jobs_data_source_id ON scan_jobs(data_source_id);

CREATE TABLE IF NOT EXISTS batch_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    trigger_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_files INT NOT NULL DEFAULT 0,
    success_files INT NOT NULL DEFAULT 0,
    failed_files INT NOT NULL DEFAULT 0,
    skipped_files INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_batch_ingest_jobs_data_source_id ON batch_ingest_jobs(data_source_id);
CREATE INDEX IF NOT EXISTS idx_batch_ingest_jobs_status ON batch_ingest_jobs(status);

CREATE TABLE IF NOT EXISTS file_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id UUID NOT NULL REFERENCES batch_ingest_jobs(id) ON DELETE CASCADE,
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    parser_name VARCHAR(100),
    extract_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    index_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_batch_job_id ON file_ingest_jobs(batch_job_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_id ON file_ingest_jobs(source_file_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_status ON file_ingest_jobs(status);
