-- Hard fix for offline startup freeze at Flyway V7
-- Goal: avoid blocking full-table UPDATE/GIN build during app startup
-- Effect:
--   1) keep required full-text schema objects (columns/functions/triggers)
--   2) skip heavy backfill + heavy trigram/GIN index creation at startup
--   3) mark V7 as executed in flyway_schema_history so boot continues
--
-- Safe to run multiple times.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE IF EXISTS documents
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

ALTER TABLE IF EXISTS chunks
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

ALTER TABLE IF EXISTS knowledge_units
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

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

-- Ensure flyway history table exists
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL PRIMARY KEY,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT now(),
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL
);

-- Mark V7 as done (lightweight path)
INSERT INTO flyway_schema_history (
    installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
)
SELECT
    COALESCE((SELECT MAX(installed_rank) FROM flyway_schema_history), 0) + 1,
    '7',
    'full text search (offline fast path)',
    'SQL',
    'V7__full_text_search.sql',
    NULL,
    current_user,
    0,
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM flyway_schema_history WHERE version = '7' AND success = TRUE
);

COMMIT;

