-- Manual pgvector migration for offline environments with large existing embedding data.
-- Run this script in psql before starting the new jar.
-- Important: do not wrap this script in an explicit BEGIN/COMMIT transaction.

\echo === Step 1: enable pgvector and add columns ===

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vector vector;

\echo === Step 2: create batch procedures for backfill ===

CREATE OR REPLACE PROCEDURE hmrag_backfill_ku_pgvector(batch_size integer DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT ctid
            FROM knowledge_unit_embeddings
            WHERE embedding_vector IS NULL
              AND vector_json IS NOT NULL
              AND jsonb_typeof(vector_json) = 'array'
              AND jsonb_array_length(vector_json) > 0
            LIMIT batch_size
        )
        UPDATE knowledge_unit_embeddings t
        SET embedding_vector = (t.vector_json::text)::vector
        FROM batch
        WHERE t.ctid = batch.ctid;

        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'knowledge_unit_embeddings migrated rows: %', v_rows;
        EXIT WHEN v_rows = 0;
        COMMIT;
    END LOOP;
END;
$$;

CREATE OR REPLACE PROCEDURE hmrag_backfill_chunk_pgvector(batch_size integer DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT ctid
            FROM chunk_embeddings
            WHERE embedding_vector IS NULL
              AND vector_json IS NOT NULL
              AND jsonb_typeof(vector_json) = 'array'
              AND jsonb_array_length(vector_json) > 0
            LIMIT batch_size
        )
        UPDATE chunk_embeddings t
        SET embedding_vector = (t.vector_json::text)::vector
        FROM batch
        WHERE t.ctid = batch.ctid;

        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE 'chunk_embeddings migrated rows: %', v_rows;
        EXIT WHEN v_rows = 0;
        COMMIT;
    END LOOP;
END;
$$;

\echo === Step 3: run batched backfill ===

CALL hmrag_backfill_ku_pgvector(1000);
CALL hmrag_backfill_chunk_pgvector(1000);

\echo === Step 4: create supporting indexes ===

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_dimensions_updated_at
    ON knowledge_unit_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_dimensions_updated_at
    ON chunk_embeddings(dimensions, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_updated_at
    ON knowledge_unit_embeddings(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_updated_at
    ON chunk_embeddings(updated_at DESC);

ANALYZE knowledge_unit_embeddings;
ANALYZE chunk_embeddings;

\echo === Step 5: verify remaining rows ===

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

\echo === Step 6: optional cleanup of helper procedures ===
-- DROP PROCEDURE IF EXISTS hmrag_backfill_ku_pgvector(integer);
-- DROP PROCEDURE IF EXISTS hmrag_backfill_chunk_pgvector(integer);
