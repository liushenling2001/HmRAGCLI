from __future__ import annotations

from fastapi import HTTPException
from sqlalchemy import case, desc, func, select
from sqlalchemy.orm import Session

from app.models.content import Chunk, KnowledgeUnit
from app.models.data_source import BatchIngestJob, DataSource, ScanJob, SourceFile
from app.models.document import Document, IngestJob
from app.models.embedding import ChunkEmbedding
from app.core.config import settings
from app.services.scan import _normalize_file_error
from app.schemas.operations import (
    OperationsActiveFileOut,
    OperationsDashboardOut,
    OperationsDataSourceCardOut,
    OperationsFailureFileOut,
    OperationsJobOut,
    OperationsOverviewOut,
    OperationsStageProgressOut,
)


def _count_scalar(db: Session, stmt) -> int:
    return int(db.scalar(stmt) or 0)


FAILED_STATUSES = {"failed", "conversion_failed", "enhanced_parser_not_ready"}


def _effective_stage_status(row: SourceFile, attr_name: str) -> str:
    value = getattr(row, attr_name, None) or "pending"
    if value == "skipped":
        return "success"
    if value in {"pending", "queued", "discovered"} and row.ingest_status == "success":
        return "success"
    if attr_name == "classification_status":
        if row.ingest_status == "failed" and value in {"pending", "queued", "discovered"}:
            return "failed"
        return value
    if attr_name == "parse_status":
        if row.classification_status in FAILED_STATUSES:
            return "failed"
        if row.ingest_status == "failed" and value in {"pending", "queued", "discovered"}:
            return "failed"
        return value
    if attr_name == "extract_status":
        if row.classification_status in FAILED_STATUSES or row.parse_status in FAILED_STATUSES:
            return "failed"
        if row.ingest_status == "failed" and value in {"pending", "queued", "discovered"}:
            return "failed"
        return value
    if attr_name == "index_status":
        if (
            row.classification_status in FAILED_STATUSES
            or row.parse_status in FAILED_STATUSES
            or row.extract_status in FAILED_STATUSES
        ):
            return "failed"
        if row.ingest_status == "failed" and value in {"pending", "queued", "discovered"}:
            return "failed"
        return value
    return value


def _build_stage_progress(rows: list[SourceFile], attr_name: str) -> OperationsStageProgressOut:
    total = len(rows)
    pending = processing = success = failed = 0
    for row in rows:
        value = _effective_stage_status(row, attr_name)
        if value == "success":
            success += 1
        elif value in {"processing", "running"}:
            processing += 1
        elif value in FAILED_STATUSES:
            failed += 1
        else:
            pending += 1
    return OperationsStageProgressOut(
        total=total,
        pending=pending,
        processing=processing,
        success=success,
        failed=failed,
    )


def _build_doc_progress(rows: list[SourceFile]) -> OperationsStageProgressOut:
    total = len(rows)
    pending = processing = success = failed = 0
    for row in rows:
        if row.classification_status in FAILED_STATUSES or row.parse_status in FAILED_STATUSES:
            failed += 1
        elif row.doc_id or row.duplicate_of_doc_id or row.classification_status == "success":
            success += 1
        elif row.processing_stage in {"classifying", "parsing"} or row.classification_status in {"processing", "running"} or row.parse_status in {"processing", "running"}:
            processing += 1
        else:
            pending += 1
    return OperationsStageProgressOut(
        total=total,
        pending=pending,
        processing=processing,
        success=success,
        failed=failed,
    )


def _progress_from_task_counts(*, total: int, completed: int, processing: int, failed: int) -> OperationsStageProgressOut:
    return OperationsStageProgressOut(
        total=total,
        pending=max(0, total - completed - processing - failed),
        processing=processing,
        success=completed,
        failed=failed,
    )


