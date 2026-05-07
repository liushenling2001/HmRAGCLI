from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import UTC, datetime, timedelta
from difflib import SequenceMatcher
from pathlib import Path
import re
import threading
import time
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.db.session import SessionLocal
from app.models.content import Chunk, KnowledgeUnit
from app.models.data_source import BatchIngestJob, SourceFile
from app.models.document import Document, IngestJob
from app.schemas.data_sources import BatchIngestJobOut
from app.schemas.documents import DocumentOut
from app.schemas.enums import DocumentStatus, JobStatus
from app.services import classifier as classifier_service
from app.services import converter as converter_service
from app.services import embedder as embedder_service
from app.services import extract as extract_service
from app.services import parser as parser_service
from app.services import scan as scan_service

RULE_HINTS = ["应当", "不得", "必须", "可以", "禁止", "负责", "审批", "批准", "标准", "办法", "规定"]
REPORT_HINTS = ["同比", "环比", "增长", "下降", "指标", "统计", "公报", "亿元", "万元", "%"]
SPEECH_HINTS = ["强调", "指出", "提出", "要求", "希望", "讲话", "发言"]
EXCEL_HINTS = ["sheet", "|", "列", "行", "字段", "指标"]
ENHANCE_EXTRACT_JOB = "enhance_extract"
ENHANCE_EMBED_JOB = "enhance_embed"
ENHANCEMENT_JOB_TYPES = {ENHANCE_EXTRACT_JOB, ENHANCE_EMBED_JOB}


def _text_similarity(a: str | None, b: str | None) -> float:
    if not a or not b:
        return 0.0
    return SequenceMatcher(None, a, b).ratio()


def _find_possible_duplicate(
    db: Session,
    *,
    source_file: SourceFile,
    title: str,
    preview_text: str,
    doc_type: str,
) -> Document | None:
    candidates = db.scalars(
        select(Document)
        .where(Document.id != source_file.doc_id if source_file.doc_id else True)
        .order_by(Document.created_at.desc())
        .limit(50)
    ).all()

    best_doc: Document | None = None
    best_score = 0.0
    for doc in candidates:
        doc_meta = doc.metadata_json or {}
        doc_preview = doc_meta.get("preview_text", "")
        title_score = _text_similarity(doc.title, title)
        preview_score = _text_similarity(doc_preview[:1200], preview_text[:1200])
        same_type_bonus = 0.1 if doc.doc_type == doc_type else 0.0
        size_score = 0.0
        existing_size = doc_meta.get("file_size")
        if existing_size and source_file.file_size:
            ratio = min(existing_size, source_file.file_size) / max(existing_size, source_file.file_size)
            size_score = 0.1 if ratio >= 0.9 else 0.0
        score = title_score * 0.45 + preview_score * 0.45 + same_type_bonus + size_score
        if score > best_score:
            best_score = score
            best_doc = doc

    if best_doc and best_score >= 0.82:
        return best_doc
    return None


def _document_from_source_file(
    source_file: SourceFile,
    doc_type: str,
    classification_meta: dict,
    preview_text: str,
    parse_metadata: dict,
    *,
    is_dev_doc: bool,
    doc_domain: str,
) -> Document:
    path = Path(source_file.file_path)
    now = datetime.now(UTC)
    return Document(
        title=path.stem,
        doc_type=doc_type,
        source_file=source_file.file_path,
        source_filename=source_file.file_name,
        source_org=None,
        author=None,
        publish_date=None,
        effective_date=None,
        expiry_date=None,
        status=DocumentStatus.reference.value,
        language="zh",
        file_hash=source_file.file_hash,
        is_dev_doc=is_dev_doc,
        doc_domain=doc_domain,
        parse_status="queued",
        metadata_json={
            "relative_path": source_file.relative_path,
            "file_ext": source_file.file_ext,
            "file_size": source_file.file_size,
            "classification": classification_meta,
            "is_dev_doc": is_dev_doc,
            "doc_domain": doc_domain,
            "preview_text": preview_text[:2000],
            "parse": parse_metadata,
        },
        created_at=now,
        updated_at=now,
    )


def _to_batch_job_out(job: BatchIngestJob) -> BatchIngestJobOut:
    return BatchIngestJobOut(
        id=job.id,
        data_source_id=job.data_source_id,
        trigger_type=job.trigger_type,
        status=job.status,
        total_files=job.total_files,
        success_files=job.success_files,
        failed_files=job.failed_files,
        skipped_files=job.skipped_files,
        started_at=job.started_at,
        finished_at=job.finished_at,
    )


def _chunk_value_score(doc_type: str, parsed: parser_service.ParsedChunk, idx: int) -> float:
    text = parsed.content or ""
    lowered = text.lower()
    score = 0.0

    if idx <= 2:
        score += 2.5
    if parsed.title:
        score += 1.5
    if parsed.chunk_type in {"sheet"}:
        score += 2.0
    if len(text) >= 150:
        score += 0.8
    if re.search(r"\d", text):
        score += 0.8

    if doc_type == "rule":
        score += sum(1.2 for token in RULE_HINTS if token in text)
    elif doc_type in {"report", "notice", "minutes"}:
        score += sum(1.0 for token in REPORT_HINTS if token in text)
    elif doc_type == "speech":
        score += sum(1.0 for token in SPEECH_HINTS if token in text)
    elif doc_type == "excel":
        score += sum(0.8 for token in EXCEL_HINTS if token in lowered)

    if "##" in text or "第" in text[:20]:
        score += 0.8
    if "|" in text:
        score += 1.2
    return score


def _select_ai_chunk_indexes(doc_type: str, parsed_chunks: list[parser_service.ParsedChunk], limit: int) -> set[int]:
    if limit <= 0:
        return set()
    scored = [
        (idx, _chunk_value_score(doc_type, parsed, idx))
        for idx, parsed in enumerate(parsed_chunks, start=1)
    ]
    scored.sort(key=lambda item: (item[1], -item[0]), reverse=True)
    return {idx for idx, _score in scored[:limit]}


def _select_embedding_chunk_indexes(
    doc_type: str,
    parsed_chunks: list[parser_service.ParsedChunk],
    limit: int,
) -> set[int]:
    if limit <= 0 or len(parsed_chunks) <= limit:
        return {idx for idx, _parsed in enumerate(parsed_chunks, start=1)}
    return _select_ai_chunk_indexes(doc_type, parsed_chunks, limit)


