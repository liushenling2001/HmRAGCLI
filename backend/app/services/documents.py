from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.models.content import Chunk, KnowledgeUnit
from app.models.data_source import SourceFile
from app.models.document import Document
from app.schemas.enums import DocType
from app.schemas.documents import (
    ChunkOut,
    DocumentDetailOut,
    DocumentKnowledgeUnitOut,
    DocumentOut,
    DuplicateSourceFileOut,
    ParseErrorOut,
    RelatedDocumentOut,
)
from app.services.embedder import cosine_similarity, embed_query
from app.services.ingest import list_documents as list_documents_from_db
from difflib import SequenceMatcher


def list_documents(
    db: Session,
    *,
    page: int = 1,
    page_size: int = 20,
    doc_type: DocType | None = None,
    is_dev_doc: bool | None = None,
    doc_domain: str | None = None,
) -> tuple[list[DocumentOut], int]:
    return list_documents_from_db(
        db,
        page=page,
        page_size=page_size,
        doc_type=doc_type.value if doc_type is not None else None,
        is_dev_doc=is_dev_doc,
        doc_domain=doc_domain,
    )


def get_document_detail(db: Session, doc_id: UUID) -> DocumentDetailOut | None:
    doc = db.get(Document, doc_id)
    if doc is None:
        return None

    chunks = list(
        db.scalars(
            select(Chunk).where(Chunk.doc_id == doc_id).order_by(Chunk.chunk_no.asc())
        ).all()
    )
    units = list(
        db.scalars(
            select(KnowledgeUnit).where(KnowledgeUnit.doc_id == doc_id).order_by(KnowledgeUnit.created_at.asc())
        ).all()
    )

    document = DocumentOut(
        id=doc.id,
        title=doc.title,
        doc_type=doc.doc_type,
        source_file=doc.source_file,
        source_filename=doc.source_filename,
        source_org=doc.source_org,
        author=doc.author,
        publish_date=doc.publish_date.isoformat() if doc.publish_date else None,
        effective_date=doc.effective_date.isoformat() if doc.effective_date else None,
        expiry_date=doc.expiry_date.isoformat() if doc.expiry_date else None,
        status=doc.status,
        language=doc.language,
        is_dev_doc=doc.is_dev_doc,
        doc_domain=doc.doc_domain,
        parse_status=doc.parse_status,
        metadata=doc.metadata_json,
    )

    duplicate_files = list(
        db.scalars(
            select(SourceFile)
            .where(SourceFile.duplicate_of_doc_id == doc_id)
            .order_by(SourceFile.created_at.desc())
        ).all()
    )

    current_preview = ((doc.metadata_json or {}).get("preview_text") or "")[:1500]
    current_preview_vec = embed_query(current_preview) if current_preview else []
    candidate_docs = list(
        db.scalars(
            select(Document)
            .where(Document.id != doc_id)
            .order_by(Document.created_at.desc())
            .limit(50)
        ).all()
    )

    related: list[RelatedDocumentOut] = []
    fallback_related: list[RelatedDocumentOut] = []
    for candidate in candidate_docs:
        if doc.file_hash and candidate.file_hash and candidate.file_hash == doc.file_hash:
            score = 1.0
            relation_type = "exact"
        else:
            title_score = SequenceMatcher(None, (doc.title or ""), (candidate.title or "")).ratio()
            candidate_preview = ((candidate.metadata_json or {}).get("preview_text") or "")[:1500]
            preview_score = SequenceMatcher(None, current_preview, candidate_preview).ratio() if current_preview and candidate_preview else 0.0
            vector_score = 0.0
            if current_preview_vec and candidate_preview:
                vector_score = cosine_similarity(current_preview_vec, embed_query(candidate_preview))
            score = max(title_score * 0.25 + preview_score * 0.15 + vector_score * 0.6, 0.0)
            relation_type = "possible" if score >= 0.2 else "nearest"
        item = RelatedDocumentOut(
            id=candidate.id,
            title=candidate.title,
            doc_type=candidate.doc_type,
            source_file=candidate.source_file,
            source_filename=candidate.source_filename,
            is_dev_doc=candidate.is_dev_doc,
            doc_domain=candidate.doc_domain,
            relation_type=relation_type,
            similarity_score=round(score, 4),
        )
        if score >= 0.2:
            related.append(item)
        elif score > 0.0:
            fallback_related.append(item)
    related.sort(key=lambda item: item.similarity_score, reverse=True)
    fallback_related.sort(key=lambda item: item.similarity_score, reverse=True)

    parse_error_raw = (doc.metadata_json or {}).get("parse_error")
    parse_error = (
        ParseErrorOut(
            type=parse_error_raw.get("type", "unknown"),
            message=parse_error_raw.get("message", ""),
        )
        if isinstance(parse_error_raw, dict)
        else None
    )

    return DocumentDetailOut(
        document=document,
        chunk_count=len(chunks),
        knowledge_unit_count=len(units),
        parse_error=parse_error,
        duplicate_files=[
            DuplicateSourceFileOut(
                id=item.id,
                file_name=item.file_name,
                file_path=item.file_path,
                relative_path=item.relative_path,
                data_source_id=item.data_source_id,
                duplicate_type="exact" if item.is_exact_duplicate else "possible" if item.is_possible_duplicate else "linked",
                is_exact_duplicate=item.is_exact_duplicate,
                is_possible_duplicate=item.is_possible_duplicate,
            )
            for item in duplicate_files
        ],
        similar_documents=(related[:5] if related else fallback_related[:3]),
        chunks=[
            ChunkOut(
                id=chunk.id,
                doc_id=chunk.doc_id,
                chunk_no=chunk.chunk_no,
                chunk_type=chunk.chunk_type,
                title=chunk.title,
                content=chunk.content,
                page_no=chunk.page_no,
                token_count=chunk.token_count,
                metadata=chunk.metadata_json,
            )
            for chunk in chunks
        ],
        knowledge_units=[
            DocumentKnowledgeUnitOut(
                id=unit.id,
                doc_id=unit.doc_id,
                chunk_id=unit.chunk_id,
                unit_type=unit.unit_type,
                title=unit.title,
                content=unit.content,
                normalized_text=unit.normalized_text,
                subject=unit.subject,
                action=unit.action,
                organization=unit.organization,
                region=unit.region,
                time_expr=unit.time_expr,
                indicator=unit.indicator,
                value_num=float(unit.value_num) if unit.value_num is not None else None,
                value_text=unit.value_text,
                unit_name=unit.unit_name,
                status=unit.status,
                priority=unit.priority,
                confidence=float(unit.confidence) if unit.confidence is not None else None,
                source_span=unit.source_span,
                source_page=unit.source_page,
                fields=unit.fields_json,
            )
            for unit in units
        ],
    )


