from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db.session import SessionLocal, get_db
from app.schemas.common import PageResult
from app.schemas.data_sources import (
    BatchIngestJobOut,
    BatchIngestStartRequest,
    DataSourceCreate,
    DataSourceDetailOut,
    DataSourceOut,
    ScanJobOut,
    ScanStartRequest,
    SourceFileOut,
)
from app.services import data_sources as service
from app.services import ingest as ingest_service
from app.services import scan as scan_service

router = APIRouter()


def _run_scan_background(scan_job_id: str, force_rescan: bool) -> None:
    db = SessionLocal()
    try:
        scan_service.execute_scan_job(db, scan_job_id, force_rescan=force_rescan)
    finally:
        db.close()


def _run_batch_ingest_background(batch_job_id: UUID) -> None:
    db = SessionLocal()
    try:
        ingest_service.run_batch_ingest(db, batch_job_id)
    finally:
        db.close()


def _run_single_reingest_background(source_file_id: UUID) -> None:
    db = SessionLocal()
    try:
        ingest_service.run_single_file_ingest(db, source_file_id)
    finally:
        db.close()


def _run_single_reextract_background(source_file_id: UUID) -> None:
    db = SessionLocal()
    try:
        ingest_service.run_single_file_reextract(db, source_file_id)
    finally:
        db.close()


def _run_single_reembed_background(source_file_id: UUID) -> None:
    db = SessionLocal()
    try:
        ingest_service.run_single_file_reembed(db, source_file_id)
    finally:
        db.close()


@router.post("", response_model=DataSourceOut)
def create_data_source(payload: DataSourceCreate, db: Session = Depends(get_db)) -> DataSourceOut:
    return service.create_data_source(db, payload)


@router.get("/{data_source_id}", response_model=DataSourceDetailOut)
def get_data_source(data_source_id: UUID, db: Session = Depends(get_db)) -> DataSourceDetailOut:
    result = service.get_data_source(db, data_source_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Data source not found")
    return result


@router.post("/{data_source_id}/scan", response_model=ScanJobOut)
def start_scan(
    data_source_id: UUID,
    payload: ScanStartRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> ScanJobOut:
    data_source = service.get_data_source(db, data_source_id)
    if data_source is None:
        raise HTTPException(status_code=404, detail="Data source not found")
    placeholder = scan_service.create_scan_job(db, str(data_source_id))
    background_tasks.add_task(_run_scan_background, str(placeholder.id), payload.force_rescan)
    return placeholder


@router.post("/{data_source_id}/ingest", response_model=BatchIngestJobOut)
def start_batch_ingest(
    data_source_id: UUID,
    payload: BatchIngestStartRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> BatchIngestJobOut:
    service.prepare_files_for_ingest(
        db,
        str(data_source_id),
        mode=payload.mode,
        reprocess_failed=payload.reprocess_failed,
    )
    job = service.start_batch_ingest(db, str(data_source_id))
    background_tasks.add_task(_run_batch_ingest_background, job.id)
    return job


@router.post("/{data_source_id}/retry-failed", response_model=BatchIngestJobOut)
def retry_failed_batch_ingest(
    data_source_id: UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> BatchIngestJobOut:
    data_source = service.get_data_source(db, data_source_id)
    if data_source is None:
        raise HTTPException(status_code=404, detail="Data source not found")
    service.prepare_files_for_ingest(
        db,
        str(data_source_id),
        mode="retry_failed",
        reprocess_failed=True,
    )
    job = service.start_batch_ingest(db, str(data_source_id))
    background_tasks.add_task(_run_batch_ingest_background, job.id)
    return job


@router.get("/{data_source_id}/files", response_model=PageResult[SourceFileOut])
def list_source_files(
    data_source_id: UUID,
    parse_status: str | None = None,
    ingest_status: str | None = None,
    file_ext: str | None = None,
    is_exact_duplicate: bool | None = None,
    build_ready: bool | None = None,
    ai_pending: bool | None = None,
    vector_pending: bool | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[SourceFileOut]:
    items, total = scan_service.list_source_files(
        db,
        str(data_source_id),
        parse_status=parse_status,
        ingest_status=ingest_status,
        file_ext=file_ext,
        is_exact_duplicate=is_exact_duplicate,
        build_ready=build_ready,
        ai_pending=ai_pending,
        vector_pending=vector_pending,
        page=page,
        page_size=page_size,
    )
    return PageResult(items=items, total=total, page=page, page_size=page_size)


@router.get("/file-status/{source_file_id}", response_model=SourceFileOut)
def get_source_file_status(source_file_id: UUID, db: Session = Depends(get_db)) -> SourceFileOut:
    result = scan_service.get_source_file(db, str(source_file_id))
    if result is None:
        raise HTTPException(status_code=404, detail="Source file not found")
    return result


@router.get("/batch-jobs/{batch_job_id}", response_model=BatchIngestJobOut)
def get_batch_job(batch_job_id: UUID, db: Session = Depends(get_db)) -> BatchIngestJobOut:
    result = service.get_batch_job(db, batch_job_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Batch job not found")
    return result


@router.post("/batch-jobs/{batch_job_id}/retry-failed", response_model=BatchIngestJobOut)
def retry_failed_files(
    batch_job_id: UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> BatchIngestJobOut:
    batch_job = service.get_batch_job(db, batch_job_id)
    if batch_job is None:
        raise HTTPException(status_code=404, detail="Batch job not found")
    service.prepare_files_for_ingest(
        db,
        str(batch_job.data_source_id),
        mode="retry_failed",
        reprocess_failed=True,
    )
    new_job = service.start_batch_ingest(db, str(batch_job.data_source_id))
    background_tasks.add_task(_run_batch_ingest_background, new_job.id)
    return new_job


@router.post("/files/{source_file_id}/reingest", response_model=SourceFileOut)
def reingest_source_file(
    source_file_id: UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> SourceFileOut:
    result = ingest_service.schedule_single_file_ingest(db, source_file_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Source file not found")
    background_tasks.add_task(_run_single_reingest_background, source_file_id)
    return result


@router.post("/files/{source_file_id}/reextract", response_model=SourceFileOut)
def reextract_source_file(
    source_file_id: UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> SourceFileOut:
    result = ingest_service.schedule_single_file_reextract(db, source_file_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Source file not found")
    background_tasks.add_task(_run_single_reextract_background, source_file_id)
    return result


@router.post("/files/{source_file_id}/reembed", response_model=SourceFileOut)
def reembed_source_file(
    source_file_id: UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> SourceFileOut:
    result = ingest_service.schedule_single_file_reembed(db, source_file_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Source file not found")
    background_tasks.add_task(_run_single_reembed_background, source_file_id)
    return result
