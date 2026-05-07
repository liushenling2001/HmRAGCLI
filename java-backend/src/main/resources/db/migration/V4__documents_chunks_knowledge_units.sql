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
    language VARCHAR(20) NOT NULL DEFAULT 'zh',
    file_hash VARCHAR(128),
    canonical_doc_id UUID,
    is_canonical BOOLEAN NOT NULL DEFAULT TRUE,
    duplicate_count INT NOT NULL DEFAULT 0,
    is_dev_doc BOOLEAN NOT NULL DEFAULT FALSE,
    doc_domain VARCHAR(50) NOT NULL DEFAULT 'business',
    parse_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_source_file ON documents(source_file);
CREATE INDEX IF NOT EXISTS idx_documents_doc_domain ON documents(doc_domain);

CREATE TABLE IF NOT EXISTS chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_no INT NOT NULL,
    chunk_type VARCHAR(50) NOT NULL DEFAULT 'text',
    title VARCHAR(500),
    content TEXT NOT NULL,
    page_no INT,
    start_offset INT,
    end_offset INT,
    token_count INT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks(doc_id);

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
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_units_doc_id ON knowledge_units(doc_id);
