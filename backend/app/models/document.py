from datetime import date, datetime
from uuid import uuid4

from sqlalchemy import Date, DateTime, ForeignKey, String
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class Document(Base):
    __tablename__ = "documents"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    title: Mapped[str] = mapped_column(String(500), nullable=False)
    doc_type: Mapped[str] = mapped_column(String(50), nullable=False)
    source_file: Mapped[str] = mapped_column(String(1000), nullable=False)
    source_filename: Mapped[str | None] = mapped_column(String(500))
    source_org: Mapped[str | None] = mapped_column(String(255))
    author: Mapped[str | None] = mapped_column(String(255))
    publish_date: Mapped[date | None] = mapped_column(Date)
    effective_date: Mapped[date | None] = mapped_column(Date)
    expiry_date: Mapped[date | None] = mapped_column(Date)
    status: Mapped[str] = mapped_column(String(50), nullable=False, default="reference")
    language: Mapped[str] = mapped_column(String(20), nullable=False, default="zh")
    file_hash: Mapped[str | None] = mapped_column(String(128))
    canonical_doc_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True))
    is_canonical: Mapped[bool] = mapped_column(nullable=False, default=True)
    duplicate_count: Mapped[int] = mapped_column(nullable=False, default=0)
    is_dev_doc: Mapped[bool] = mapped_column(nullable=False, default=False)
    doc_domain: Mapped[str] = mapped_column(String(50), nullable=False, default="business")
    parse_status: Mapped[str] = mapped_column(String(50), nullable=False, default="pending")
    metadata_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)


class IngestJob(Base):
    __tablename__ = "ingest_jobs"

    id: Mapped[str] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)
    doc_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True), ForeignKey("documents.id", ondelete="CASCADE"))
    source_file_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True), ForeignKey("source_files.id", ondelete="CASCADE"))
    data_source_id: Mapped[str | None] = mapped_column(UUID(as_uuid=True), ForeignKey("data_sources.id", ondelete="CASCADE"))
    job_type: Mapped[str] = mapped_column(String(50), nullable=False)
    stage: Mapped[str | None] = mapped_column(String(50))
    status: Mapped[str] = mapped_column(String(50), nullable=False)
    progress_total: Mapped[int] = mapped_column(nullable=False, default=0)
    progress_completed: Mapped[int] = mapped_column(nullable=False, default=0)
    payload_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    attempt_count: Mapped[int] = mapped_column(nullable=False, default=0)
    error_message: Mapped[str | None] = mapped_column(String)
    started_at: Mapped[datetime | None] = mapped_column(DateTime)
    heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
