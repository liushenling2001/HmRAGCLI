from __future__ import annotations

import re
from typing import Any

from sqlalchemy import Select, Text, cast, func, or_, select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.content import Chunk, KnowledgeUnit
from app.models.document import Document
from app.models.embedding import ChunkEmbedding, KnowledgeUnitEmbedding
from app.schemas.knowledge_units import KnowledgeUnitOut, KnowledgeUnitQuery
from app.schemas.qa import CitationOut
from app.schemas.search import UnifiedSearchItemOut, UnifiedSearchQuery
from app.services.embedder import cosine_similarity, embed_query


DEV_TITLE_HINTS = [
    "schema",
    "draft",
    "ddl",
    "pydantic",
    "fastapi",
    "api",
    "ingestion-design",
    "knowledge-base-design",
]

DEV_TEXT_HINTS = [
    "```",
    "class ",
    "def ",
    "select ",
    "create table",
    "post /api",
    "get /api",
    "baseModel",
    "pydantic",
    "fastapi",
    "sqlalchemy",
]

BUSINESS_DOC_BONUS = {
    "rule": 0.08,
    "notice": 0.06,
    "report": 0.06,
    "minutes": 0.04,
    "excel": 0.03,
    "speech": 0.0,
    "unknown": -0.03,
}

SUMMARY_HINTS = ["摘要", "概述", "概要", "summary", "abstract"]
CAPTION_HINTS = ["图", "表", "figure", "table", "sheet"]


def _is_dev_doc_clause() -> Any:
    clauses = []
    for token in DEV_TITLE_HINTS:
        clauses.append(func.lower(func.coalesce(Document.title, "")).like(f"%{token}%"))
        clauses.append(func.lower(func.coalesce(Document.source_filename, "")).like(f"%{token}%"))
    return or_(Document.is_dev_doc.is_(True), *clauses)


def _text_noise_penalty(doc: Document | None, title: str | None, content: str | None) -> float:
    penalty = 0.0
    title_l = (title or "").lower()
    content_l = (content or "")[:1200].lower()
    source_name = ((doc.source_filename if doc is not None else "") or "").lower()

    if any(token in title_l for token in DEV_TITLE_HINTS):
        penalty += settings.search_doc_dev_title_penalty
    if any(token in source_name for token in DEV_TITLE_HINTS):
        penalty += settings.search_doc_dev_title_penalty

    dev_hits = sum(1 for token in DEV_TEXT_HINTS if token in content_l)
    penalty += min(dev_hits * settings.search_doc_dev_text_penalty, 0.24)

    if content_l.count("```") >= 2:
        penalty += settings.search_doc_code_block_penalty
    if content_l.count("|") >= 8:
        penalty += settings.search_doc_table_penalty
    if "http " in content_l or "http://" in content_l or "https://" in content_l:
        penalty += settings.search_doc_link_penalty

    if doc is not None:
        penalty -= BUSINESS_DOC_BONUS.get(doc.doc_type, 0.0)

    return max(settings.search_doc_min_noise_penalty, min(penalty, settings.search_doc_max_noise_penalty))


def _query_terms(query: str) -> list[str]:
    ascii_terms = [term.lower() for term in re.findall(r"[A-Za-z][A-Za-z0-9_-]{1,}", query or "")]
    if ascii_terms:
        return ascii_terms
    q = (query or "").strip().lower()
    return [q] if len(q) >= 2 else []


def _is_exactish_query(query: str | None) -> bool:
    q = (query or "").strip()
    if not q:
        return False
    if any(token in q for token in ['"', "'", "“", "”", "《", "》"]):
        return True
    if len(q) >= 8:
        return True
    return False


def _metadata_text(doc: Document | None) -> str:
    if doc is None:
        return ""
    metadata = doc.metadata_json or {}
    preview = metadata.get("preview_text") or ""
    return str(preview).lower()


def _chunk_search_blob(chunk: Chunk) -> str:
    metadata = chunk.metadata_json or {}
    parts = [
        chunk.title or "",
        str(metadata.get("search_title") or ""),
        str(metadata.get("heading_path") or ""),
        " ".join(metadata.get("headers") or []),
        str(metadata.get("sheet_name") or ""),
        chunk.content or "",
    ]
    return "\n".join(part for part in parts if part).lower()


def _unit_search_blob(unit: KnowledgeUnit, doc: Document | None) -> str:
    fields = unit.fields_json or {}
    parts = [
        doc.title if doc is not None else "",
        doc.source_filename if doc is not None else "",
        unit.title or "",
        unit.subject or "",
        unit.indicator or "",
        str(fields.get("search_title") or ""),
        str(fields.get("heading_path") or ""),
        " ".join(fields.get("headers") or []),
        str(fields.get("sheet_name") or ""),
        unit.normalized_text or "",
        unit.content or "",
    ]
    return "\n".join(part for part in parts if part).lower()


