from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.data_source import BatchIngestJob, DataSource, SourceFile
from app.schemas.data_sources import (
    BatchIngestJobOut,
    DataSourceCreate,
    DataSourceDetailOut,
    DataSourceOut,
)
from app.schemas.enums import JobStatus, SourceStatus

DEFAULT_INCLUDE_PATTERNS = ["*.pdf", "*.doc", "*.docx", "*.xlsx", "*.md"]


def _to_data_source_out(model: DataSource) -> DataSourceOut:
    return DataSourceOut(
        id=model.id,
        source_name=model.source_name,
        source_type=model.source_type,
        root_path=model.root_path,
        include_patterns=model.include_patterns,
        exclude_patterns=model.exclude_patterns,
        recursive=model.recursive,
        status=model.status,
        metadata=model.metadata_json,
        created_at=model.created_at,
        updated_at=model.updated_at,
    )


def create_data_source(db: Session, payload: DataSourceCreate) -> DataSourceOut:
    now = datetime.now(UTC)
    include_patterns = payload.include_patterns or DEFAULT_INCLUDE_PATTERNS
    model = DataSource(
        source_name=payload.source_name,
        source_type=payload.source_type.value,
        root_path=payload.root_path,
        include_patterns=include_patterns,
        exclude_patterns=payload.exclude_patterns,
        recursive=payload.recursive,
        status=SourceStatus.active.value,
        metadata_json=payload.metadata,
        created_at=now,
        updated_at=now,
    )
    db.add(model)
    db.commit()
    db.refresh(model)
    return _to_data_source_out(model)


def get_data_source(db: Session, data_source_id: UUID) -> DataSourceDetailOut | None:
    model = db.get(DataSource, data_source_id)
    if model is None:
        return None

    total_files = db.scalar(
        select(func.count(SourceFile.id)).where(SourceFile.data_source_id == data_source_id)
    ) or 0
    success_files = db.scalar(
        select(func.count(SourceFile.id)).where(
            SourceFile.data_source_id == data_source_id,
            SourceFile.ingest_status == "success",
        )
    ) or 0
    failed_files = db.scalar(
        select(func.count(SourceFile.id)).where(
            SourceFile.data_source_id == data_source_id,
            SourceFile.ingest_status == "failed",
        )
    ) or 0
    pending_files = db.scalar(
        select(func.count(SourceFile.id)).where(
            SourceFile.data_source_id == data_source_id,
            SourceFile.ingest_status.in_(["pending", "queued", "processing"]),
        )
    ) or 0

    last_scan_at = db.scalar(
        select(func.max(SourceFile.last_scan_at)).where(SourceFile.data_source_id == data_source_id)
    )
    last_ingest_at = db.scalar(
        select(func.max(SourceFile.last_ingest_at)).where(SourceFile.data_source_id == data_source_id)
    )

    return DataSourceDetailOut(
        **_to_data_source_out(model).model_dump(),
        total_files=total_files,
        success_files=success_files,
        failed_files=failed_files,
        pending_files=pending_files,
        last_scan_at=last_scan_at,
        last_ingest_at=last_ingest_at,
    )
def start_batch_ingest(db: Session, data_source_id: str) -> BatchIngestJobOut:
    now = datetime.now(UTC)
    job = BatchIngestJob(
        data_source_id=data_source_id,
        trigger_type="manual",
        status=JobStatus.pending.value,
        total_files=0,
        success_files=0,
        failed_files=0,
        skipped_files=0,
        started_at=now,
        finished_at=None,
        created_at=now,
    )
    db.add(job)
    db.commit()
    db.refresh(job)
    return BatchIngestJobOut(
        id=job.id,
        data_source_id=job.data_source_id,
        trigger_type="manual",
        status=JobStatus.pending,
        total_files=job.total_files,
        success_files=job.success_files,
        failed_files=job.failed_files,
        skipped_files=job.skipped_files,
        started_at=job.started_at,
        finished_at=job.finished_at,
    )


def prepare_files_for_ingest(
    db: Session,
    data_source_id: str,
    *,
    mode: str = "incremental",
    reprocess_failed: bool = False,
) -> int:
    rows = db.scalars(
        select(SourceFile).where(
            SourceFile.data_source_id == data_source_id,
            SourceFile.discover_status == "active",
        )
    ).all()

    mode = (mode or "incremental").lower()
    prepared = 0
    for row in rows:
        should_prepare = False
        if mode == "full":
            should_prepare = True
        elif mode == "retry_failed":
            should_prepare = row.ingest_status == "failed"
        elif mode == "resume":
            should_prepare = row.ingest_status in {"failed", "processing", "pending"} or row.processing_stage != "completed"
        else:
            should_prepare = row.ingest_status == "pending" or (reprocess_failed and row.ingest_status == "failed")

        if not should_prepare:
            continue

        row.ingest_status = "pending"
        if mode == "full":
            row.processing_stage = "discovered"
            row.classification_status = "pending"
            row.parse_status = "pending"
            row.extract_status = "pending"
            row.index_status = "pending"
            row.doc_id = None
            row.duplicate_of_doc_id = None
            row.is_exact_duplicate = False
            row.is_possible_duplicate = False
        elif mode == "retry_failed":
            if row.classification_status == "failed":
                row.processing_stage = "discovered"
                row.classification_status = "pending"
            elif row.parse_status == "failed":
                row.processing_stage = "classified"
                row.parse_status = "pending"
            else:
                row.processing_stage = "parsing" if row.parse_status == "success" else "classified"
                row.extract_status = "pending"
        elif mode == "resume":
            if row.processing_stage == "completed":
                continue
            if row.processing_stage == "processing":
                row.processing_stage = "discovered"
        row.error_message = None
        row.updated_at = datetime.now(UTC)
        prepared += 1

    db.commit()
    return prepared


def get_batch_job(db: Session, batch_job_id: UUID) -> BatchIngestJobOut | None:
    job = db.get(BatchIngestJob, batch_job_id)
    if job is None:
        return None
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