def _merge_document_ingest_stats(document: Document, **updates: int) -> None:
    metadata = dict(document.metadata_json or {})
    ingest_stats = dict(metadata.get("ingest_stats", {}))
    ingest_stats.update(updates)
    metadata["ingest_stats"] = ingest_stats
    document.metadata_json = metadata


def _find_open_enhancement_job(db: Session, *, source_file_id: UUID, stage: str) -> IngestJob | None:
    return db.scalar(
        select(IngestJob).where(
            IngestJob.source_file_id == source_file_id,
            IngestJob.stage == stage,
            IngestJob.status.in_(["pending", "running"]),
        )
    )


def _enqueue_enhancement_job(
    db: Session,
    *,
    source_file: SourceFile,
    document: Document,
    stage: str,
    progress_total: int = 0,
) -> IngestJob | None:
    existing = _find_open_enhancement_job(db, source_file_id=source_file.id, stage=stage)
    if existing is not None:
        if progress_total and existing.progress_total != progress_total:
            existing.progress_total = progress_total
            existing.updated_at = datetime.now(UTC)
            db.commit()
        return existing
    job = IngestJob(
        doc_id=document.id,
        source_file_id=source_file.id,
        data_source_id=source_file.data_source_id,
        job_type=stage,
        stage=stage,
        status="pending",
        progress_total=max(0, progress_total),
        progress_completed=0,
        payload_json={},
        created_at=datetime.now(UTC),
        updated_at=datetime.now(UTC),
    )
    db.add(job)
    db.commit()
    return job


def _enqueue_extract_job(db: Session, *, source_file: SourceFile, document: Document, total_chunks: int) -> IngestJob | None:
    return _enqueue_enhancement_job(
        db,
        source_file=source_file,
        document=document,
        stage=ENHANCE_EXTRACT_JOB,
        progress_total=total_chunks,
    )


def _enqueue_embed_job(db: Session, *, source_file: SourceFile, document: Document, total_chunks: int) -> IngestJob | None:
    return _enqueue_enhancement_job(
        db,
        source_file=source_file,
        document=document,
        stage=ENHANCE_EMBED_JOB,
        progress_total=total_chunks,
    )


def _ingest_source_file_by_id(source_file_id: UUID, *, schedule_enhancement: bool = True) -> str:
    db = SessionLocal()
    try:
        source_file = db.get(SourceFile, source_file_id)
        if source_file is None:
            return "failed"
        return _ingest_source_file(db, source_file, schedule_enhancement=schedule_enhancement)
    finally:
        db.close()


def _schedule_pending_enhancement_for_source_file(db: Session, source_file: SourceFile, *, max_jobs: int | None = None) -> int:
    document = _resolved_document_for_source_file(db, source_file)
    if document is None:
        return 0

    stats = (document.metadata_json or {}).get("ingest_stats", {})
    scheduled = 0
    remaining = max_jobs if max_jobs is not None else 2

    if remaining > 0 and source_file.extract_status in {"queued", "pending", None}:
        total = int(stats.get("ai_chunk_total") or stats.get("ai_chunk_count") or 0)
        if _enqueue_extract_job(db, source_file=source_file, document=document, total_chunks=total) is not None:
            scheduled += 1
            remaining -= 1

    if remaining > 0 and source_file.extract_status == "success" and source_file.index_status in {"queued", "pending", None}:
        total = int(stats.get("embedding_chunk_total") or stats.get("embedded_chunk_count") or 0)
        if _enqueue_embed_job(db, source_file=source_file, document=document, total_chunks=total) is not None:
            scheduled += 1

    return scheduled


def _count_open_enhancement_jobs(db: Session) -> int:
    return int(
        db.scalar(
            select(func.count(IngestJob.id)).where(
                IngestJob.job_type.in_(list(ENHANCEMENT_JOB_TYPES)),
                IngestJob.status.in_(["pending", "running"]),
            )
        )
        or 0
    )


def _schedule_enhancement_backlog(db: Session) -> int:
    open_jobs = _count_open_enhancement_jobs(db)
    max_pending = max(1, settings.enhancement_queue_max_pending)
    remaining_slots = max(0, max_pending - open_jobs)
    if remaining_slots <= 0:
        return 0

    batch_limit = max(1, settings.enhancement_queue_fill_batch_size)
    allowed = min(remaining_slots, batch_limit)
    rows = db.scalars(
        select(SourceFile).where(
            SourceFile.discover_status == "active",
        )
    ).all()
    scheduled = 0
    for row in rows:
        if scheduled >= allowed:
            break
        build_ready = row.classification_status in {"success", "skipped"} and row.parse_status in {"success", "skipped"}
        if not build_ready or not (row.doc_id or row.duplicate_of_doc_id):
            continue
        if row.extract_status in {"queued", "pending", None} or (
            row.extract_status == "success" and row.index_status in {"queued", "pending", None}
        ):
            remaining_for_file = max(0, allowed - scheduled)
            if remaining_for_file <= 0:
                break
            scheduled += _schedule_pending_enhancement_for_source_file(db, row, max_jobs=remaining_for_file)
    if scheduled:
        db.commit()
    return scheduled


def recover_stale_processing_work(db: Session) -> int:
    cutoff = datetime.now(UTC) - timedelta(seconds=max(30, settings.stale_processing_seconds))
    stale_jobs = db.scalars(
        select(IngestJob).where(
            IngestJob.job_type.in_(list(ENHANCEMENT_JOB_TYPES)),
            IngestJob.status == "running",
            IngestJob.updated_at.is_not(None),
            IngestJob.updated_at < cutoff,
        )
    ).all()
    for job in stale_jobs:
        job.status = "pending"
        job.updated_at = datetime.now(UTC)
        job.heartbeat_at = job.updated_at

    stale_rows = db.scalars(
        select(SourceFile).where(
            SourceFile.ingest_status == "processing",
            SourceFile.updated_at.is_not(None),
            SourceFile.updated_at < cutoff,
        )
    ).all()

    recovered_jobs = 0
    for row in stale_rows:
        build_ready = row.classification_status in {"success", "skipped"} and row.parse_status in {"success", "skipped"}
        if build_ready and (
            row.extract_status in {"processing", "queued", "pending"} or row.index_status in {"processing", "queued", "pending"}
        ):
            row.processing_stage = "extracting" if row.extract_status != "success" else "indexing"
            row.ingest_status = "processing"
            row.updated_at = datetime.now(UTC)
            document = db.get(Document, row.doc_id or row.duplicate_of_doc_id) if (row.doc_id or row.duplicate_of_doc_id) else None
            if document is not None:
                stats = (document.metadata_json or {}).get("ingest_stats", {})
                if row.extract_status != "success":
                    recovered_jobs += _schedule_pending_enhancement_for_source_file(db, row)
                elif row.index_status != "success":
                    recovered_jobs += _schedule_pending_enhancement_for_source_file(db, row)
        elif row.processing_stage in {"classifying", "parsing"}:
            row.ingest_status = "failed"
            if row.processing_stage == "classifying":
                row.classification_status = "failed"
                row.error_message = row.error_message or "分类阶段中断，服务重启后未恢复"
            else:
                row.parse_status = "failed"
                row.error_message = row.error_message or "解析阶段中断，服务重启后未恢复"
            row.updated_at = datetime.now(UTC)
    db.commit()
    return max(recovered_jobs, len(stale_jobs))


