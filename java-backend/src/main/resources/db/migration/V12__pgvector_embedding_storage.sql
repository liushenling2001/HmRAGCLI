CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_dimensions_updated_at
    ON knowledge_unit_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_dimensions_updated_at
    ON chunk_embeddings(dimensions, updated_at DESC);