def list_document_chunks(
    db: Session,
    doc_id: UUID,
    *,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[ChunkOut], int] | None:
    doc = db.get(Document, doc_id)
    if doc is None:
        return None
    stmt = select(Chunk).where(Chunk.doc_id == doc_id)
    count_stmt = select(func.count(Chunk.id)).where(Chunk.doc_id == doc_id)
    if keyword:
        stmt = stmt.where(
            or_(
                Chunk.title.ilike(f"%{keyword}%"),
                Chunk.content.ilike(f"%{keyword}%"),
            )
        )
        count_stmt = count_stmt.where(
            or_(
                Chunk.title.ilike(f"%{keyword}%"),
                Chunk.content.ilike(f"%{keyword}%"),
            )
        )
    stmt = stmt.order_by(Chunk.chunk_no.asc()).offset((page - 1) * page_size).limit(page_size)
    rows = list(db.scalars(stmt).all())
    total = db.scalar(count_stmt) or 0
    return (
        [
            ChunkOut(
                id=chunk.id,
                doc_id=chunk.doc_id,
                chunk_no=chunk.chunk_no,
                chunk_type=chunk.chunk_type,
                title=chunk.title,
                content=chunk.content,
                page_no=chunk.page_no,
                token_count=chunk.token_count,
                metadata=chunk.metadata_json,
            )
            for chunk in rows
        ],
        total,
    )


def list_document_knowledge_units(
    db: Session,
    doc_id: UUID,
    *,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[DocumentKnowledgeUnitOut], int] | None:
    doc = db.get(Document, doc_id)
    if doc is None:
        return None
    stmt = select(KnowledgeUnit).where(KnowledgeUnit.doc_id == doc_id)
    count_stmt = select(func.count(KnowledgeUnit.id)).where(KnowledgeUnit.doc_id == doc_id)
    if keyword:
        stmt = stmt.where(
            or_(
                KnowledgeUnit.title.ilike(f"%{keyword}%"),
                KnowledgeUnit.content.ilike(f"%{keyword}%"),
                KnowledgeUnit.normalized_text.ilike(f"%{keyword}%"),
                KnowledgeUnit.subject.ilike(f"%{keyword}%"),
                KnowledgeUnit.indicator.ilike(f"%{keyword}%"),
            )
        )
        count_stmt = count_stmt.where(
            or_(
                KnowledgeUnit.title.ilike(f"%{keyword}%"),
                KnowledgeUnit.content.ilike(f"%{keyword}%"),
                KnowledgeUnit.normalized_text.ilike(f"%{keyword}%"),
                KnowledgeUnit.subject.ilike(f"%{keyword}%"),
                KnowledgeUnit.indicator.ilike(f"%{keyword}%"),
            )
        )
    stmt = stmt.order_by(KnowledgeUnit.created_at.asc()).offset((page - 1) * page_size).limit(page_size)
    rows = list(db.scalars(stmt).all())
    total = db.scalar(count_stmt) or 0
    return (
        [
            DocumentKnowledgeUnitOut(
                id=unit.id,
                doc_id=unit.doc_id,
                chunk_id=unit.chunk_id,
                unit_type=unit.unit_type,
                title=unit.title,
                content=unit.content,
                normalized_text=unit.normalized_text,
                subject=unit.subject,
                action=unit.action,
                organization=unit.organization,
                region=unit.region,
                time_expr=unit.time_expr,
                indicator=unit.indicator,
                value_num=float(unit.value_num) if unit.value_num is not None else None,
                value_text=unit.value_text,
                unit_name=unit.unit_name,
                status=unit.status,
                priority=unit.priority,
                confidence=float(unit.confidence) if unit.confidence is not None else None,
                source_span=unit.source_span,
                source_page=unit.source_page,
                fields=unit.fields_json,
            )
            for unit in rows
        ],
        total,
    )
