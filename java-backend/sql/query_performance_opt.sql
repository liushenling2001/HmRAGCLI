-- HmRAG query performance optimization script
-- Goal: improve full-text and fallback keyword lookup latency for local/offline deployment.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Full-text indexes
CREATE INDEX IF NOT EXISTS idx_documents_search_tsv_gin
    ON documents USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS idx_chunks_search_tsv_gin
    ON chunks USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS idx_knowledge_units_search_tsv_gin
    ON knowledge_units USING gin (search_tsv);

-- Join/order support
CREATE INDEX IF NOT EXISTS idx_source_files_doc_id_created_at
    ON source_files(doc_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_updated_at
    ON knowledge_unit_embeddings(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_updated_at
    ON chunk_embeddings(updated_at DESC);

-- Fallback LIKE path acceleration
CREATE INDEX IF NOT EXISTS idx_documents_title_trgm
    ON documents USING gin (lower(title) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_documents_source_filename_trgm
    ON documents USING gin (lower(source_filename) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_source_files_relative_path_trgm
    ON source_files USING gin (lower(relative_path) gin_trgm_ops);

-- Keep planner stats fresh
ANALYZE documents;
ANALYZE chunks;
ANALYZE knowledge_units;
ANALYZE source_files;
ANALYZE knowledge_unit_embeddings;
ANALYZE chunk_embeddings;