def _persist_file_progress(
    db: Session,
    *,
    source_file: SourceFile,
    document: Document,
    processing_stage: str | None = None,
    classification_status: str | None = None,
    parse_status: str | None = None,
    extract_status: str | None = None,
    index_status: str | None = None,
    ingest_status: str | None = None,
    error_message: str | None = None,
    commit: bool = True,
) -> None:
    now = datetime.now(UTC)
    if processing_stage is not None:
        source_file.processing_stage = processing_stage
    if classification_status is not None:
        source_file.classification_status = classification_status
    if parse_status is not None:
        source_file.parse_status = parse_status
    if extract_status is not None:
        source_file.extract_status = extract_status
    if index_status is not None:
        source_file.index_status = index_status
    if ingest_status is not None:
        source_file.ingest_status = ingest_status
    if error_message is not None:
        source_file.error_message = error_message
    source_file.updated_at = now
    document.updated_at = now
    if commit:
        db.commit()


def _load_document_chunks(db: Session, document_id: UUID | str) -> list[Chunk]:
    return list(
        db.scalars(
            select(Chunk).where(Chunk.doc_id == document_id).order_by(Chunk.chunk_no.asc())
        ).all()
    )


def _build_parsed_chunks_from_db(chunks: list[Chunk]) -> list[parser_service.ParsedChunk]:
    return [
        parser_service.ParsedChunk(
            content=chunk.content,
            chunk_type=chunk.chunk_type,
            title=chunk.title,
            page_no=chunk.page_no,
            metadata=chunk.metadata_json,
        )
        for chunk in chunks
    ]


def _mark_job_running(job: IngestJob) -> None:
    now = datetime.now(UTC)
    job.status = "running"
    job.started_at = job.started_at or now
    job.heartbeat_at = now
    job.updated_at = now
    job.attempt_count = int(job.attempt_count or 0) + 1


def _mark_job_progress(job: IngestJob, *, completed: int, total: int | None = None) -> None:
    now = datetime.now(UTC)
    if total is not None:
        job.progress_total = max(0, total)
    job.progress_completed = max(0, completed)
    job.heartbeat_at = now
    job.updated_at = now


def _mark_job_success(job: IngestJob) -> None:
    now = datetime.now(UTC)
    if job.progress_total and job.progress_completed < job.progress_total:
        job.progress_completed = job.progress_total
    job.status = "success"
    job.finished_at = now
    job.heartbeat_at = now
    job.updated_at = now
    job.error_message = None


def _mark_job_failed(job: IngestJob, message: str) -> None:
    now = datetime.now(UTC)
    job.status = "failed"
    job.error_message = message
    job.finished_at = now
    job.heartbeat_at = now
    job.updated_at = now


def _claim_next_enhancement_job(db: Session) -> IngestJob | None:
    stmt = (
        select(IngestJob)
        .where(
            IngestJob.job_type.in_(list(ENHANCEMENT_JOB_TYPES)),
            IngestJob.status == "pending",
        )
        .order_by(IngestJob.created_at.asc())
        .with_for_update(skip_locked=True)
    )
    job = db.scalar(stmt)
    if job is None:
        return None
    _mark_job_running(job)
    db.commit()
    db.refresh(job)
    return job


def _run_extract_enhancement_job(db: Session, job: IngestJob) -> None:
    source_file = db.get(SourceFile, job.source_file_id)
    document = db.get(Document, job.doc_id) if job.doc_id else None
    if source_file is None or document is None:
        _mark_job_failed(job, "增强任务关联的文件或文档不存在")
        db.commit()
        return

    source_file.processing_stage = "extracting"
    source_file.extract_status = "processing"
    source_file.index_status = "queued"
    source_file.ingest_status = "processing"
    source_file.error_message = None
    source_file.updated_at = datetime.now(UTC)
    document.updated_at = source_file.updated_at
    db.commit()

    chunks = _load_document_chunks(db, document.id)
    parsed_chunks = _build_parsed_chunks_from_db(chunks)
    ai_chunk_indexes = _select_ai_chunk_indexes(document.doc_type, parsed_chunks, max(0, settings.ai_extract_chunk_limit))
    embedding_chunk_indexes = _select_embedding_chunk_indexes(document.doc_type, parsed_chunks, max(0, settings.embedding_chunk_limit_per_file))

    total_ai = len(ai_chunk_indexes)
    _mark_job_progress(job, completed=0, total=total_ai)
    _merge_document_ingest_stats(
        document,
        parsed_chunks=len(chunks),
        parsed_chunk_total=len(chunks),
        parsed_chunk_completed=len(chunks),
        ai_chunk_count=total_ai,
        ai_chunk_total=total_ai,
        ai_chunk_completed=0,
        embedded_chunk_count=len(embedding_chunk_indexes),
        embedding_chunk_total=len(embedding_chunk_indexes),
        embedding_chunk_completed=0,
    )
    db.commit()

    db.query(KnowledgeUnit).filter(KnowledgeUnit.doc_id == document.id).delete()
    db.commit()

    ai_done = 0
    for idx, chunk in enumerate(chunks, start=1):
        use_ai = idx in ai_chunk_indexes
        ku = extract_service.build_knowledge_unit(document.doc_type, chunk, use_ai=use_ai)
        db.add(ku)
        db.flush()
        if use_ai:
            ai_done += 1
            _mark_job_progress(job, completed=ai_done)
        _merge_document_ingest_stats(
            document,
            parsed_chunks=len(chunks),
            parsed_chunk_total=len(chunks),
            parsed_chunk_completed=len(chunks),
            ai_chunk_count=total_ai,
            ai_chunk_total=total_ai,
            ai_chunk_completed=ai_done,
            embedded_chunk_count=len(embedding_chunk_indexes),
            embedding_chunk_total=len(embedding_chunk_indexes),
            embedding_chunk_completed=0,
        )
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()

    source_file.extract_status = "success"
    source_file.processing_stage = "indexing" if embedding_chunk_indexes else "completed"
    source_file.updated_at = datetime.now(UTC)
    document.updated_at = source_file.updated_at
    _mark_job_success(job)
    db.commit()

    if embedding_chunk_indexes:
        _enqueue_embed_job(
            db,
            source_file=source_file,
            document=document,
            total_chunks=len(embedding_chunk_indexes),
        )
    else:
        source_file.index_status = "success"
        source_file.ingest_status = "success"
        source_file.last_ingest_at = datetime.now(UTC)
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()


