ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(50);

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN embedding_provider SET DEFAULT 'fallback';

UPDATE knowledge_unit_embeddings
SET embedding_provider = 'fallback'
WHERE embedding_provider IS NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN embedding_provider SET NOT NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS dimensions INT;

UPDATE knowledge_unit_embeddings
SET dimensions = COALESCE(dimensions, jsonb_array_length(vector_json))
WHERE dimensions IS NULL AND vector_json IS NOT NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN dimensions SET DEFAULT 0;

UPDATE knowledge_unit_embeddings
SET dimensions = 0
WHERE dimensions IS NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN dimensions SET NOT NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ADD COLUMN IF NOT EXISTS vector_json JSONB;

UPDATE knowledge_unit_embeddings
SET vector_json = '[]'::jsonb
WHERE vector_json IS NULL;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN vector_json SET DEFAULT '[]'::jsonb;

ALTER TABLE IF EXISTS knowledge_unit_embeddings
    ALTER COLUMN vector_json SET NOT NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(50);

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN embedding_provider SET DEFAULT 'fallback';

UPDATE chunk_embeddings
SET embedding_provider = 'fallback'
WHERE embedding_provider IS NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN embedding_provider SET NOT NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS dimensions INT;

UPDATE chunk_embeddings
SET dimensions = COALESCE(dimensions, jsonb_array_length(vector_json))
WHERE dimensions IS NULL AND vector_json IS NOT NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN dimensions SET DEFAULT 0;

UPDATE chunk_embeddings
SET dimensions = 0
WHERE dimensions IS NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN dimensions SET NOT NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ADD COLUMN IF NOT EXISTS vector_json JSONB;

UPDATE chunk_embeddings
SET vector_json = '[]'::jsonb
WHERE vector_json IS NULL;

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN vector_json SET DEFAULT '[]'::jsonb;

ALTER TABLE IF EXISTS chunk_embeddings
    ALTER COLUMN vector_json SET NOT NULL;