def _aggregate_chunk_task_progress(
    rows: list[SourceFile],
    docs_by_id: dict[str, Document],
    *,
    task: str,
    chunk_counts: dict[str, int],
    chunk_embedding_counts: dict[str, int],
) -> OperationsStageProgressOut:
    total = completed = processing = failed = 0
    for row in rows:
        doc_id = str(row.doc_id or row.duplicate_of_doc_id or "")
        doc = docs_by_id.get(doc_id) if doc_id else None
        stats = (doc.metadata_json or {}).get("ingest_stats", {}) if doc else {}

        if task == "ai":
            fallback_total = min(int(chunk_counts.get(doc_id, 0)), max(0, settings.ai_extract_chunk_limit))
            file_total = int(stats.get("ai_chunk_total") or stats.get("ai_chunk_count") or fallback_total)
            stage_status = _effective_stage_status(row, "extract_status")
            file_done = int(
                stats.get("ai_chunk_completed")
                or (file_total if stage_status == "success" else 0)
            )
        else:
            fallback_total = int(chunk_embedding_counts.get(doc_id, 0))
            file_total = int(stats.get("embedding_chunk_total") or stats.get("embedded_chunk_count") or fallback_total)
            stage_status = _effective_stage_status(row, "index_status")
            file_done = int(
                stats.get("embedding_chunk_completed")
                or (file_total if stage_status == "success" else 0)
            )

        file_done = min(file_done, file_total)
        remaining = max(0, file_total - file_done)

        total += file_total
        completed += file_done
        if remaining == 0:
            continue
        if stage_status in FAILED_STATUSES:
            failed += remaining
        elif stage_status in {"processing", "running"}:
            processing += remaining

    return _progress_from_task_counts(
        total=total,
        completed=completed,
        processing=processing,
        failed=failed,
    )


def get_operations_overview(db: Session) -> OperationsOverviewOut:
    return OperationsOverviewOut(
        total_data_sources=_count_scalar(db, select(func.count(DataSource.id))),
        active_data_sources=_count_scalar(
            db, select(func.count(DataSource.id)).where(DataSource.status == "active")
        ),
        total_files=_count_scalar(db, select(func.count(SourceFile.id))),
        active_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.discover_status == "active")
        ),
        pending_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.ingest_status == "pending")
        ),
        processing_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.ingest_status == "processing")
        ),
        success_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.ingest_status == "success")
        ),
        failed_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.ingest_status == "failed")
        ),
        exact_duplicate_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.is_exact_duplicate.is_(True))
        ),
        possible_duplicate_files=_count_scalar(
            db, select(func.count(SourceFile.id)).where(SourceFile.is_possible_duplicate.is_(True))
        ),
        total_documents=_count_scalar(db, select(func.count(Document.id))),
        business_documents=_count_scalar(
            db, select(func.count(Document.id)).where(Document.doc_domain == "business")
        ),
        development_documents=_count_scalar(
            db, select(func.count(Document.id)).where(Document.doc_domain == "development")
        ),
        total_chunks=_count_scalar(db, select(func.count(Chunk.id))),
        total_knowledge_units=_count_scalar(db, select(func.count(KnowledgeUnit.id))),
    )


