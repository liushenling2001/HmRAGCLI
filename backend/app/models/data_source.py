from datetime import datetime
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class DataSource(Base):
    __tablename__ = "data_sources"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    source_name: Mapped[str] = mapped_column(String(255), nullable=False)
    source_type: Mapped[str] = mapped_column(String(50), nullable=False)
    root_path: Mapped[str] = mapped_column(String(2000), nullable=False, unique=True)
    include_patterns: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    exclude_patterns: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    recursive: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    status: Mapped[str] = mapped_column(String(50), nullable=False, default="active")
    metadata_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)

    source_files: Mapped[list["SourceFile"]] = relationship(
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    scan_jobs: Mapped[list["ScanJob"]] = relationship(
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    batch_ingest_jobs: Mapped[list["BatchIngestJob"]] = relationship(
        back_populates="data_source",
        cascade="all, delete-orphan",
    )


class SourceFile(Base):
    __tablename__ = "source_files"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    data_source_id: Mapped[str] = mapped_column(UUID(as_uuid=True), ForeignKey("data_sources.id", ondelete="CASCADE"))
    file_path: Mapped[str] = mapped_column(String(2000), nullable=False)
    relative_path: Mapped[str | None] = mapped_column(String(1000))
    file_name: Mapped[str] = mapped_column(String(500), nullable=False)
    file_ext: Mapped[str | None] = mapped_column(String(20))
    file_size: Mapped[int | None]
    mtime: Mapped[datetime | None] = mapped_column(DateTime)
    file_hash: Mapped[str | None] = mapped_column(String(128))
    discover_status: Mapped[str] = mapped_column(String(50), nullable=False, default="active")
    ingest_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    processing_stage: Mapped[str] = mapped_column(String(50), nullable=False, default="discovered")
    classification_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    parse_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    extract_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    index_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    duplicate_of_doc_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True))
    is_exact_duplicate: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_possible_duplicate: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    last_scan_at: Mapped[datetime | None] = mapped_column(DateTime)
    last_ingest_at: Mapped[datetime | None] = mapped_column(DateTime)
    error_message: Mapped[str | None] = mapped_column(Text)
    doc_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)

    data_source: Mapped[DataSource] = relationship(back_populates="source_files")


class ScanJob(Base):
    __tablename__ = "scan_jobs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    data_source_id: Mapped[str] = mapped_column(UUID(as_uuid=True), ForeignKey("data_sources.id", ondelete="CASCADE"))
    status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    total_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    new_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    changed_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    missing_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    started_at: Mapped[datetime | None] = mapped_column(DateTime)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)

    data_source: Mapped[DataSource] = relationship(back_populates="scan_jobs")


class BatchIngestJob(Base):
    __tablename__ = "batch_ingest_jobs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    data_source_id: Mapped[str] = mapped_column(UUID(as_uuid=True), ForeignKey("data_sources.id", ondelete="CASCADE"))
    trigger_type: Mapped[str] = mapped_column(String(50), nullable=False)
    status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    total_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    success_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    failed_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    skipped_files: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    started_at: Mapped[datetime | None] = mapped_column(DateTime)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)

    data_source: Mapped[DataSource] = relationship(back_populates="batch_ingest_jobs")
