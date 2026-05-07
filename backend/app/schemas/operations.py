from datetime import datetime
from uuid import UUID

from app.schemas.common import APIModel


class OperationsOverviewOut(APIModel):
    total_data_sources: int
    active_data_sources: int
    total_files: int
    active_files: int
    pending_files: int
    processing_files: int
    success_files: int
    failed_files: int
    exact_duplicate_files: int
    possible_duplicate_files: int
    total_documents: int
    business_documents: int
    development_documents: int
    total_chunks: int
    total_knowledge_units: int


class OperationsStageProgressOut(APIModel):
    total: int = 0
    pending: int = 0
    processing: int = 0
    success: int = 0
    failed: int = 0


class OperationsDataSourceCardOut(APIModel):
    id: UUID
    source_name: str
    source_type: str
    root_path: str
    status: str
    total_files: int
    pending_files: int
    processing_files: int
    success_files: int
    failed_files: int
    exact_duplicate_files: int
    build_progress: OperationsStageProgressOut
    extraction_progress: OperationsStageProgressOut
    ai_progress: OperationsStageProgressOut
    vector_progress: OperationsStageProgressOut
    classify_progress: OperationsStageProgressOut
    parse_progress: OperationsStageProgressOut
    extract_progress: OperationsStageProgressOut
    index_progress: OperationsStageProgressOut
    last_scan_at: datetime | None = None
    last_ingest_at: datetime | None = None


class OperationsJobOut(APIModel):
    id: UUID
    job_kind: str
    data_source_id: UUID
    data_source_name: str | None = None
    source_file_id: UUID | None = None
    source_file_name: str | None = None
    status: str
    total_files: int
    success_files: int | None = None
    failed_files: int | None = None
    skipped_files: int | None = None
    new_files: int | None = None
    changed_files: int | None = None
    missing_files: int | None = None
    processed_files: int | None = None
    queued_files: int | None = None
    running_files: int | None = None
    progress_percent: float | None = None
    current_stage_summary: str | None = None
    build_progress: OperationsStageProgressOut | None = None
    extraction_progress: OperationsStageProgressOut | None = None
    ai_progress: OperationsStageProgressOut | None = None
    vector_progress: OperationsStageProgressOut | None = None
    classify_progress: OperationsStageProgressOut | None = None
    parse_progress: OperationsStageProgressOut | None = None
    extract_progress: OperationsStageProgressOut | None = None
    index_progress: OperationsStageProgressOut | None = None
    started_at: datetime | None = None
    finished_at: datetime | None = None


class OperationsFailureFileOut(APIModel):
    id: UUID
    data_source_id: UUID
    data_source_name: str | None = None
    doc_id: UUID | None = None
    file_name: str
    file_path: str
    file_ext: str | None = None
    ingest_status: str
    processing_stage: str
    parse_status: str
    extract_status: str
    index_status: str
    retry_count: int
    error_stage: str | None = None
    error_summary: str | None = None
    error_detail: str | None = None
    error_message: str | None = None
    updated_at: datetime


class OperationsActiveFileOut(APIModel):
    id: UUID
    data_source_id: UUID
    data_source_name: str | None = None
    file_name: str
    file_ext: str | None = None
    ingest_status: str
    processing_stage: str
    parse_status: str
    extract_status: str
    index_status: str
    updated_at: datetime


class OperationsDashboardOut(APIModel):
    overview: OperationsOverviewOut
    data_sources: list[OperationsDataSourceCardOut]
    active_files: list[OperationsActiveFileOut]
    recent_jobs: list[OperationsJobOut]
    recent_failures: list[OperationsFailureFileOut]


class OperationsJobDeleteOut(APIModel):
    id: UUID
    job_kind: str
    deleted: bool