def list_operations_data_sources(db: Session) -> list[OperationsDataSourceCardOut]:
    data_sources = db.scalars(select(DataSource).order_by(DataSource.created_at.desc())).all()
    source_ids = [row.id for row in data_sources]
    files = db.scalars(select(SourceFile).where(SourceFile.data_source_id.in_(source_ids))).all() if source_ids else []
    by_source: dict[str, list[SourceFile]] = {}
    for row in files:
        by_source.setdefault(str(row.data_source_id), []).append(row)
    doc_ids = {
        (item.doc_id or item.duplicate_of_doc_id)
        for item in files
        if item.doc_id or item.duplicate_of_doc_id
    }
    docs = db.scalars(select(Document).where(Document.id.in_(doc_ids))).all() if doc_ids else []
    docs_by_id = {str(doc.id): doc for doc in docs}
    chunk_counts = {
        str(doc_id): int(total or 0)
        for doc_id, total in db.execute(
            select(Chunk.doc_id, func.count(Chunk.id)).where(Chunk.doc_id.in_(doc_ids)).group_by(Chunk.doc_id)
        ).all()
    } if doc_ids else {}
    chunk_embedding_counts = {
        str(doc_id): int(total or 0)
        for doc_id, total in db.execute(
            select(Chunk.doc_id, func.count(ChunkEmbedding.id))
            .join(ChunkEmbedding, ChunkEmbedding.chunk_id == Chunk.id)
            .where(Chunk.doc_id.in_(doc_ids))
            .group_by(Chunk.doc_id)
        ).all()
    } if doc_ids else {}

    result: list[OperationsDataSourceCardOut] = []
    for row in data_sources:
        source_rows = by_source.get(str(row.id), [])
        build_progress = _build_doc_progress(source_rows)
        extraction_progress = _build_stage_progress(source_rows, "extract_status")
        ai_progress = _aggregate_chunk_task_progress(
            source_rows,
            docs_by_id,
            task="ai",
            chunk_counts=chunk_counts,
            chunk_embedding_counts=chunk_embedding_counts,
        )
        vector_progress = _aggregate_chunk_task_progress(
            source_rows,
            docs_by_id,
            task="vector",
            chunk_counts=chunk_counts,
            chunk_embedding_counts=chunk_embedding_counts,
        )
        result.append(
            OperationsDataSourceCardOut(
                id=row.id,
                source_name=row.source_name,
                source_type=row.source_type,
                root_path=row.root_path,
                status=row.status,
                total_files=len(source_rows),
                pending_files=sum(1 for item in source_rows if item.ingest_status == "pending"),
                processing_files=sum(1 for item in source_rows if item.ingest_status == "processing"),
                success_files=sum(1 for item in source_rows if item.ingest_status == "success"),
                failed_files=sum(1 for item in source_rows if item.ingest_status == "failed"),
                exact_duplicate_files=sum(1 for item in source_rows if item.is_exact_duplicate),
                build_progress=build_progress,
                extraction_progress=extraction_progress,
                ai_progress=ai_progress,
                vector_progress=vector_progress,
                classify_progress=_build_stage_progress(source_rows, "classification_status"),
                parse_progress=_build_stage_progress(source_rows, "parse_status"),
                extract_progress=_build_stage_progress(source_rows, "extract_status"),
                index_progress=_build_stage_progress(source_rows, "index_status"),
                last_scan_at=max((item.last_scan_at for item in source_rows if item.last_scan_at), default=None),
                last_ingest_at=max((item.last_ingest_at for item in source_rows if item.last_ingest_at), default=None),
            )
        )
    return result