def _run_embed_enhancement_job(db: Session, job: IngestJob) -> None:
    source_file = db.get(SourceFile, job.source_file_id)
    document = db.get(Document, job.doc_id) if job.doc_id else None
    if source_file is None or document is None:
        _mark_job_failed(job, "向量任务关联的文件或文档不存在")
        db.commit()
        return

    source_file.processing_stage = "indexing"
    source_file.index_status = "processing"
    source_file.ingest_status = "processing"
    source_file.error_message = None
    source_file.updated_at = datetime.now(UTC)
    document.updated_at = source_file.updated_at
    db.commit()

    chunks = _load_document_chunks(db, document.id)
    parsed_chunks = _build_parsed_chunks_from_db(chunks)
    embedding_chunk_indexes = _select_embedding_chunk_indexes(document.doc_type, parsed_chunks, max(0, settings.embedding_chunk_limit_per_file))
    units = list(
        db.scalars(
            select(KnowledgeUnit).where(KnowledgeUnit.doc_id == document.id).order_by(KnowledgeUnit.created_at.asc())
        ).all()
    )

    total_embed = len(embedding_chunk_indexes)
    _mark_job_progress(job, completed=0, total=total_embed)
    emb_done = 0
    for idx, chunk in enumerate(chunks, start=1):
        if idx not in embedding_chunk_indexes:
            continue
        embedder_service.embed_chunks(db, [chunk])
        if idx - 1 < len(units):
            embedder_service.embed_knowledge_units(db, [units[idx - 1]])
        emb_done += 1
        _mark_job_progress(job, completed=emb_done)
        _merge_document_ingest_stats(
            document,
            parsed_chunks=len(chunks),
            parsed_chunk_total=len(chunks),
            parsed_chunk_completed=len(chunks),
            ai_chunk_count=int((document.metadata_json or {}).get("ingest_stats", {}).get("ai_chunk_total", 0)),
            ai_chunk_total=int((document.metadata_json or {}).get("ingest_stats", {}).get("ai_chunk_total", 0)),
            ai_chunk_completed=int((document.metadata_json or {}).get("ingest_stats", {}).get("ai_chunk_completed", 0)),
            embedded_chunk_count=total_embed,
            embedding_chunk_total=total_embed,
            embedding_chunk_completed=emb_done,
        )
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()

    source_file.index_status = "success"
    source_file.processing_stage = "completed"
    source_file.ingest_status = "success"
    source_file.last_ingest_at = datetime.now(UTC)
    source_file.updated_at = datetime.now(UTC)
    document.updated_at = source_file.updated_at
    _mark_job_success(job)
    db.commit()


def _run_enhancement_job_by_id(job_id: UUID | str) -> None:
    db = SessionLocal()
    try:
        job = db.get(IngestJob, job_id)
        if job is None:
            return
        try:
            if job.job_type == ENHANCE_EXTRACT_JOB:
                _run_extract_enhancement_job(db, job)
            elif job.job_type == ENHANCE_EMBED_JOB:
                _run_embed_enhancement_job(db, job)
        except Exception as exc:
            db.rollback()
            job = db.get(IngestJob, job_id)
            if job is not None:
                _mark_job_failed(job, str(exc))
                source_file = db.get(SourceFile, job.source_file_id) if job.source_file_id else None
                if source_file is not None:
                    source_file.ingest_status = "failed"
                    source_file.error_message = str(exc)
                    if job.job_type == ENHANCE_EXTRACT_JOB:
                        source_file.extract_status = "failed"
                        source_file.processing_stage = "extracting"
                    else:
                        source_file.index_status = "failed"
                        source_file.processing_stage = "indexing"
                    source_file.updated_at = datetime.now(UTC)
                db.commit()
    finally:
        db.close()


def run_enhancement_worker(stop_event: threading.Event) -> None:
    last_recover_at = 0.0
    last_schedule_at = 0.0
    while not stop_event.is_set():
        now_ts = time.monotonic()
        if now_ts - last_recover_at >= max(10.0, settings.enhancement_worker_poll_seconds * 5):
            db = SessionLocal()
            try:
                recover_stale_processing_work(db)
            finally:
                db.close()
            last_recover_at = now_ts
        if now_ts - last_schedule_at >= max(5.0, settings.enhancement_worker_poll_seconds * 2):
            db = SessionLocal()
            try:
                _schedule_enhancement_backlog(db)
            finally:
                db.close()
            last_schedule_at = now_ts
        db = SessionLocal()
        try:
            job = _claim_next_enhancement_job(db)
        finally:
            db.close()
        if job is None:
            stop_event.wait(settings.enhancement_worker_poll_seconds)
            continue
        _run_enhancement_job_by_id(job.id)