def _query_overlap_adjustment(
    query: str | None,
    doc: Document | None,
    title: str | None,
    content: str | None,
) -> float:
    if not query:
        return 0.0
    haystack = " ".join(
        part for part in [
            (title or "").lower(),
            ((doc.source_filename if doc is not None else "") or "").lower(),
            (content or "")[:1200].lower(),
        ] if part
    )
    terms = _query_terms(query)
    if not terms:
        return 0.0
    hits = sum(1 for term in terms if term in haystack)
    if hits == 0:
        return (
            -settings.search_query_ascii_miss_penalty
            if any(re.search(r"[a-z]", term) for term in terms)
            else -settings.search_query_non_ascii_miss_penalty
        )
    return min(hits * settings.search_query_hit_bonus, settings.search_query_max_hit_bonus)


def _phrase_match_adjustment(
    query: str | None,
    doc: Document | None,
    title: str | None,
    content: str | None,
) -> float:
    q = (query or "").strip().lower()
    if not q:
        return 0.0
    title_l = (title or "").lower()
    content_l = (content or "")[:4000].lower()
    file_l = ((doc.source_filename if doc is not None else "") or "").lower()
    preview_l = _metadata_text(doc)
    bonus = 0.0

    if q and q in content_l:
        bonus += settings.search_exact_phrase_boost
    if q and q in title_l:
        bonus += settings.search_title_hit_boost
    if q and q in file_l:
        bonus += settings.search_filename_hit_boost
    if q and q in preview_l:
        bonus += settings.search_summary_hit_boost

    if any(hint in title_l for hint in SUMMARY_HINTS):
        if q in title_l or q in content_l:
            bonus += settings.search_summary_hit_boost
    if any(hint in title_l for hint in CAPTION_HINTS):
        if q in title_l:
            bonus += settings.search_caption_hit_boost

    return bonus


def _exact_content_boost(query: str | None, content: str | None) -> float:
    q = (query or "").strip().lower()
    text = (content or "").lower()
    if not q or not text:
        return 0.0
    if q == text.strip():
        return settings.search_exact_phrase_boost * 1.5
    if q in text:
        return settings.search_exact_phrase_boost * 0.8
    return 0.0


def _infer_match_type(
    query: str | None,
    doc: Document | None,
    title: str | None,
    content: str | None,
) -> str:
    q = (query or "").strip().lower()
    if not q:
        return "content"
    title_l = (title or "").lower()
    file_l = ((doc.source_filename if doc is not None else "") or "").lower()
    preview_l = _metadata_text(doc)
    content_l = (content or "")[:4000].lower()

    if q in title_l and any(hint in title_l for hint in CAPTION_HINTS):
        return "caption"
    if q in title_l:
        return "title"
    if q in file_l:
        return "filename"
    if q in preview_l:
        return "summary"
    if q in content_l:
        return "content"
    return "semantic"


def _build_snippet(query: str | None, content: str | None, *, radius: int = 90) -> str | None:
    text = (content or "").strip()
    if not text:
        return None
    q = (query or "").strip()
    if not q:
        return text[: radius * 2] + ("..." if len(text) > radius * 2 else "")

    text_l = text.lower()
    q_l = q.lower()
    idx = text_l.find(q_l)
    if idx >= 0:
        start = max(0, idx - radius)
        end = min(len(text), idx + len(q) + radius)
        prefix = "..." if start > 0 else ""
        suffix = "..." if end < len(text) else ""
        return f"{prefix}{text[start:end]}{suffix}"

    terms = [term for term in _query_terms(q) if term]
    for term in terms:
        idx = text_l.find(term.lower())
        if idx >= 0:
            start = max(0, idx - radius)
            end = min(len(text), idx + len(term) + radius)
            prefix = "..." if start > 0 else ""
            suffix = "..." if end < len(text) else ""
            return f"{prefix}{text[start:end]}{suffix}"

    return text[: radius * 2] + ("..." if len(text) > radius * 2 else "")


def _final_score(
    base_score: float,
    doc: Document | None,
    title: str | None,
    content: str | None,
    query: str | None = None,
) -> float:
    return (
        base_score
        - _text_noise_penalty(doc, title, content)
        + _query_overlap_adjustment(query, doc, title, content)
        + _phrase_match_adjustment(query, doc, title, content)
        + _exact_content_boost(query, content)
    )


