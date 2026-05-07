from datetime import datetime
from uuid import UUID

from pydantic import Field

from app.schemas.common import APIModel
from app.schemas.enums import JobStatus, SourceStatus, SourceType


class DataSourceCreate(APIModel):
    source_name: str = Field(..., max_length=255)
    source_type: SourceType
    root_path: str = Field(..., max_length=2000)
    include_patterns: list[str] = Field(default_factory=list)
    exclude_patterns: list[str] = Field(default_factory=list)
    recursive: bool = True
    metadata: dict = Field(default_factory=dict)


class DataSourceUpdate(APIModel):
    source_name: str | None = None
    include_patterns: list[str] | None = None
    exclude_patterns: list[str] | None = None
    recursive: bool | None = None
    status: SourceStatus | None = None
    metadata: dict | None = None


class DataSourceOut(APIModel):
    id: UUID
    source_name: str
    source_type: SourceType
    root_path: str
    include_patterns: list[str]
    exclude_patterns: list[str]
    recursive: bool
    status: SourceStatus
    metadata: dict
    created_at: datetime
    updated_at: datetime


class DataSourceDetailOut(DataSourceOut):
    total_files: int = 0
    success_files: int = 0
    failed_files: int = 0
    pending_files: int = 0
    last_scan_at: datetime | None = None
    last_ingest_at: datetime | None = None


class SourceFileOut(APIModel):
    id: UUID
    data_source_id: UUID
    file_path: str
    relative_path: str | None = None
    file_name: str
    file_ext: str | None = None
    file_size: int | None = None
    mtime: datetime | None = None
    file_hash: str | None = None
    discover_status: str
    ingest_status: str
    processing_stage: str
    classification_status: str
    parse_status: str
    extract_status: str
    index_status: str
    retry_count: int
    duplicate_of_doc_id: UUID | None = None
    is_exact_duplicate: bool
    is_possible_duplicate: bool
    last_scan_at: datetime | None = None
    last_ingest_at: datetime | None = None
    error_message: str | None = None
    error_stage: str | None = None
    error_summary: str | None = None
    error_detail: str | None = None
    build_ready: bool = False
    available_for_search: bool = False
    enhance_failed: bool = False
    lifecycle_status: str = "pending"
    lifecycle_label: str = "待处理"
    doc_id: UUID | None = None
    parsed_chunk_total: int = 0
    parsed_chunk_completed: int = 0
    ai_chunk_total: int = 0
    ai_chunk_completed: int = 0
    embedding_chunk_total: int = 0
    embedding_chunk_completed: int = 0


class ScanStartRequest(APIModel):
    force_rescan: bool = False


class ScanJobOut(APIModel):
    id: UUID
    data_source_id: UUID
    status: JobStatus
    total_files: int
    new_files: int
    changed_files: int
    missing_files: int
    started_at: datetime | None = None
    finished_at: datetime | None = None


class BatchIngestStartRequest(APIModel):
    mode: str = "incremental"
    reprocess_failed: bool = False
    force_rebuild_index: bool = False


class BatchIngestJobOut(APIModel):
    id: UUID
    data_source_id: UUID
    trigger_type: str
    status: JobStatus
    total_files: int
    success_files: int
    failed_files: int
    skipped_files: int
    started_at: datetime | None = None
    finished_at: datetime | None = None
