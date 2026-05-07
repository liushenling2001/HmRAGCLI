-- Final pgvector index build script for offline deployment.
-- Assumption: embedding_vector has already been backfilled and vector dimensions are uniform.

\echo === Step 1: verify pgvector extension ===
CREATE EXTENSION IF NOT EXISTS vector;

\echo === Step 2: verify dimensions ===
SELECT dimensions, count(*) AS row_count
FROM knowledge_unit_embeddings
WHERE embedding_vector IS NOT NULL
GROUP BY dimensions
ORDER BY row_count DESC;

SELECT dimensions, count(*) AS row_count
FROM chunk_embeddings
WHERE embedding_vector IS NOT NULL
GROUP BY dimensions
ORDER BY row_count DESC;

\echo === Step 3: verify pending backfill rows ===
SELECT count(*) AS ku_pending
FROM knowledge_unit_embeddings
WHERE embedding_vector IS NULL
  AND vector_json IS NOT NULL
  AND jsonb_typeof(vector_json) = 'array'
  AND jsonb_array_length(vector_json) > 0;

SELECT count(*) AS chunk_pending
FROM chunk_embeddings
WHERE embedding_vector IS NULL
  AND vector_json IS NOT NULL
  AND jsonb_typeof(vector_json) = 'array'
  AND jsonb_array_length(vector_json) > 0;

\echo === Step 4: build pgvector HNSW indexes ===
CREATE INDEX IF NOT EXISTS idx_ku_embedding_vector_hnsw
    ON knowledge_unit_embeddings
    USING hnsw (embedding_vector vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_vector_hnsw
    ON chunk_embeddings
    USING hnsw (embedding_vector vector_cosine_ops);

\echo === Step 5: build supporting indexes ===
CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_dimensions_updated_at
    ON knowledge_unit_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_dimensions_updated_at
    ON chunk_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_updated_at
    ON knowledge_unit_embeddings(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_updated_at
    ON chunk_embeddings(updated_at DESC);

\echo === Step 6: analyze tables ===
ANALYZE knowledge_unit_embeddings;
ANALYZE chunk_embeddings;

\echo === Step 7: list indexes ===
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename IN ('knowledge_unit_embeddings', 'chunk_embeddings')
ORDER BY tablename, indexname;
