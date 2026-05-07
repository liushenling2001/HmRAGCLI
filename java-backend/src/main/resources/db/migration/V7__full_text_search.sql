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

UPDATE documents
SET search_tsv =
    setweight(to_tsvector('simple', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(source_filename, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(source_file, '')), 'B')
WHERE search_tsv IS NULL;

UPDATE chunks
SET search_tsv =
    setweight(to_tsvector('simple', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(content, '')), 'B') ||
    setweight(to_tsvector('simple', COALESCE(chunk_type, '')), 'C')
WHERE search_tsv IS NULL;

UPDATE knowledge_units
SET search_tsv =
    setweight(to_tsvector('simple', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(subject, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(indicator, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(normalized_text, '')), 'B') ||
    setweight(to_tsvector('simple', COALESCE(content, '')), 'B')
WHERE search_tsv IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_search_tsv ON documents USING GIN (search_tsv);
CREATE INDEX IF NOT EXISTS idx_chunks_search_tsv ON chunks USING GIN (search_tsv);
CREATE INDEX IF NOT EXISTS idx_knowledge_units_search_tsv ON knowledge_units USING GIN (search_tsv);

CREATE INDEX IF NOT EXISTS idx_documents_title_trgm ON documents USING GIN (lower(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_documents_filename_trgm ON documents USING GIN (lower(source_filename) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_title_trgm ON chunks USING GIN (lower(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_content_trgm ON chunks USING GIN (lower(content) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_ku_title_trgm ON knowledge_units USING GIN (lower(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_ku_content_trgm ON knowledge_units USING GIN (lower(content) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_ku_subject_trgm ON knowledge_units USING GIN (lower(subject) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_ku_indicator_trgm ON knowledge_units USING GIN (lower(indicator) gin_trgm_ops);
