package com.hmrag.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SchemaBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(SchemaBootstrapService.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaBootstrapService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureOptionalSearchSchema() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

            jdbcTemplate.execute("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS search_tsv tsvector");
            jdbcTemplate.execute("ALTER TABLE IF EXISTS chunks ADD COLUMN IF NOT EXISTS search_tsv tsvector");
            jdbcTemplate.execute("ALTER TABLE IF EXISTS knowledge_units ADD COLUMN IF NOT EXISTS search_tsv tsvector");

            jdbcTemplate.execute("""
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
                    """);

            jdbcTemplate.execute("""
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
                    """);

            jdbcTemplate.execute("""
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
                    """);

            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_documents_search_tsv ON documents");
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_documents_search_tsv
                    BEFORE INSERT OR UPDATE OF title, source_filename, source_file
                    ON documents
                    FOR EACH ROW
                    EXECUTE FUNCTION hmrag_update_documents_search_tsv()
                    """);

            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_chunks_search_tsv ON chunks");
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_chunks_search_tsv
                    BEFORE INSERT OR UPDATE OF title, content, chunk_type
                    ON chunks
                    FOR EACH ROW
                    EXECUTE FUNCTION hmrag_update_chunks_search_tsv()
                    """);

            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_ku_search_tsv ON knowledge_units");
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_ku_search_tsv
                    BEFORE INSERT OR UPDATE OF title, subject, indicator, normalized_text, content
                    ON knowledge_units
                    FOR EACH ROW
                    EXECUTE FUNCTION hmrag_update_ku_search_tsv()
                    """);

            log.info("Search schema bootstrap completed");
        } catch (Exception ex) {
            log.error("Search schema bootstrap failed", ex);
            throw ex;
        }
    }
}