def list_recent_operations_jobs(
    db: Session,
    *,
    job_kind: str | None = None,
    status: str | None = None,
    data_source_id: str | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[OperationsJobOut], int]:
    source_progress = {str(item.id): item for item in list_operations_data_sources(db)}
    processing_by_source = {
        str(row.data_source_id): row
        for row in db.execute(
            select(
                SourceFile.data_source_id,
                func.sum(case((SourceFile.ingest_status == "processing", 1), else_=0)).label("running_files"),
                func.sum(case((SourceFile.ingest_status == "pending", 1), else_=0)).label("queued_files"),
                func.string_agg(
                    case(
                        (SourceFile.ingest_status == "processing", SourceFile.processing_stage),
                        else_=None,
                    ),
                    ", ",
                ).label("stages"),
            )
            .group_by(SourceFile.data_source_id)
        ).all()
    }

    scan_stmt = (
        select(
            ScanJob.id,
            ScanJob.data_source_id,
            DataSource.source_name,
            ScanJob.status,
            ScanJob.total_files,
            ScanJob.new_files,
            ScanJob.changed_files,
            ScanJob.missing_files,
            ScanJob.started_at,
            ScanJob.finished_at,
            ScanJob.created_at,
        )
        .join(DataSource, DataSource.id == ScanJob.data_source_id)
    )
    batch_stmt = (
        select(
            BatchIngestJob.id,
            BatchIngestJob.data_source_id,
            DataSource.source_name,
            BatchIngestJob.status,
            BatchIngestJob.total_files,
            BatchIngestJob.success_files,
            BatchIngestJob.failed_files,
            BatchIngestJob.skipped_files,
            BatchIngestJob.started_at,
            BatchIngestJob.finished_at,
            BatchIngestJob.created_at,
        )
        .join(DataSource, DataSource.id == BatchIngestJob.data_source_id)
    )

    if status:
        scan_stmt = scan_stmt.where(ScanJob.status == status)
        batch_stmt = batch_stmt.where(BatchIngestJob.status == status)
    if data_source_id:
        scan_stmt = scan_stmt.where(ScanJob.data_source_id == data_source_id)
        batch_stmt = batch_stmt.where(BatchIngestJob.data_source_id == data_source_id)

    scan_rows = db.execute(scan_stmt.order_by(desc(ScanJob.created_at)).limit(page_size * max(page, 1))).all()
    batch_rows = db.execute(batch_stmt.order_by(desc(BatchIngestJob.created_at)).limit(page_size * max(page, 1))).all()
    enhance_stmt = (
        select(
            IngestJob.id,
            IngestJob.data_source_id,
            DataSource.source_name,
            IngestJob.source_file_id,
            SourceFile.file_name,
            IngestJob.stage,
            IngestJob.status,
            IngestJob.progress_total,
            IngestJob.progress_completed,
            IngestJob.error_message,
            IngestJob.started_at,
            IngestJob.finished_at,
            IngestJob.created_at,
        )
        .join(DataSource, DataSource.id == IngestJob.data_source_id, isouter=True)
        .join(SourceFile, SourceFile.id == IngestJob.source_file_id, isouter=True)
        .where(IngestJob.job_type.in_(["enhance_extract", "enhance_embed"]))
    )
    if status:
        enhance_stmt = enhance_stmt.where(IngestJob.status == status)
    if data_source_id:
        enhance_stmt = enhance_stmt.where(IngestJob.data_source_id == data_source_id)
    enhance_rows = db.execute(enhance_stmt.order_by(desc(IngestJob.created_at)).limit(page_size * max(page, 1))).all()

    jobs: list[tuple] = []
    if job_kind in {None, "scan"}:
        for row in scan_rows:
            jobs.append(
                (
                    row.created_at,
                    OperationsJobOut(
                        id=row.id,
                        job_kind="scan",
                        data_source_id=row.data_source_id,
                        data_source_name=row.source_name,
                        status=row.status,
                        total_files=int(row.total_files or 0),
                        new_files=int(row.new_files or 0),
                        changed_files=int(row.changed_files or 0),
                        missing_files=int(row.missing_files or 0),
                        processed_files=int(row.total_files or 0) if row.status == "success" else None,
                        queued_files=None,
                        running_files=None,
                        progress_percent=100.0 if row.status == "success" else None,
                        current_stage_summary="scanning" if row.status == "running" else None,
                        build_progress=source_progress.get(str(row.data_source_id)).build_progress if source_progress.get(str(row.data_source_id)) else None,
                        extraction_progress=source_progress.get(str(row.data_source_id)).extraction_progress if source_progress.get(str(row.data_source_id)) else None,
                        ai_progress=source_progress.get(str(row.data_source_id)).ai_progress if source_progress.get(str(row.data_source_id)) else None,
                        vector_progress=source_progress.get(str(row.data_source_id)).vector_progress if source_progress.get(str(row.data_source_id)) else None,
                        classify_progress=source_progress.get(str(row.data_source_id)).classify_progress if source_progress.get(str(row.data_source_id)) else None,
                        parse_progress=source_progress.get(str(row.data_source_id)).parse_progress if source_progress.get(str(row.data_source_id)) else None,
                        extract_progress=source_progress.get(str(row.data_source_id)).extract_progress if source_progress.get(str(row.data_source_id)) else None,
                        index_progress=source_progress.get(str(row.data_source_id)).index_progress if source_progress.get(str(row.data_source_id)) else None,
                        started_at=row.started_at,
                        finished_at=row.finished_at,
                    ),
                )
            )
    if job_kind in {None, "ingest"}:
        for row in batch_rows:
            progress = processing_by_source.get(str(row.data_source_id))
            source_card = source_progress.get(str(row.data_source_id))
            build_progress = source_card.build_progress if source_card else None
            total_files = int(build_progress.total if build_progress else (row.total_files or 0))
            build_success = int(build_progress.success if build_progress else (row.success_files or 0))
            build_failed = int(build_progress.failed if build_progress else (row.failed_files or 0))
            build_processing = int(build_progress.processing if build_progress else 0)
            build_pending = int(build_progress.pending if build_progress else 0)
            skipped_files = int(row.skipped_files or 0)
            processed_files = min(total_files, build_success + build_failed + skipped_files)
            progress_percent = (processed_files / total_files * 100.0) if total_files > 0 else 0.0
            effective_status = row.status
            remaining_files = max(0, total_files - processed_files - build_processing)
            if total_files > 0 and build_processing == 0 and remaining_files == 0:
                effective_status = "partial_failed" if build_failed > 0 else "success"
            elif build_processing > 0:
                effective_status = "running"
            elif remaining_files > 0:
                effective_status = "pending"
            jobs.append(
                (
                    row.created_at,
                    OperationsJobOut(
                        id=row.id,
                        job_kind="ingest",
                        data_source_id=row.data_source_id,
                        data_source_name=row.source_name,
                        status=effective_status,
                        total_files=total_files,
                        success_files=build_success,
                        failed_files=build_failed,
                        skipped_files=skipped_files,
                        processed_files=processed_files,
                        queued_files=remaining_files,
                        running_files=build_processing,
                        progress_percent=progress_percent,
                        current_stage_summary=(
                            f"基础建库完成 {processed_files}/{total_files}"
                            if total_files > 0 and build_processing == 0 and remaining_files == 0
                            else (progress.stages or "")[:120] if progress and progress.stages
                            else None
                        ),
                        build_progress=source_card.build_progress if source_card else None,
                        extraction_progress=source_card.extraction_progress if source_card else None,
                        ai_progress=source_card.ai_progress if source_card else None,
                        vector_progress=source_card.vector_progress if source_card else None,
                        classify_progress=source_card.classify_progress if source_card else None,
                        parse_progress=source_card.parse_progress if source_card else None,
                        extract_progress=source_card.extract_progress if source_card else None,
                        index_progress=source_card.index_progress if source_card else None,
                        started_at=row.started_at,
                        finished_at=row.finished_at,
                    ),
                )
            )
    if job_kind in {None, "enhance", "enhance_extract", "enhance_embed"}:
        for row in enhance_rows:
            stage = row.stage or "enhance"
            if job_kind not in {None, "enhance", stage}:
                continue
            total_units = int(row.progress_total or 0)
            completed_units = int(row.progress_completed or 0)
            jobs.append(
                (
                    row.created_at,
                    OperationsJobOut(
                        id=row.id,
                        job_kind=stage,
                        data_source_id=row.data_source_id,
                        data_source_name=row.source_name,
                        source_file_id=row.source_file_id,
                        source_file_name=row.file_name,
                        status=row.status,
                        total_files=max(total_units, 1 if total_units == 0 else total_units),
                        processed_files=completed_units,
                        queued_files=max(0, total_units - completed_units) if row.status == "pending" else 0,
                        running_files=max(0, total_units - completed_units) if row.status == "running" else 0,
                        progress_percent=(completed_units / total_units * 100.0) if total_units > 0 else (100.0 if row.status == "success" else 0.0),
                        current_stage_summary=stage,
                        started_at=row.started_at,
                        finished_at=row.finished_at,
                    ),
                )
            )
    jobs.sort(key=lambda item: item[0], reverse=True)
    total = len(jobs)
    start = (max(page, 1) - 1) * page_size
    end = start + page_size
    return [item for _, item in jobs[start:end]], total


