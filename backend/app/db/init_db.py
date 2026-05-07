import logging

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.db.base import Base
from app.db.session import engine
from app.models import (  # noqa: F401
    BatchIngestJob,
    Chunk,
    ChunkEmbedding,
    DataSource,
    Document,
    IngestJob,
    KnowledgeUnit,
    KnowledgeUnitEmbedding,
    ScanJob,
    SourceFile,
)

logger = logging.getLogger(__name__)


def init_db() -> None:
    try:
        with engine.begin() as conn:
            conn.execute(text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
            conn.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
        Base.metadata.create_all(bind=engine)
        with engine.begin() as conn:
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS processing_stage VARCHAR(50) NOT NULL DEFAULT 'discovered'"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS classification_status VARCHAR(50) NOT NULL DEFAULT 'pending'"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS parse_status VARCHAR(50) NOT NULL DEFAULT 'pending'"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS extract_status VARCHAR(50) NOT NULL DEFAULT 'pending'"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS index_status VARCHAR(50) NOT NULL DEFAULT 'pending'"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS duplicate_of_doc_id UUID"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS is_exact_duplicate BOOLEAN NOT NULL DEFAULT FALSE"))
            conn.execute(text("ALTER TABLE IF EXISTS source_files ADD COLUMN IF NOT EXISTS is_possible_duplicate BOOLEAN NOT NULL DEFAULT FALSE"))
            conn.execute(text("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS canonical_doc_id UUID"))
            conn.execute(text("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS is_canonical BOOLEAN NOT NULL DEFAULT TRUE"))
            conn.execute(text("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS duplicate_count INTEGER NOT NULL DEFAULT 0"))
            conn.execute(text("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS is_dev_doc BOOLEAN NOT NULL DEFAULT FALSE"))
            conn.execute(text("ALTER TABLE IF EXISTS documents ADD COLUMN IF NOT EXISTS doc_domain VARCHAR(50) NOT NULL DEFAULT 'business'"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS source_file_id UUID"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS data_source_id UUID"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS stage VARCHAR(50)"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS progress_total INTEGER NOT NULL DEFAULT 0"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS progress_completed INTEGER NOT NULL DEFAULT 0"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS payload_json JSONB NOT NULL DEFAULT '{}'::jsonb"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP"))
            conn.execute(text("ALTER TABLE IF EXISTS ingest_jobs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW()"))
            conn.execute(text("ALTER TABLE IF EXISTS knowledge_unit_embeddings ADD COLUMN IF NOT EXISTS vector vector(768)"))
            conn.execute(text("ALTER TABLE IF EXISTS chunk_embeddings ADD COLUMN IF NOT EXISTS vector vector(768)"))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_ingest_jobs_status_created "
                "ON ingest_jobs (status, created_at)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_ingest_jobs_source_file_stage "
                "ON ingest_jobs (source_file_id, stage)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_documents_title_trgm "
                "ON documents USING gin (title gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_documents_source_filename_trgm "
                "ON documents USING gin (source_filename gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_documents_title_fts "
                "ON documents USING gin (to_tsvector('simple', coalesce(title, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_documents_source_filename_fts "
                "ON documents USING gin (to_tsvector('simple', coalesce(source_filename, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_title_trgm "
                "ON chunks USING gin (title gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_content_trgm "
                "ON chunks USING gin (content gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_search_title_trgm "
                "ON chunks USING gin (((metadata_json->>'search_title')) gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_heading_path_trgm "
                "ON chunks USING gin (((metadata_json->>'heading_path')) gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_title_fts "
                "ON chunks USING gin (to_tsvector('simple', coalesce(title, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_content_fts "
                "ON chunks USING gin (to_tsvector('simple', coalesce(content, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_search_title_fts "
                "ON chunks USING gin (to_tsvector('simple', coalesce(metadata_json->>'search_title', '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunks_heading_path_fts "
                "ON chunks USING gin (to_tsvector('simple', coalesce(metadata_json->>'heading_path', '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_title_trgm "
                "ON knowledge_units USING gin (title gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_subject_trgm "
                "ON knowledge_units USING gin (subject gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_indicator_trgm "
                "ON knowledge_units USING gin (indicator gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_normalized_text_trgm "
                "ON knowledge_units USING gin (normalized_text gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_content_trgm "
                "ON knowledge_units USING gin (content gin_trgm_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_title_fts "
                "ON knowledge_units USING gin (to_tsvector('simple', coalesce(title, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_subject_fts "
                "ON knowledge_units USING gin (to_tsvector('simple', coalesce(subject, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_indicator_fts "
                "ON knowledge_units USING gin (to_tsvector('simple', coalesce(indicator, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_normalized_text_fts "
                "ON knowledge_units USING gin (to_tsvector('simple', coalesce(normalized_text, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_units_content_fts "
                "ON knowledge_units USING gin (to_tsvector('simple', coalesce(content, '')))"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_unit_embeddings_vector_hnsw "
                "ON knowledge_unit_embeddings USING hnsw (vector vector_cosine_ops)"
            ))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_vector_hnsw "
                "ON chunk_embeddings USING hnsw (vector vector_cosine_ops)"
            ))
    except SQLAlchemyError as exc:
        logger.exception("Database initialization failed: %s", exc)
        raise
