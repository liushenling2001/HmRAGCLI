CREATE TABLE IF NOT EXISTS knowledge_unit_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_unit_id UUID NOT NULL UNIQUE REFERENCES knowledge_units(id) ON DELETE CASCADE,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    dimensions INT NOT NULL,
    vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_knowledge_unit_id
    ON knowledge_unit_embeddings(knowledge_unit_id);

CREATE TABLE IF NOT EXISTS chunk_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id UUID NOT NULL UNIQUE REFERENCES chunks(id) ON DELETE CASCADE,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_provider VARCHAR(50) NOT NULL DEFAULT 'fallback',
    dimensions INT NOT NULL,
    vector_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_chunk_id
    ON chunk_embeddings(chunk_id);