def list_active_processing_files(db: Session, limit: int = 12) -> list[OperationsActiveFileOut]:
    rows = db.execute(
        select(
            SourceFile.id,
            SourceFile.data_source_id,
            DataSource.source_name,
            SourceFile.file_name,
            SourceFile.file_ext,
            SourceFile.ingest_status,
            SourceFile.processing_stage,
            SourceFile.parse_status,
            SourceFile.extract_status,
            SourceFile.index_status,
            SourceFile.updated_at,
        )
        .join(DataSource, DataSource.id == SourceFile.data_source_id)
        .where(SourceFile.ingest_status == "processing")
        .order_by(desc(SourceFile.updated_at))
        .limit(limit)
    ).all()
    return [
        OperationsActiveFileOut(
            id=row.id,
            data_source_id=row.data_source_id,
            data_source_name=row.source_name,
            file_name=row.file_name,
            file_ext=row.file_ext,
            ingest_status=row.ingest_status,
            processing_stage=row.processing_stage,
            parse_status=row.parse_status,
            extract_status=row.extract_status,
            index_status=row.index_status,
            updated_at=row.updated_at,
        )
        for row in rows
    ]


def list_recent_failure_files(
    db: Session,
    *,
    data_source_id: str | None = None,
    file_ext: str | None = None,
    parse_status: str | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[OperationsFailureFileOut], int]:
    stmt = (
        select(
            SourceFile.id,
            SourceFile.data_source_id,
            DataSource.source_name,
            SourceFile.doc_id,
            SourceFile.file_name,
            SourceFile.file_path,
            SourceFile.file_ext,
            SourceFile.ingest_status,
            SourceFile.processing_stage,
            SourceFile.classification_status,
            SourceFile.parse_status,
            SourceFile.extract_status,
            SourceFile.index_status,
            SourceFile.retry_count,
            SourceFile.error_message,
            SourceFile.updated_at,
        )
        .join(DataSource, DataSource.id == SourceFile.data_source_id)
        .where(SourceFile.ingest_status == "failed")
    )
    count_stmt = (
        select(func.count(SourceFile.id))
        .join(DataSource, DataSource.id == SourceFile.data_source_id)
        .where(SourceFile.ingest_status == "failed")
    )
    if data_source_id:
        stmt = stmt.where(SourceFile.data_source_id == data_source_id)
        count_stmt = count_stmt.where(SourceFile.data_source_id == data_source_id)
    if file_ext:
        normalized_ext = file_ext if file_ext.startswith(".") else f".{file_ext}"
        stmt = stmt.where(SourceFile.file_ext == normalized_ext.lower())
        count_stmt = count_stmt.where(SourceFile.file_ext == normalized_ext.lower())
    if parse_status:
        stmt = stmt.where(SourceFile.parse_status == parse_status)
        count_stmt = count_stmt.where(SourceFile.parse_status == parse_status)

    rows = db.execute(
        stmt.order_by(desc(SourceFile.updated_at)).offset((max(page, 1) - 1) * page_size).limit(page_size)
    ).all()
    total = int(db.scalar(count_stmt) or 0)
    items = [
        OperationsFailureFileOut(
            id=row.id,
            data_source_id=row.data_source_id,
            data_source_name=row.source_name,
            doc_id=row.doc_id,
            file_name=row.file_name,
            file_path=row.file_path,
            file_ext=row.file_ext,
            ingest_status=row.ingest_status,
            processing_stage=row.processing_stage,
            classification_status=row.classification_status,
            parse_status=row.parse_status,
            extract_status=row.extract_status,
            index_status=row.index_status,
            retry_count=int(row.retry_count or 0),
            error_stage=_normalize_file_error(row)[0],
            error_summary=_normalize_file_error(row)[1],
            error_detail=_normalize_file_error(row)[2],
            error_message=row.error_message,
            updated_at=row.updated_at,
        )
        for row in rows
    ]
    return items, total


def get_operations_dashboard(db: Session) -> OperationsDashboardOut:
    recent_jobs, _jobs_total = list_recent_operations_jobs(db, page=1, page_size=10)
    recent_failures, _failures_total = list_recent_failure_files(db, page=1, page_size=20)
    return OperationsDashboardOut(
        overview=get_operations_overview(db),
        data_sources=list_operations_data_sources(db),
        active_files=list_active_processing_files(db),
        recent_jobs=recent_jobs,
        recent_failures=recent_failures,
    )


def delete_operations_job(db: Session, *, job_kind: str, job_id: str) -> tuple[str, str]:
    if job_kind == "scan":
        job = db.get(ScanJob, job_id)
    elif job_kind == "ingest":
        job = db.get(BatchIngestJob, job_id)
    else:
        raise HTTPException(status_code=400, detail="Unsupported job_kind")

    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")

    db.delete(job)
    db.commit()
    return job_kind, str(job_id)