def _ingest_source_file(db: Session, source_file: SourceFile, *, schedule_enhancement: bool = True) -> str:
    file_started_at = datetime.now(UTC)
    source_file.ingest_status = "processing"
    source_file.processing_stage = "parsing"
    source_file.error_message = None
    source_file.updated_at = file_started_at
    db.commit()
    try:
        try:
            prepared_parse = parser_service.prepare_parse_result(source_file.file_path)
            preview_text = prepared_parse.preview_text
            parse_metadata = prepared_parse.parse_metadata
        except Exception:
            source_file.processing_stage = "parsing"
            raise
        source_file.parse_status = "success"
        source_file.processing_stage = "classifying"
        source_file.updated_at = datetime.now(UTC)
        db.commit()
        classification = classifier_service.classify_document(source_file.file_path, preview_text)
        is_dev_doc, doc_domain = classifier_service.detect_document_domain(source_file.file_path, preview_text)
        source_file.classification_status = "success"
        source_file.processing_stage = "classified"
        source_file.updated_at = datetime.now(UTC)
        db.commit()

        existing_doc = None
        current_doc_id = source_file.doc_id
        if current_doc_id:
            existing_doc = db.get(Document, current_doc_id)

        if existing_doc is None and source_file.file_hash:
            candidate_doc = db.scalar(
                select(Document).where(Document.file_hash == source_file.file_hash)
            )
            if candidate_doc is not None:
                existing_doc = candidate_doc

        if (
            existing_doc is not None
            and existing_doc.parse_status == "success"
            and (current_doc_id is None or existing_doc.id != current_doc_id)
        ):
            source_file.doc_id = existing_doc.id
            source_file.duplicate_of_doc_id = existing_doc.id
            source_file.is_exact_duplicate = True
            source_file.processing_stage = "completed"
            source_file.parse_status = "skipped"
            source_file.extract_status = "skipped"
            source_file.index_status = "skipped"
            source_file.ingest_status = "success"
            source_file.last_ingest_at = datetime.now(UTC)
            source_file.updated_at = datetime.now(UTC)
            existing_doc.duplicate_count = (existing_doc.duplicate_count or 0) + 1
            db.commit()
            return "skipped"

        possible_duplicate = _find_possible_duplicate(
            db,
            source_file=source_file,
            title=Path(source_file.file_path).stem,
            preview_text=preview_text,
            doc_type=classification.doc_type,
        )
        if possible_duplicate is not None and (current_doc_id is None or possible_duplicate.id != current_doc_id):
            source_file.is_possible_duplicate = True
            source_file.duplicate_of_doc_id = possible_duplicate.id
        else:
            source_file.is_possible_duplicate = False
            source_file.duplicate_of_doc_id = None

        if existing_doc is None:
            existing_doc = _document_from_source_file(
                source_file,
                classification.doc_type,
                classification.model_dump(),
                preview_text,
                parse_metadata,
                is_dev_doc=is_dev_doc,
                doc_domain=doc_domain,
            )
            db.add(existing_doc)
            db.flush()
        else:
            existing_doc.title = Path(source_file.file_path).stem
            existing_doc.doc_type = classification.doc_type
            existing_doc.source_file = source_file.file_path
            existing_doc.source_filename = source_file.file_name
            existing_doc.file_hash = source_file.file_hash
            existing_doc.is_dev_doc = is_dev_doc
            existing_doc.doc_domain = doc_domain
            existing_doc.parse_status = "queued"
            existing_doc.metadata_json = {
                **(existing_doc.metadata_json or {}),
                "classification": classification.model_dump(),
                "is_dev_doc": is_dev_doc,
                "doc_domain": doc_domain,
                "preview_text": preview_text[:2000],
                "parse": parse_metadata,
            }
            existing_doc.updated_at = datetime.now(UTC)
        if "preview_text" not in (existing_doc.metadata_json or {}):
            existing_doc.metadata_json = {
                **(existing_doc.metadata_json or {}),
                "preview_text": preview_text[:2000],
                "parse": parse_metadata,
            }
        db.commit()

        _persist_file_progress(
            db,
            source_file=source_file,
            document=existing_doc,
            processing_stage="parsing",
            parse_status="processing",
        )
        db.query(Chunk).filter(Chunk.doc_id == existing_doc.id).delete()
        db.query(KnowledgeUnit).filter(KnowledgeUnit.doc_id == existing_doc.id).delete()
        db.commit()

        parsed_chunks = prepared_parse.parsed_chunks
        ai_chunk_limit = max(0, settings.ai_extract_chunk_limit)
        ai_chunk_indexes = _select_ai_chunk_indexes(existing_doc.doc_type, parsed_chunks, ai_chunk_limit)
        embedding_chunk_limit = max(0, settings.embedding_chunk_limit_per_file)
        embedding_chunk_indexes = _select_embedding_chunk_indexes(
            existing_doc.doc_type,
            parsed_chunks,
            embedding_chunk_limit,
        )
        parsed_chunk_total = len(parsed_chunks)
        ai_chunk_total = len(ai_chunk_indexes)
        embedding_chunk_total = len(embedding_chunk_indexes)
        _merge_document_ingest_stats(
            existing_doc,
            parsed_chunks=parsed_chunk_total,
            parsed_chunk_total=parsed_chunk_total,
            parsed_chunk_completed=0,
            ai_chunk_count=ai_chunk_total,
            ai_chunk_total=ai_chunk_total,
            ai_chunk_completed=0,
            embedded_chunk_count=embedding_chunk_total,
            embedding_chunk_total=embedding_chunk_total,
            embedding_chunk_completed=0,
        )
        _persist_file_progress(
            db,
            source_file=source_file,
            document=existing_doc,
            processing_stage="extracting",
            parse_status="success",
            extract_status="queued",
            index_status="queued",
        )

        parsed_chunk_completed = 0
        for idx, parsed in enumerate(parsed_chunks, start=1):
            chunk = Chunk(
                doc_id=existing_doc.id,
                chunk_no=idx,
                chunk_type=parsed.chunk_type,
                title=parsed.title,
                content=parsed.content,
                page_no=parsed.page_no,
                start_offset=None,
                end_offset=None,
                token_count=max(1, len(parsed.content) // 4),
                metadata_json=parsed.metadata or {},
            )
            db.add(chunk)
            db.flush()
            ku = extract_service.build_knowledge_unit(existing_doc.doc_type, chunk, use_ai=False)
            db.add(ku)
            db.flush()
            parsed_chunk_completed += 1
            _merge_document_ingest_stats(
                existing_doc,
                parsed_chunks=parsed_chunk_total,
                parsed_chunk_total=parsed_chunk_total,
                parsed_chunk_completed=parsed_chunk_completed,
                ai_chunk_count=ai_chunk_total,
                ai_chunk_total=ai_chunk_total,
                ai_chunk_completed=0,
                embedded_chunk_count=embedding_chunk_total,
                embedding_chunk_total=embedding_chunk_total,
                embedding_chunk_completed=0,
            )
            source_file.updated_at = datetime.now(UTC)
            existing_doc.updated_at = source_file.updated_at
            db.commit()

        source_file.doc_id = existing_doc.id
        source_file.duplicate_of_doc_id = None
        source_file.is_exact_duplicate = False
        source_file.extract_status = "queued"
        source_file.index_status = "queued"
        source_file.processing_stage = "extracting"
        source_file.ingest_status = "processing" if settings.async_enhance_after_ingest else "success"
        source_file.last_ingest_at = datetime.now(UTC)
        source_file.error_message = None
        existing_doc.parse_status = "success"
        existing_doc.metadata_json = {
            **(existing_doc.metadata_json or {}),
            "ingest_stats": {
                "parsed_chunks": parsed_chunk_total,
                "parsed_chunk_total": parsed_chunk_total,
                "parsed_chunk_completed": parsed_chunk_total,
                "ai_chunk_count": ai_chunk_total,
                "ai_chunk_total": ai_chunk_total,
                "ai_chunk_completed": 0,
                "embedded_chunk_count": embedding_chunk_total,
                "embedding_chunk_total": embedding_chunk_total,
                "embedding_chunk_completed": 0,
            },
        }
        existing_doc.updated_at = datetime.now(UTC)
        db.commit()
        if schedule_enhancement and settings.async_enhance_after_ingest:
            _enqueue_extract_job(
                db,
                source_file=source_file,
                document=existing_doc,
                total_chunks=ai_chunk_total,
            )
        return "success"
    except Exception as exc:
        db.rollback()
        source_file = db.get(SourceFile, source_file.id)
        source_file.ingest_status = "failed"
        source_file.retry_count = (source_file.retry_count or 0) + 1
        is_conversion_error = isinstance(exc, converter_service.DocConversionError)
        is_enhanced_parser_error = isinstance(exc, parser_service.EnhancedParserNotReadyError)
        source_file.error_message = (
            f"DOC_CONVERSION_FAILED: {exc}" if is_conversion_error else str(exc)
        )
        if source_file.processing_stage in {"parsing"}:
            if is_conversion_error:
                source_file.parse_status = "conversion_failed"
            elif is_enhanced_parser_error:
                source_file.parse_status = "enhanced_parser_not_ready"
            else:
                source_file.parse_status = "failed"
        elif source_file.processing_stage == "classifying":
            source_file.classification_status = "failed"
        elif source_file.processing_stage == "parsing" or is_conversion_error or is_enhanced_parser_error:
            if is_conversion_error:
                source_file.parse_status = "conversion_failed"
            elif is_enhanced_parser_error:
                source_file.parse_status = "enhanced_parser_not_ready"
            else:
                source_file.parse_status = "failed"
        else:
            source_file.extract_status = "failed"
        source_file.updated_at = datetime.now(UTC)
        if source_file.doc_id:
            existing_doc = db.get(Document, source_file.doc_id)
            if existing_doc is not None:
                db.query(Chunk).filter(Chunk.doc_id == existing_doc.id).delete()
                db.query(KnowledgeUnit).filter(KnowledgeUnit.doc_id == existing_doc.id).delete()
                existing_doc.parse_status = (
                    "conversion_failed"
                    if is_conversion_error
                    else "enhanced_parser_not_ready"
                    if is_enhanced_parser_error
                    else "failed"
                )
                existing_doc.metadata_json = {
                    **(existing_doc.metadata_json or {}),
                    "parse_error": {
                        "type": (
                            "doc_conversion_failed"
                            if is_conversion_error
                            else "enhanced_parser_not_ready"
                            if is_enhanced_parser_error
                            else "parse_failed"
                        ),
                        "message": str(exc),
                    },
                }
                existing_doc.updated_at = datetime.now(UTC)
        db.commit()
        return "failed"


def run_batch_ingest(db: Session, batch_job_id: UUID) -> BatchIngestJobOut | None:
    job = db.get(BatchIngestJob, batch_job_id)
    if job is None:
        return None

    now = datetime.now(UTC)
    job.status = JobStatus.running.value
    job.started_at = job.started_at or now

    pending_files = db.scalars(
        select(SourceFile).where(
            SourceFile.data_source_id == job.data_source_id,
            SourceFile.discover_status == "active",
            SourceFile.ingest_status.in_(["pending", "processing"]),
        )
    ).all()

    job.total_files = len(pending_files)
    success = 0
    failed = 0
    skipped = 0
    db.commit()

    source_file_ids = [row.id for row in pending_files]
    successful_source_file_ids: list[UUID] = []
    max_workers = max(1, min(settings.ingest_build_max_workers, len(source_file_ids) or 1))
    with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="hmrag-build") as executor:
        future_map = {
            executor.submit(_ingest_source_file_by_id, source_file_id, schedule_enhancement=False): source_file_id
            for source_file_id in source_file_ids
        }
        for future in as_completed(future_map):
            source_file_id = future_map[future]
            try:
                result = future.result()
            except Exception:
                result = "failed"

            if result == "success":
                success += 1
                successful_source_file_ids.append(source_file_id)
            elif result == "skipped":
                skipped += 1
            else:
                failed += 1

            job = db.get(BatchIngestJob, batch_job_id)
            if job is None:
                return None
            job.success_files = success
            job.failed_files = failed
            job.skipped_files = skipped
            job.status = JobStatus.running.value
            db.commit()

    if settings.async_enhance_after_ingest and successful_source_file_ids:
        _schedule_enhancement_backlog(db)

    job.success_files = success
    job.failed_files = failed
    job.skipped_files = skipped
    job.status = JobStatus.success.value if failed == 0 else JobStatus.partial_failed.value
    job.finished_at = datetime.now(UTC)
    db.commit()
    db.refresh(job)
    return _to_batch_job_out(job)


