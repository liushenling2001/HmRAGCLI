CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

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

CREATE TABLE IF NOT EXISTS file_ingest_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id UUID REFERENCES batch_ingest_jobs(id) ON DELETE CASCADE,
    source_file_id UUID REFERENCES source_files(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    progress_total INT NOT NULL DEFAULT 0,
    progress_completed INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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

CREATE TABLE IF NOT EXISTS knowledge_unit_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_unit_id UUID NOT NULL UNIQUE REFERENCES knowledge_units(id) ON DELETE CASCADE,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    dimensions INT NOT NULL DEFAULT 0,
    vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chunk_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id UUID NOT NULL UNIQUE REFERENCES chunks(id) ON DELETE CASCADE,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    dimensions INT NOT NULL DEFAULT 0,
    vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE source_files
    ADD COLUMN IF NOT EXISTS file_path VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS relative_path VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS file_name VARCHAR(500),
    ADD COLUMN IF NOT EXISTS file_ext VARCHAR(20),
    ADD COLUMN IF NOT EXISTS file_size BIGINT,
    ADD COLUMN IF NOT EXISTS mtime TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS file_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS discover_status VARCHAR(50) NOT NULL DEFAULT 'active',
    ADD COLUMN IF NOT EXISTS ingest_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS processing_stage VARCHAR(50) NOT NULL DEFAULT 'discovered',
    ADD COLUMN IF NOT EXISTS classification_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS parse_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS extract_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS index_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS duplicate_of_doc_id UUID,
    ADD COLUMN IF NOT EXISTS is_exact_duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_possible_duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_scan_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_ingest_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS doc_id UUID,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE file_ingest_jobs
    ADD COLUMN IF NOT EXISTS job_stage VARCHAR(50) NOT NULL DEFAULT 'parse',
    ADD COLUMN IF NOT EXISTS progress_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_completed INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    ADD COLUMN IF NOT EXISTS dimensions INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    ADD COLUMN IF NOT EXISTS dimensions INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

ALTER TABLE knowledge_units
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

UPDATE file_ingest_jobs
SET job_stage = 'parse'
WHERE job_stage IS NULL OR btrim(job_stage) = '';

UPDATE knowledge_units
SET priority = 0
WHERE priority IS NULL;

UPDATE knowledge_unit_embeddings
SET embedding_provider = COALESCE(embedding_provider, 'fallback'),
    dimensions = COALESCE(dimensions, CASE WHEN vector_json IS NULL THEN 0 ELSE jsonb_array_length(vector_json) END),
    vector_json = COALESCE(vector_json, '[]'::jsonb)
WHERE embedding_provider IS NULL OR dimensions IS NULL OR vector_json IS NULL;

UPDATE chunk_embeddings
SET embedding_provider = COALESCE(embedding_provider, 'fallback'),
    dimensions = COALESCE(dimensions, CASE WHEN vector_json IS NULL THEN 0 ELSE jsonb_array_length(vector_json) END),
    vector_json = COALESCE(vector_json, '[]'::jsonb)
WHERE embedding_provider IS NULL OR dimensions IS NULL OR vector_json IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uniq_source_files_data_source_path ON source_files(data_source_id, file_path);
CREATE INDEX IF NOT EXISTS idx_source_files_data_source_id ON source_files(data_source_id);
CREATE INDEX IF NOT EXISTS idx_source_files_ingest_status ON source_files(ingest_status);

CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_batch_job_id ON file_ingest_jobs(batch_job_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_id ON file_ingest_jobs(source_file_id);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_status ON file_ingest_jobs(status);
CREATE INDEX IF NOT EXISTS idx_file_ingest_jobs_source_file_created_at ON file_ingest_jobs(source_file_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_file_ingest_jobs_batch_file_stage ON file_ingest_jobs(batch_job_id, source_file_id, job_stage);

CREATE INDEX IF NOT EXISTS idx_documents_source_file ON documents(source_file);
CREATE INDEX IF NOT EXISTS idx_documents_doc_domain ON documents(doc_domain);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks(doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_units_doc_id ON knowledge_units(doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_knowledge_unit_id ON knowledge_unit_embeddings(knowledge_unit_id);
CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_chunk_id ON chunk_embeddings(chunk_id);

CREATE OR REPLACE FUNCTION hmrag_update_documents_search_tsv()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('simple', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.source_filename, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.source_file, '')), 'B');
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION hmrag_update_chunks_search_tsv()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('simple', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.content, '')), 'B') ||
        setweight(to_tsvector('simple', COALESCE(NEW.chunk_type, '')), 'C');
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION hmrag_update_ku_search_tsv()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('simple', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.subject, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.indicator, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.normalized_text, '')), 'B') ||
        setweight(to_tsvector('simple', COALESCE(NEW.content, '')), 'B');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_documents_search_tsv ON documents;
CREATE TRIGGER trg_documents_search_tsv
BEFORE INSERT OR UPDATE OF title, source_filename, source_file
ON documents
FOR EACH ROW
EXECUTE FUNCTION hmrag_update_documents_search_tsv();

DROP TRIGGER IF EXISTS trg_chunks_search_tsv ON chunks;
CREATE TRIGGER trg_chunks_search_tsv
BEFORE INSERT OR UPDATE OF title, content, chunk_type
ON chunks
FOR EACH ROW
EXECUTE FUNCTION hmrag_update_chunks_search_tsv();

DROP TRIGGER IF EXISTS trg_ku_search_tsv ON knowledge_units;
CREATE TRIGGER trg_ku_search_tsv
BEFORE INSERT OR UPDATE OF title, subject, indicator, normalized_text, content
ON knowledge_units
FOR EACH ROW
EXECUTE FUNCTION hmrag_update_ku_search_tsv();