def _keyword_score_expr_for_units(keyword: str):
    keyword_text = cast(keyword, Text)
    tsquery = func.websearch_to_tsquery("simple", keyword)
    return (
        func.greatest(
            func.similarity(cast(func.coalesce(Document.title, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(Document.source_filename, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(KnowledgeUnit.title, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(KnowledgeUnit.subject, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(KnowledgeUnit.indicator, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(KnowledgeUnit.normalized_text, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(KnowledgeUnit.content, ""), Text), keyword_text),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Document.title, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Document.source_filename, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(KnowledgeUnit.title, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(KnowledgeUnit.subject, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(KnowledgeUnit.indicator, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(KnowledgeUnit.normalized_text, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(KnowledgeUnit.content, "")), tsquery),
        )
    ).label("score")


def _keyword_score_expr_for_chunks(keyword: str):
    keyword_text = cast(keyword, Text)
    tsquery = func.websearch_to_tsquery("simple", keyword)
    return (
        func.greatest(
            func.similarity(cast(func.coalesce(Document.title, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(Document.source_filename, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(Chunk.title, ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(cast(Chunk.metadata_json["search_title"], Text), ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(cast(Chunk.metadata_json["heading_path"], Text), ""), Text), keyword_text),
            func.similarity(cast(func.coalesce(Chunk.content, ""), Text), keyword_text),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Document.title, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Document.source_filename, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Chunk.title, "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(cast(Chunk.metadata_json["search_title"], Text), "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(cast(Chunk.metadata_json["heading_path"], Text), "")), tsquery),
            func.ts_rank_cd(func.to_tsvector("simple", func.coalesce(Chunk.content, "")), tsquery),
        )
    ).label("score")


def _db_vector_scores_for_units(
    db: Session,
    unit_ids: list[str],
    query_vec: list[float],
) -> dict[str, float]:
    if not unit_ids or len(query_vec) != 768:
        return {}
    distance = KnowledgeUnitEmbedding.vector.cosine_distance(query_vec).label("distance")
    rows = db.execute(
        select(KnowledgeUnitEmbedding.knowledge_unit_id, distance).where(
            KnowledgeUnitEmbedding.knowledge_unit_id.in_(unit_ids),
            KnowledgeUnitEmbedding.vector.is_not(None),
        )
    ).all()
    return {str(unit_id): 1.0 - float(dist) for unit_id, dist in rows if dist is not None}


def _db_vector_scores_for_chunks(
    db: Session,
    chunk_ids: list[str],
    query_vec: list[float],
) -> dict[str, float]:
    if not chunk_ids or len(query_vec) != 768:
        return {}
    distance = ChunkEmbedding.vector.cosine_distance(query_vec).label("distance")
    rows = db.execute(
        select(ChunkEmbedding.chunk_id, distance).where(
            ChunkEmbedding.chunk_id.in_(chunk_ids),
            ChunkEmbedding.vector.is_not(None),
        )
    ).all()
    return {str(chunk_id): 1.0 - float(dist) for chunk_id, dist in rows if dist is not None}


def _apply_filters(stmt: Select, query: KnowledgeUnitQuery) -> Select:
    if query.unit_type:
        stmt = stmt.where(KnowledgeUnit.unit_type == query.unit_type.value)
    if query.doc_type:
        stmt = stmt.where(Document.doc_type == query.doc_type.value)
    if query.organization:
        stmt = stmt.where(KnowledgeUnit.organization == query.organization)
    if query.region:
        stmt = stmt.where(KnowledgeUnit.region == query.region)
    if query.indicator:
        stmt = stmt.where(KnowledgeUnit.indicator == query.indicator)
    if query.status:
        stmt = stmt.where(KnowledgeUnit.status == query.status)
    if getattr(query, "exclude_dev_docs", False):
        stmt = stmt.where(~_is_dev_doc_clause())
    return stmt


def _apply_chunk_filters(stmt: Select, query: UnifiedSearchQuery) -> Select:
    if query.doc_type:
        stmt = stmt.where(Document.doc_type == query.doc_type.value)
    if query.exclude_dev_docs:
        stmt = stmt.where(~_is_dev_doc_clause())
    return stmt


def _vector_units_for_query(
    db: Session,
    query: KnowledgeUnitQuery,
    query_vec: list[float],
    limit: int,
) -> list[tuple[KnowledgeUnit, Document, float]]:
    if len(query_vec) != 768:
        return []
    distance = KnowledgeUnitEmbedding.vector.cosine_distance(query_vec).label("distance")
    stmt = (
        select(KnowledgeUnit, Document, distance)
        .join(KnowledgeUnitEmbedding, KnowledgeUnitEmbedding.knowledge_unit_id == KnowledgeUnit.id)
        .join(Document, Document.id == KnowledgeUnit.doc_id)
    )
    stmt = _apply_filters(stmt, query)
    stmt = stmt.where(KnowledgeUnitEmbedding.vector.is_not(None)).order_by(distance.asc()).limit(limit)
    rows = db.execute(stmt).all()
    return [(unit, doc, 1.0 - float(dist)) for unit, doc, dist in rows if dist is not None]


def _vector_chunks_for_query(
    db: Session,
    query: UnifiedSearchQuery,
    query_vec: list[float],
    limit: int,
) -> list[tuple[Chunk, Document, float]]:
    if len(query_vec) != 768:
        return []
    distance = ChunkEmbedding.vector.cosine_distance(query_vec).label("distance")
    stmt = (
        select(Chunk, Document, distance)
        .join(ChunkEmbedding, ChunkEmbedding.chunk_id == Chunk.id)
        .join(Document, Document.id == Chunk.doc_id)
    )
    stmt = _apply_chunk_filters(stmt, query)
    stmt = stmt.where(ChunkEmbedding.vector.is_not(None)).order_by(distance.asc()).limit(limit)
    rows = db.execute(stmt).all()
    return [(chunk, doc, 1.0 - float(dist)) for chunk, doc, dist in rows if dist is not None]


def _keyword_statement(query: KnowledgeUnitQuery) -> Select:
    stmt = select(KnowledgeUnit, Document).join(Document, Document.id == KnowledgeUnit.doc_id)
    stmt = _apply_filters(stmt, query)
    if query.keyword:
        keyword = query.keyword.strip()
        similarity_score = _keyword_score_expr_for_units(keyword)
        tsquery = func.websearch_to_tsquery("simple", keyword)
        stmt = stmt.add_columns(similarity_score).where(
            or_(
                Document.title.ilike(f"%{keyword}%"),
                Document.source_filename.ilike(f"%{keyword}%"),
                KnowledgeUnit.title.ilike(f"%{keyword}%"),
                KnowledgeUnit.subject.ilike(f"%{keyword}%"),
                KnowledgeUnit.indicator.ilike(f"%{keyword}%"),
                KnowledgeUnit.normalized_text.ilike(f"%{keyword}%"),
                KnowledgeUnit.content.ilike(f"%{keyword}%"),
                func.to_tsvector("simple", func.coalesce(Document.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Document.source_filename, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.subject, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.indicator, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.normalized_text, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.content, "")).op("@@")(tsquery),
                similarity_score > 0.05,
            )
        )
        stmt = stmt.order_by(similarity_score.desc(), KnowledgeUnit.created_at.desc())
    else:
        stmt = stmt.order_by(KnowledgeUnit.created_at.desc())
    return stmt


def search_knowledge_units(db: Session, query: KnowledgeUnitQuery) -> tuple[list[KnowledgeUnitOut], int]:
    rows: list[Any] = []
    if query.keyword:
        stmt = _keyword_statement(query)
        keyword_rows = db.execute(
            stmt.offset((query.page - 1) * query.page_size).limit(max(query.page_size * 3, 20))
        ).all()
        vector_rows = []
        if not _is_exactish_query(query.keyword):
            query_vec = embed_query(query.keyword)
            vector_rows = _vector_units_for_query(db, query, query_vec, max(query.page_size * 3, 20))
        merged: dict[str, tuple[Any, float]] = {}
        for row in keyword_rows:
            unit = row[0]
            keyword_score = float(row[2] if len(row) > 2 and row[2] is not None else 0.0)
            doc = row[1]
            merged[str(unit.id)] = (
                row,
                _final_score(
                    keyword_score * settings.search_keyword_weight,
                    doc,
                    unit.title,
                    _unit_search_blob(unit, doc),
                    query.keyword,
                ),
            )
        for unit, doc, vector_score in vector_rows:
            key = str(unit.id)
            base_row = (unit, doc, None)
            existing = merged.get(key)
            if existing is None:
                merged[key] = (
                    base_row,
                    _final_score(
                        vector_score * settings.search_unit_vector_weight,
                        doc,
                        unit.title,
                        _unit_search_blob(unit, doc),
                        query.keyword,
                    ),
                )
            else:
                merged[key] = (
                    existing[0],
                    _final_score(
                        existing[1] + vector_score * settings.search_unit_vector_boost,
                        doc,
                        unit.title,
                        _unit_search_blob(unit, doc),
                        query.keyword,
                    ),
                )
        rows = [row for row, _ in sorted(merged.values(), key=lambda item: item[1], reverse=True)[: query.page_size]]
    else:
        stmt = _keyword_statement(query)
        rows = db.execute(
            stmt.offset((query.page - 1) * query.page_size).limit(max(query.page_size, 20))
        ).all()[: query.page_size]

    count_stmt = _apply_filters(
        select(func.count(KnowledgeUnit.id)).join(Document, Document.id == KnowledgeUnit.doc_id),
        query,
    )
    if query.keyword:
        keyword = query.keyword.strip()
        keyword_text = cast(keyword, Text)
        tsquery = func.websearch_to_tsquery("simple", keyword)
        count_stmt = count_stmt.where(
            or_(
                KnowledgeUnit.title.ilike(f"%{keyword}%"),
                KnowledgeUnit.subject.ilike(f"%{keyword}%"),
                KnowledgeUnit.indicator.ilike(f"%{keyword}%"),
                KnowledgeUnit.normalized_text.ilike(f"%{keyword}%"),
                KnowledgeUnit.content.ilike(f"%{keyword}%"),
                Document.title.ilike(f"%{keyword}%"),
                Document.source_filename.ilike(f"%{keyword}%"),
                func.to_tsvector("simple", func.coalesce(Document.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Document.source_filename, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.subject, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.indicator, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.normalized_text, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.content, "")).op("@@")(tsquery),
                func.greatest(
                    func.similarity(cast(func.coalesce(Document.title, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(Document.source_filename, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(KnowledgeUnit.title, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(KnowledgeUnit.subject, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(KnowledgeUnit.indicator, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(KnowledgeUnit.normalized_text, ""), Text), keyword_text),
                    func.similarity(cast(func.coalesce(KnowledgeUnit.content, ""), Text), keyword_text),
                )
                > 0.05,
            )
        )
    total = db.scalar(count_stmt) or 0

    items: list[KnowledgeUnitOut] = []
    for row in rows:
        unit = row[0]
        items.append(
            KnowledgeUnitOut(
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
                person=unit.person,
                region=unit.region,
                time_expr=unit.time_expr,
                event_date=unit.event_date.isoformat() if unit.event_date else None,
                indicator=unit.indicator,
                value_num=float(unit.value_num) if unit.value_num is not None else None,
                value_text=unit.value_text,
                unit_name=unit.unit_name,
                effective_date=unit.effective_date.isoformat() if unit.effective_date else None,
                expiry_date=unit.expiry_date.isoformat() if unit.expiry_date else None,
                status=unit.status,
                priority=unit.priority,
                confidence=float(unit.confidence) if unit.confidence is not None else None,
                source_span=unit.source_span,
                source_page=unit.source_page,
                fields=unit.fields_json,
                tags=[],
            )
        )
    return items, total


def _search_units_for_qa(
    db: Session,
    query_text: str,
    top_k: int = 5,
    *,
    exclude_dev_docs: bool = False,
) -> list[tuple[KnowledgeUnit, Document, float]]:
    query = KnowledgeUnitQuery(keyword=query_text, exclude_dev_docs=exclude_dev_docs, page=1, page_size=top_k)
    keyword = query_text.strip()
    score = _keyword_score_expr_for_units(keyword)
    tsquery = func.websearch_to_tsquery("simple", keyword)
    stmt = (
        _apply_filters(select(KnowledgeUnit, Document, score).join(Document, Document.id == KnowledgeUnit.doc_id), query)
        .where(
            or_(
                Document.title.ilike(f"%{keyword}%"),
                Document.source_filename.ilike(f"%{keyword}%"),
                KnowledgeUnit.title.ilike(f"%{keyword}%"),
                KnowledgeUnit.subject.ilike(f"%{keyword}%"),
                KnowledgeUnit.indicator.ilike(f"%{keyword}%"),
                KnowledgeUnit.normalized_text.ilike(f"%{keyword}%"),
                KnowledgeUnit.content.ilike(f"%{keyword}%"),
                func.to_tsvector("simple", func.coalesce(Document.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Document.source_filename, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.subject, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.indicator, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.normalized_text, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(KnowledgeUnit.content, "")).op("@@")(tsquery),
                score > 0.05,
            )
        )
        .order_by(score.desc(), KnowledgeUnit.created_at.desc())
        .limit(max(top_k * 3, 10))
    )
    keyword_rows = [(unit, doc, float(score_value or 0.0)) for unit, doc, score_value in db.execute(stmt).all()]
    vector_rows = []
    if not _is_exactish_query(query_text):
        query_vec = embed_query(query_text)
        vector_rows = _vector_units_for_query(db, query, query_vec, max(top_k * 3, 10))
    merged: dict[str, tuple[KnowledgeUnit, Document, float]] = {}
    for unit, doc, score_value in keyword_rows:
        merged[str(unit.id)] = (
            unit,
            doc,
            _final_score(
                score_value * settings.search_keyword_weight,
                doc,
                unit.title,
                _unit_search_blob(unit, doc),
                query_text,
            ),
        )
    for unit, doc, vector_score in vector_rows:
        key = str(unit.id)
        if key in merged:
            prev = merged[key]
            merged[key] = (
                prev[0],
                prev[1],
                _final_score(
                    prev[2] + vector_score * settings.search_unit_vector_boost,
                    doc,
                    unit.title,
                    _unit_search_blob(unit, doc),
                    query_text,
                ),
            )
        else:
            merged[key] = (
                unit,
                doc,
                _final_score(
                    vector_score * settings.search_unit_vector_weight,
                    doc,
                    unit.title,
                    _unit_search_blob(unit, doc),
                    query_text,
                ),
            )
    rows = sorted(merged.values(), key=lambda item: item[2], reverse=True)
    return rows[:top_k]


def _search_chunks_for_qa(
    db: Session,
    query_text: str,
    top_k: int = 5,
    *,
    exclude_dev_docs: bool = False,
) -> list[tuple[Chunk, Document, float]]:
    keyword = query_text.strip()
    score = _keyword_score_expr_for_chunks(keyword)
    tsquery = func.websearch_to_tsquery("simple", keyword)
    stmt = (
        select(Chunk, Document, score)
        .join(Document, Document.id == Chunk.doc_id)
        .where(
            or_(
                Document.title.ilike(f"%{keyword}%"),
                Document.source_filename.ilike(f"%{keyword}%"),
                Chunk.title.ilike(f"%{keyword}%"),
                cast(Chunk.metadata_json["search_title"], Text).ilike(f"%{keyword}%"),
                cast(Chunk.metadata_json["heading_path"], Text).ilike(f"%{keyword}%"),
                Chunk.content.ilike(f"%{keyword}%"),
                func.to_tsvector("simple", func.coalesce(Document.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Document.source_filename, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Chunk.title, "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(cast(Chunk.metadata_json["search_title"], Text), "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(cast(Chunk.metadata_json["heading_path"], Text), "")).op("@@")(tsquery),
                func.to_tsvector("simple", func.coalesce(Chunk.content, "")).op("@@")(tsquery),
                score > 0.05,
            )
        )
    )
    stmt = _apply_chunk_filters(stmt, UnifiedSearchQuery(keyword=query_text, exclude_dev_docs=exclude_dev_docs, page=1, page_size=top_k))
    stmt = (
        stmt
        .order_by(score.desc(), Chunk.created_at.desc())
        .limit(max(top_k * 3, 10))
    )
    keyword_rows = [(chunk, doc, float(score_value or 0.0)) for chunk, doc, score_value in db.execute(stmt).all()]
    vector_rows = []
    if not _is_exactish_query(query_text):
        query_vec = embed_query(query_text)
        vector_rows = _vector_chunks_for_query(
            db,
            UnifiedSearchQuery(keyword=query_text, exclude_dev_docs=exclude_dev_docs, page=1, page_size=top_k),
            query_vec,
            max(top_k * 3, 10),
        )
    merged: dict[str, tuple[Chunk, Document, float]] = {}
    for chunk, doc, score_value in keyword_rows:
        merged[str(chunk.id)] = (
            chunk,
            doc,
            _final_score(
                score_value * settings.search_chunk_keyword_weight,
                doc,
                chunk.title,
                _chunk_search_blob(chunk),
                query_text,
            ),
        )
    for chunk, doc, vector_score in vector_rows:
        key = str(chunk.id)
        if key in merged:
            prev = merged[key]
            merged[key] = (
                prev[0],
                prev[1],
                _final_score(
                    prev[2] + vector_score * settings.search_chunk_vector_boost,
                    doc,
                    chunk.title,
                    _chunk_search_blob(chunk),
                    query_text,
                ),
            )
        else:
            merged[key] = (
                chunk,
                doc,
                _final_score(
                    vector_score * settings.search_chunk_vector_weight,
                    doc,
                    chunk.title,
                    _chunk_search_blob(chunk),
                    query_text,
                ),
            )
    rows = sorted(merged.values(), key=lambda item: item[2], reverse=True)
    return rows[:top_k]


def search_for_qa(
    db: Session,
    query_text: str,
    top_k: int = 5,
    *,
    exclude_dev_docs: bool = False,
) -> list[dict[str, Any]]:
    unit_rows = _search_units_for_qa(db, query_text, top_k=top_k, exclude_dev_docs=exclude_dev_docs)
    chunk_rows = _search_chunks_for_qa(db, query_text, top_k=top_k, exclude_dev_docs=exclude_dev_docs)

    merged: list[dict[str, Any]] = []
    seen_keys: set[tuple[str, str]] = set()
    represented_chunk_ids: set[str] = set()

    for unit, doc, score_value in unit_rows:
        key = ("unit", str(unit.id))
        if key in seen_keys:
            continue
        seen_keys.add(key)
        if unit.chunk_id is not None:
            represented_chunk_ids.add(str(unit.chunk_id))
        merged.append(
            {
                "kind": "unit",
                "score": score_value,
                "doc": doc,
                "unit": unit,
                "chunk": None,
            }
        )

    for chunk, doc, score_value in chunk_rows:
        key = ("chunk", str(chunk.id))
        if key in seen_keys or str(chunk.id) in represented_chunk_ids:
            continue
        seen_keys.add(key)
        merged.append(
            {
                "kind": "chunk",
                "score": score_value,
                "doc": doc,
                "unit": None,
                "chunk": chunk,
            }
        )

    merged.sort(key=lambda item: item["score"], reverse=True)
    return merged[:top_k]


def build_citations(rows: list[dict[str, Any]]) -> list[CitationOut]:
    citations: list[CitationOut] = []
    for row in rows:
        doc = row["doc"]
        unit = row.get("unit")
        chunk = row.get("chunk")
        metadata = doc.metadata_json or {}
        citations.append(
            CitationOut(
                doc_id=doc.id,
                source_file=doc.source_file,
                source_filename=doc.source_filename,
                relative_path=metadata.get("relative_path"),
                unit_id=unit.id if unit is not None else None,
                chunk_id=(unit.chunk_id if unit is not None else chunk.id if chunk is not None else None),
                title=doc.title,
                source_span=unit.source_span if unit is not None else chunk.title if chunk is not None else None,
                page_no=unit.source_page if unit is not None else chunk.page_no if chunk is not None else None,
            )
        )
    return citations


def search_unified(db: Session, query: UnifiedSearchQuery) -> tuple[list[UnifiedSearchItemOut], int]:
    exact_mode = _is_exactish_query(query.keyword)
    unit_query = KnowledgeUnitQuery(
        keyword=query.keyword,
        exclude_dev_docs=query.exclude_dev_docs,
        unit_type=query.unit_type,
        doc_type=query.doc_type,
        organization=query.organization,
        region=query.region,
        indicator=query.indicator,
        status=query.status,
        page=1,
        page_size=max(query.page_size * 3, 20),
    )
    keyword_unit_items, unit_total = search_knowledge_units(db, unit_query)
    vector_unit_rows = []
    if not _is_exactish_query(query.keyword):
        query_vec = embed_query(query.keyword)
        vector_unit_rows = _vector_units_for_query(db, unit_query, query_vec, max(query.page_size * 3, 20))
    chunk_rows = _search_chunks_for_qa(
        db,
        query.keyword,
        top_k=max(query.page_size * 3, 20),
        exclude_dev_docs=query.exclude_dev_docs,
    )
    unit_item_map: dict[str, UnifiedSearchItemOut] = {}

    items: list[UnifiedSearchItemOut] = []
    seen_chunk_ids: set[str] = set()
    for item in keyword_unit_items:
        doc = db.get(Document, item.doc_id)
        metadata = (doc.metadata_json or {}) if doc is not None else {}
        unit_item = UnifiedSearchItemOut(
            kind="unit",
            match_type=_infer_match_type(query.keyword, doc, item.title or (doc.title if doc is not None else None), item.normalized_text or item.content),
            score=_final_score(
                settings.search_keyword_weight,
                doc,
                item.title,
                _unit_search_blob(
                    KnowledgeUnit(
                        id=item.id,
                        doc_id=item.doc_id,
                        chunk_id=item.chunk_id,
                        unit_type=item.unit_type,
                        title=item.title,
                        content=item.content,
                        normalized_text=item.normalized_text,
                        subject=item.subject,
                        action=item.action,
                        organization=item.organization,
                        person=item.person,
                        region=item.region,
                        time_expr=item.time_expr,
                        event_date=None,
                        indicator=item.indicator,
                        value_num=item.value_num,
                        value_text=item.value_text,
                        unit_name=item.unit_name,
                        effective_date=None,
                        expiry_date=None,
                        status=item.status,
                        priority=item.priority,
                        confidence=item.confidence,
                        source_span=item.source_span,
                        source_page=item.source_page,
                        fields_json=item.fields,
                    ),
                    doc,
                ),
                query.keyword,
            ),
            doc_id=item.doc_id,
            source_file=doc.source_file if doc is not None else "",
            source_filename=doc.source_filename if doc is not None else None,
            relative_path=metadata.get("relative_path"),
            chunk_id=item.chunk_id,
            unit_id=item.id,
            doc_title=doc.title if doc is not None else None,
            doc_type=doc.doc_type if doc is not None else "unknown",
            is_dev_doc=doc.is_dev_doc if doc is not None else False,
            doc_domain=doc.doc_domain if doc is not None else "business",
            title=item.title or (doc.title if doc is not None else None),
            content=item.normalized_text or item.content,
            snippet=_build_snippet(query.keyword, item.normalized_text or item.content),
            page_no=item.source_page,
            source_span=item.source_span,
            subject=item.subject,
            indicator=item.indicator,
            tags=item.tags,
        )
        if exact_mode and unit_item.match_type == "summary":
            unit_item.score -= settings.search_summary_hit_boost
        unit_item_map[str(item.id)] = unit_item
        if item.chunk_id is not None and not exact_mode:
            seen_chunk_ids.add(str(item.chunk_id))
    for unit, doc, vector_score in vector_unit_rows:
        key = str(unit.id)
        if key in unit_item_map:
            unit_item_map[key].score = _final_score(
                unit_item_map[key].score + vector_score * settings.search_unit_vector_boost,
                doc,
                unit.title,
                _unit_search_blob(unit, doc),
                query.keyword,
            )
        else:
            unit_item_map[key] = UnifiedSearchItemOut(
                kind="unit",
                match_type=_infer_match_type(query.keyword, doc, unit.title or doc.title, unit.normalized_text or unit.content),
                score=_final_score(
                    vector_score * settings.search_unit_vector_weight,
                    doc,
                    unit.title,
                    _unit_search_blob(unit, doc),
                    query.keyword,
                ),
                doc_id=doc.id,
                source_file=doc.source_file,
                source_filename=doc.source_filename,
                relative_path=(doc.metadata_json or {}).get("relative_path"),
                chunk_id=unit.chunk_id,
                unit_id=unit.id,
                doc_title=doc.title,
                doc_type=doc.doc_type,
                is_dev_doc=doc.is_dev_doc,
                doc_domain=doc.doc_domain,
                title=unit.title or doc.title,
                content=unit.normalized_text or unit.content,
                snippet=_build_snippet(query.keyword, unit.normalized_text or unit.content),
                page_no=unit.source_page,
                source_span=unit.source_span,
                subject=unit.subject,
                indicator=unit.indicator,
                tags=[],
            )
            if unit.chunk_id is not None and not exact_mode:
                seen_chunk_ids.add(str(unit.chunk_id))

    items.extend(unit_item_map.values())

    for chunk, doc, score_value in chunk_rows:
        if str(chunk.id) in seen_chunk_ids:
            continue
        items.append(
            UnifiedSearchItemOut(
                kind="chunk",
                match_type=_infer_match_type(query.keyword, doc, chunk.title or doc.title, _chunk_search_blob(chunk)),
                score=_final_score(score_value, doc, chunk.title, _chunk_search_blob(chunk), query.keyword),
                doc_id=doc.id,
                source_file=doc.source_file,
                source_filename=doc.source_filename,
                relative_path=(doc.metadata_json or {}).get("relative_path"),
                chunk_id=chunk.id,
                unit_id=None,
                doc_title=doc.title,
                doc_type=doc.doc_type,
                is_dev_doc=doc.is_dev_doc,
                doc_domain=doc.doc_domain,
                title=chunk.title or doc.title,
                content=chunk.content,
                snippet=_build_snippet(query.keyword, _chunk_search_blob(chunk)),
                page_no=chunk.page_no,
                source_span=chunk.title,
                subject=None,
                indicator=None,
                tags=[],
            )
        )

    def dedupe_key(item: UnifiedSearchItemOut) -> tuple[str, str, str, str]:
        normalized_title = (item.title or "").strip().lower()
        normalized_span = (item.source_span or "").strip().lower()
        normalized_match = (item.match_type or "content")
        if normalized_match in {"title", "filename", "summary", "caption"}:
            normalized_span = ""
        return (
            str(item.doc_id),
            normalized_match,
            normalized_title,
            normalized_span,
        )

    deduped_map: dict[tuple[str, str, str, str], UnifiedSearchItemOut] = {}
    for item in items:
        key = dedupe_key(item)
        existing = deduped_map.get(key)
        if existing is None or item.score > existing.score:
            deduped_map[key] = item

    items = list(deduped_map.values())

    def sort_key(item: UnifiedSearchItemOut) -> tuple[float, int]:
        if exact_mode:
            kind_bonus = 1 if item.kind == "chunk" else 0
            return (item.score, kind_bonus)
        return (item.score, 1 if item.kind == "unit" else 0)

    items.sort(key=sort_key, reverse=True)
    diversified: list[UnifiedSearchItemOut] = []
    doc_counts: dict[str, int] = {}
    overflow: list[UnifiedSearchItemOut] = []
    max_per_doc = 2 if _is_exactish_query(query.keyword) else 3

    for item in items:
        doc_key = str(item.doc_id)
        current = doc_counts.get(doc_key, 0)
        if current < max_per_doc:
            diversified.append(item)
            doc_counts[doc_key] = current + 1
        else:
            overflow.append(item)

    items = diversified + overflow
    total = len(items)
    start = (query.page - 1) * query.page_size
    end = start + query.page_size
    return items[start:end], total