def run_single_file_ingest(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None

    schedule_single_file_ingest(db, source_file_id)
    _ingest_source_file(db, source_file)
    return scan_service.get_source_file(db, str(source_file_id))


def _resolved_document_for_source_file(db: Session, source_file: SourceFile) -> Document | None:
    doc_id = source_file.doc_id or source_file.duplicate_of_doc_id
    if doc_id is None:
        return None
    return db.get(Document, doc_id)


def run_single_file_reextract(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    document = _resolved_document_for_source_file(db, source_file)
    if document is None:
        return scan_service.get_source_file(db, str(source_file_id))

    schedule_single_file_reextract(db, source_file_id)

    try:
        chunks = list(
            db.scalars(
                select(Chunk).where(Chunk.doc_id == document.id).order_by(Chunk.chunk_no.asc())
            ).all()
        )
        db.query(KnowledgeUnit).filter(KnowledgeUnit.doc_id == document.id).delete()
        db.commit()

        ai_chunk_limit = max(0, settings.ai_extract_chunk_limit)
        parsed_chunks = [
            parser_service.ParsedChunk(
                content=chunk.content,
                chunk_type=chunk.chunk_type,
                title=chunk.title,
                page_no=chunk.page_no,
                metadata=chunk.metadata_json,
            )
            for chunk in chunks
        ]
        ai_chunk_indexes = _select_ai_chunk_indexes(document.doc_type, parsed_chunks, ai_chunk_limit)
        parsed_total = len(chunks)
        ai_total = len(ai_chunk_indexes)
        _merge_document_ingest_stats(
            document,
            parsed_chunks=parsed_total,
            parsed_chunk_total=parsed_total,
            parsed_chunk_completed=parsed_total,
            ai_chunk_count=ai_total,
            ai_chunk_total=ai_total,
            ai_chunk_completed=0,
        )
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()

        ai_done = 0
        for idx, chunk in enumerate(chunks, start=1):
            use_ai = idx in ai_chunk_indexes
            ku = extract_service.build_knowledge_unit(document.doc_type, chunk, use_ai=use_ai)
            db.add(ku)
            db.flush()
            embedder_service.embed_knowledge_units(db, [ku])
            if use_ai:
                ai_done += 1
            _merge_document_ingest_stats(
                document,
                parsed_chunks=parsed_total,
                parsed_chunk_total=parsed_total,
                parsed_chunk_completed=parsed_total,
                ai_chunk_count=ai_total,
                ai_chunk_total=ai_total,
                ai_chunk_completed=ai_done,
            )
            source_file.updated_at = datetime.now(UTC)
            document.updated_at = source_file.updated_at
            db.commit()

        source_file.extract_status = "success"
        source_file.index_status = "success"
        source_file.processing_stage = "completed"
        source_file.ingest_status = "success"
        source_file.last_ingest_at = datetime.now(UTC)
        source_file.updated_at = datetime.now(UTC)
        document.parse_status = "success"
        document.updated_at = datetime.now(UTC)
        db.commit()
    except Exception as exc:
        db.rollback()
        source_file = db.get(SourceFile, source_file_id)
        source_file.ingest_status = "failed"
        source_file.extract_status = "failed"
        source_file.error_message = str(exc)
        source_file.updated_at = datetime.now(UTC)
        db.commit()

    return scan_service.get_source_file(db, str(source_file_id))


def run_single_file_reembed(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    document = _resolved_document_for_source_file(db, source_file)
    if document is None:
        return scan_service.get_source_file(db, str(source_file_id))

    schedule_single_file_reembed(db, source_file_id)

    try:
        chunks = list(
            db.scalars(
                select(Chunk).where(Chunk.doc_id == document.id).order_by(Chunk.chunk_no.asc())
            ).all()
        )
        units = list(
            db.scalars(
                select(KnowledgeUnit).where(KnowledgeUnit.doc_id == document.id).order_by(KnowledgeUnit.created_at.asc())
            ).all()
        )
        embed_total = len(chunks)
        _merge_document_ingest_stats(
            document,
            parsed_chunks=len(chunks),
            parsed_chunk_total=len(chunks),
            parsed_chunk_completed=len(chunks),
            embedding_chunk_total=embed_total,
            embedded_chunk_count=embed_total,
            embedding_chunk_completed=0,
        )
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()
        embed_done = 0
        for chunk in chunks:
            embedder_service.embed_chunks(db, [chunk])
            embed_done += 1
            _merge_document_ingest_stats(
                document,
                parsed_chunks=len(chunks),
                parsed_chunk_total=len(chunks),
                parsed_chunk_completed=len(chunks),
                embedding_chunk_total=embed_total,
                embedded_chunk_count=embed_total,
                embedding_chunk_completed=embed_done,
            )
            source_file.updated_at = datetime.now(UTC)
            document.updated_at = source_file.updated_at
            db.commit()
        if units:
            embedder_service.embed_knowledge_units(db, units)
            db.commit()

        source_file.index_status = "success"
        source_file.processing_stage = "completed"
        source_file.ingest_status = "success"
        source_file.last_ingest_at = datetime.now(UTC)
        source_file.updated_at = datetime.now(UTC)
        db.commit()
    except Exception as exc:
        db.rollback()
        source_file = db.get(SourceFile, source_file_id)
        source_file.ingest_status = "failed"
        source_file.index_status = "failed"
        source_file.error_message = str(exc)
        source_file.updated_at = datetime.now(UTC)
        db.commit()

    return scan_service.get_source_file(db, str(source_file_id))


def run_single_file_enhance(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    document = _resolved_document_for_source_file(db, source_file)
    if document is None:
        return scan_service.get_source_file(db, str(source_file_id))

    try:
        source_file.processing_stage = "extracting"
        source_file.extract_status = "processing"
        source_file.index_status = "queued"
        source_file.ingest_status = "processing"
        source_file.error_message = None
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()

        chunks = list(
            db.scalars(
                select(Chunk).where(Chunk.doc_id == document.id).order_by(Chunk.chunk_no.asc())
            ).all()
        )
        ai_chunk_limit = max(0, settings.ai_extract_chunk_limit)
        parsed_chunks = [
            parser_service.ParsedChunk(
                content=chunk.content,
                chunk_type=chunk.chunk_type,
                title=chunk.title,
                page_no=chunk.page_no,
                metadata=chunk.metadata_json,
            )
            for chunk in chunks
        ]
        ai_chunk_indexes = _select_ai_chunk_indexes(document.doc_type, parsed_chunks, ai_chunk_limit)
        embedding_chunk_limit = max(0, settings.embedding_chunk_limit_per_file)
        embedding_chunk_indexes = _select_embedding_chunk_indexes(document.doc_type, parsed_chunks, embedding_chunk_limit)

        old_units = list(
            db.scalars(
                select(KnowledgeUnit).where(KnowledgeUnit.doc_id == document.id).order_by(KnowledgeUnit.created_at.asc())
            ).all()
        )
        new_units: list[KnowledgeUnit] = []
        ai_done = 0
        for idx, chunk in enumerate(chunks, start=1):
            use_ai = idx in ai_chunk_indexes
            ku = extract_service.build_knowledge_unit(document.doc_type, chunk, use_ai=use_ai)
            new_units.append(ku)
            if use_ai:
                ai_done += 1
            _merge_document_ingest_stats(
                document,
                parsed_chunks=len(chunks),
                parsed_chunk_total=len(chunks),
                parsed_chunk_completed=len(chunks),
                ai_chunk_count=len(ai_chunk_indexes),
                ai_chunk_total=len(ai_chunk_indexes),
                ai_chunk_completed=ai_done,
                embedded_chunk_count=len(embedding_chunk_indexes),
                embedding_chunk_total=len(embedding_chunk_indexes),
                embedding_chunk_completed=0,
            )
            source_file.updated_at = datetime.now(UTC)
            document.updated_at = source_file.updated_at
            db.commit()

        db.query(KnowledgeUnit).filter(KnowledgeUnit.doc_id == document.id).delete()
        db.commit()
        for ku in new_units:
            db.add(ku)
            db.flush()
        db.commit()

        source_file.processing_stage = "indexing"
        source_file.extract_status = "success"
        source_file.index_status = "processing"
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()

        emb_done = 0
        for idx, chunk in enumerate(chunks, start=1):
            if idx in embedding_chunk_indexes:
                embedder_service.embed_chunks(db, [chunk])
                if idx - 1 < len(new_units):
                    embedder_service.embed_knowledge_units(db, [new_units[idx - 1]])
                emb_done += 1
                _merge_document_ingest_stats(
                    document,
                    parsed_chunks=len(chunks),
                    parsed_chunk_total=len(chunks),
                    parsed_chunk_completed=len(chunks),
                    ai_chunk_count=len(ai_chunk_indexes),
                    ai_chunk_total=len(ai_chunk_indexes),
                    ai_chunk_completed=len(ai_chunk_indexes),
                    embedded_chunk_count=len(embedding_chunk_indexes),
                    embedding_chunk_total=len(embedding_chunk_indexes),
                    embedding_chunk_completed=emb_done,
                )
                source_file.updated_at = datetime.now(UTC)
                document.updated_at = source_file.updated_at
                db.commit()

        source_file.index_status = "success"
        source_file.processing_stage = "completed"
        source_file.ingest_status = "success"
        source_file.last_ingest_at = datetime.now(UTC)
        source_file.updated_at = datetime.now(UTC)
        document.updated_at = source_file.updated_at
        db.commit()
    except Exception as exc:
        db.rollback()
        source_file = db.get(SourceFile, source_file_id)
        if source_file is None:
            return None
        current_stage = source_file.processing_stage
        source_file.ingest_status = "failed"
        source_file.error_message = str(exc)
        if current_stage == "extracting":
            source_file.extract_status = "failed"
        else:
            source_file.index_status = "failed"
        source_file.updated_at = datetime.now(UTC)
        db.commit()

    return scan_service.get_source_file(db, str(source_file_id))


def schedule_single_file_ingest(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    source_file.ingest_status = "pending"
    source_file.processing_stage = "discovered"
    source_file.classification_status = "pending"
    source_file.parse_status = "pending"
    source_file.extract_status = "pending"
    source_file.index_status = "pending"
    source_file.duplicate_of_doc_id = None
    source_file.is_exact_duplicate = False
    source_file.is_possible_duplicate = False
    source_file.error_message = None
    source_file.updated_at = datetime.now(UTC)
    db.commit()
    return scan_service.get_source_file(db, str(source_file_id))


def schedule_single_file_reextract(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    source_file.ingest_status = "processing"
    source_file.processing_stage = "extracting"
    source_file.extract_status = "processing"
    source_file.index_status = "pending"
    source_file.error_message = None
    source_file.updated_at = datetime.now(UTC)
    db.commit()
    return scan_service.get_source_file(db, str(source_file_id))


def schedule_single_file_reembed(db: Session, source_file_id: UUID):
    source_file = db.get(SourceFile, source_file_id)
    if source_file is None:
        return None
    source_file.ingest_status = "processing"
    source_file.processing_stage = "indexing"
    source_file.index_status = "processing"
    source_file.error_message = None
    source_file.updated_at = datetime.now(UTC)
    db.commit()
    return scan_service.get_source_file(db, str(source_file_id))


def list_documents(
    db: Session,
    *,
    page: int = 1,
    page_size: int = 20,
    doc_type: str | None = None,
    is_dev_doc: bool | None = None,
    doc_domain: str | None = None,
) -> tuple[list[DocumentOut], int]:
    stmt = select(Document)
    count_stmt = select(func.count(Document.id))

    if doc_type:
        stmt = stmt.where(Document.doc_type == doc_type)
        count_stmt = count_stmt.where(Document.doc_type == doc_type)
    if is_dev_doc is not None:
        stmt = stmt.where(Document.is_dev_doc == is_dev_doc)
        count_stmt = count_stmt.where(Document.is_dev_doc == is_dev_doc)
    if doc_domain:
        stmt = stmt.where(Document.doc_domain == doc_domain)
        count_stmt = count_stmt.where(Document.doc_domain == doc_domain)

    stmt = stmt.order_by(Document.created_at.desc()).offset((page - 1) * page_size).limit(page_size)
    rows = db.scalars(stmt).all()
    total = db.scalar(count_stmt) or 0
    items = [
        DocumentOut(
            id=row.id,
            title=row.title,
            doc_type=row.doc_type,
            source_file=row.source_file,
            source_filename=row.source_filename,
            source_org=row.source_org,
            author=row.author,
            publish_date=row.publish_date.isoformat() if row.publish_date else None,
            effective_date=row.effective_date.isoformat() if row.effective_date else None,
            expiry_date=row.expiry_date.isoformat() if row.expiry_date else None,
            status=row.status,
            language=row.language,
            parse_status=row.parse_status,
            metadata=row.metadata_json,
            is_dev_doc=row.is_dev_doc,
            doc_domain=row.doc_domain,
        )
        for row in rows
    ]
    return items, total
