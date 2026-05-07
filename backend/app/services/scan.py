from __future__ import annotations

import fnmatch
import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.content import Chunk
from app.models.data_source import DataSource, ScanJob, SourceFile
from app.models.document import Document
from app.models.embedding import ChunkEmbedding
from app.schemas.data_sources import ScanJobOut, SourceFileOut
from app.schemas.enums import JobStatus


@dataclass
class ScanCounters:
    total_files: int = 0
    new_files: int = 0
    changed_files: int = 0
    missing_files: int = 0


def _normalize_patterns(patterns: list[str]) -> list[str]:
    return patterns or ["*"]


def _match_name(name: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(name, pattern) for pattern in patterns)


def _should_include(path: Path, include_patterns: list[str], exclude_patterns: list[str]) -> bool:
    name = path.name
    if name.startswith("~$"):
        return False
    if not _match_name(name, _normalize_patterns(include_patterns)):
        return False
    if exclude_patterns and _match_name(name, exclude_patterns):
        return False
    return True


def _iter_files(root: Path, recursive: bool, include_patterns: list[str], exclude_patterns: list[str]) -> list[Path]:
    if recursive:
        candidates = (p for p in root.rglob("*") if p.is_file())
    else:
        candidates = (p for p in root.iterdir() if p.is_file())
    return [p for p in candidates if _should_include(p, include_patterns, exclude_patterns)]


def _file_hash(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def _to_scan_job_out(model: ScanJob) -> ScanJobOut:
    return ScanJobOut(
        id=model.id,
        data_source_id=model.data_source_id,
        status=model.status,
        total_files=model.total_files,
        new_files=model.new_files,
        changed_files=model.changed_files,
        missing_files=model.missing_files,
        started_at=model.started_at,
        finished_at=model.finished_at,
    )


def _to_source_file_out(model: SourceFile, *, task_stats: dict | None = None) -> SourceFileOut:
    task_stats = task_stats or {}
    error_stage, error_summary, error_detail = _normalize_file_error(model)
    build_ready, available_for_search, enhance_failed, lifecycle_status, lifecycle_label = _derive_file_lifecycle(model)
    return SourceFileOut(
        id=model.id,
        data_source_id=model.data_source_id,
        file_path=model.file_path,
        relative_path=model.relative_path,
        file_name=model.file_name,
        file_ext=model.file_ext,
        file_size=model.file_size,
        mtime=model.mtime,
        file_hash=model.file_hash,
        discover_status=model.discover_status,
        ingest_status=model.ingest_status,
        processing_stage=model.processing_stage,
        classification_status=model.classification_status,
        parse_status=model.parse_status,
        extract_status=model.extract_status,
        index_status=model.index_status,
        retry_count=model.retry_count,
        duplicate_of_doc_id=model.duplicate_of_doc_id,
        is_exact_duplicate=model.is_exact_duplicate,
        is_possible_duplicate=model.is_possible_duplicate,
        last_scan_at=model.last_scan_at,
        last_ingest_at=model.last_ingest_at,
        error_message=model.error_message,
        error_stage=error_stage,
        error_summary=error_summary,
        error_detail=error_detail,
        build_ready=build_ready,
        available_for_search=available_for_search,
        enhance_failed=enhance_failed,
        lifecycle_status=lifecycle_status,
        lifecycle_label=lifecycle_label,
        doc_id=model.doc_id,
        parsed_chunk_total=int(task_stats.get("parsed_chunk_total", 0) or 0),
        parsed_chunk_completed=int(task_stats.get("parsed_chunk_completed", 0) or 0),
        ai_chunk_total=int(task_stats.get("ai_chunk_total", 0) or 0),
        ai_chunk_completed=int(task_stats.get("ai_chunk_completed", 0) or 0),
        embedding_chunk_total=int(task_stats.get("embedding_chunk_total", 0) or 0),
        embedding_chunk_completed=int(task_stats.get("embedding_chunk_completed", 0) or 0),
    )


def _derive_file_lifecycle(model: SourceFile) -> tuple[bool, bool, bool, str, str]:
    failed_set = {"failed", "conversion_failed", "enhanced_parser_not_ready"}
    classify = str(model.classification_status or "").lower()
    parse = str(model.parse_status or "").lower()
    extract = str(model.extract_status or "").lower()
    index = str(model.index_status or "").lower()

    build_failed = classify in failed_set or parse in failed_set
    build_ready = (not build_failed) and classify in {"success", "skipped"} and parse in {"success", "skipped"}
    available_for_search = build_ready and bool(model.doc_id or model.duplicate_of_doc_id)
    enhance_failed = available_for_search and (extract in failed_set or index in failed_set)

    if build_failed:
        return False, False, False, "build_failed", "基础建库失败"
    if available_for_search and enhance_failed:
        return True, True, True, "enhance_failed", "可检索（增强失败）"
    if available_for_search and extract == "success" and index == "success":
        return True, True, False, "fully_ready", "完全就绪"
    if available_for_search:
        return True, True, False, "enhancing", "可检索（增强中）"
    if str(model.ingest_status or "").lower() == "processing":
        return False, False, False, "building", "基础建库中"
    return False, False, False, "pending", "待处理"


def _normalize_file_error(model: SourceFile) -> tuple[str | None, str | None, str | None]:
    message = (model.error_message or "").strip()
    if not message:
        return None, None, None

    parse_status = str(model.parse_status or "").lower()
    classification_status = str(model.classification_status or "").lower()
    extract_status = str(model.extract_status or "").lower()
    index_status = str(model.index_status or "").lower()
    processing_stage = str(model.processing_stage or "").lower()

    if parse_status in {"failed", "conversion_failed", "enhanced_parser_not_ready"} or processing_stage == "parsing":
        stage = "解析"
    elif classification_status == "failed" or processing_stage == "classifying":
        stage = "分类"
    elif extract_status == "failed" or processing_stage == "extracting":
        stage = "抽取"
    elif index_status == "failed" or processing_stage == "indexing":
        stage = "向量"
    else:
        stage = "处理"

    lowered = message.lower()
    if "ai analysis timed out after" in lowered:
        summary = "AI分析超时，当前模型响应过慢"
    elif stage == "分类" and "connection" in lowered:
        summary = "分类模型连接失败，当前无法完成文档分类"
    elif stage == "分类" and "timeout" in lowered:
        summary = "分类阶段超时，当前模型响应过慢"
    elif stage == "分类" and "json" in lowered:
        summary = "分类结果格式异常，模型没有返回可解析结果"
    elif stage == "分类":
        summary = "文档分类失败，当前无法完成智能分析"
    elif "pdf parsing timed out after" in lowered:
        summary = "复杂PDF解析超时，已记录失败，可后续单独重试或走慢速解析"
    elif "could not read null object" in lowered:
        summary = "PDF文件结构异常，当前解析器无法读取"
    elif "password" in lowered and "pdf" in lowered:
        summary = "PDF已加密，当前无法直接解析"
    elif "doc_conversion_failed" in lowered or "libreoffice" in lowered:
        summary = "Word旧格式转换失败，LibreOffice未成功完成转换"
    elif "enhanced parser" in lowered:
        summary = "增强解析器未就绪，当前环境没有可用的增强解析后端"
    elif "context length" in lowered:
        summary = "文本过长，模型上下文不足，当前任务未完成"
    elif "connection" in lowered or "timeout" in lowered:
        summary = "外部模型或服务连接超时"
    else:
        summary = message

    return stage, summary, message


def _collect_task_stats(db: Session, rows: list[SourceFile]) -> dict[str, dict]:
    doc_ids = {
        (row.doc_id or row.duplicate_of_doc_id)
        for row in rows
        if row.doc_id or row.duplicate_of_doc_id
    }
    docs = db.scalars(select(Document).where(Document.id.in_(doc_ids))).all() if doc_ids else []
    docs_by_id = {str(doc.id): doc for doc in docs}
    chunk_counts = {
        str(doc_id): int(total or 0)
        for doc_id, total in db.execute(
            select(Chunk.doc_id, func.count(Chunk.id)).where(Chunk.doc_id.in_(doc_ids)).group_by(Chunk.doc_id)
        ).all()
    } if doc_ids else {}
    emb_counts = {
        str(doc_id): int(total or 0)
        for doc_id, total in db.execute(
            select(Chunk.doc_id, func.count(ChunkEmbedding.id))
            .join(ChunkEmbedding, ChunkEmbedding.chunk_id == Chunk.id)
            .where(Chunk.doc_id.in_(doc_ids))
            .group_by(Chunk.doc_id)
        ).all()
    } if doc_ids else {}

    result: dict[str, dict] = {}
    for row in rows:
        doc_id = str(row.doc_id or row.duplicate_of_doc_id or "")
        doc = docs_by_id.get(doc_id) if doc_id else None
        stats = (doc.metadata_json or {}).get("ingest_stats", {}) if doc else {}
        parsed_total = int(stats.get("parsed_chunks") or chunk_counts.get(doc_id, 0) or 0)
        parsed_done = int(stats.get("parsed_chunk_completed") or (parsed_total if row.parse_status == "success" else 0))
        ai_total = int(stats.get("ai_chunk_total") or stats.get("ai_chunk_count") or min(parsed_total, max(0, settings.ai_extract_chunk_limit)))
        ai_done = int(stats.get("ai_chunk_completed") or (ai_total if row.extract_status == "success" else 0))
        emb_total = int(stats.get("embedding_chunk_total") or stats.get("embedded_chunk_count") or emb_counts.get(doc_id, 0) or 0)
        emb_done = int(stats.get("embedding_chunk_completed") or (emb_total if row.index_status == "success" else 0))
        result[str(row.id)] = {
            "parsed_chunk_total": parsed_total,
            "parsed_chunk_completed": min(parsed_done, parsed_total),
            "ai_chunk_total": ai_total,
            "ai_chunk_completed": min(ai_done, ai_total),
            "embedding_chunk_total": emb_total,
            "embedding_chunk_completed": min(emb_done, emb_total),
        }
    return result


def _execute_scan(db: Session, data_source: DataSource, scan_job: ScanJob, force_rescan: bool = False) -> ScanJobOut:
    root = Path(data_source.root_path)
    if not root.exists() or not root.is_dir():
        scan_job.status = JobStatus.failed.value
        scan_job.total_files = 0
        scan_job.new_files = 0
        scan_job.changed_files = 0
        scan_job.missing_files = 0
        scan_job.finished_at = datetime.now(UTC)
        db.commit()
        db.refresh(scan_job)
        return _to_scan_job_out(scan_job)

    existing_rows = db.scalars(
        select(SourceFile).where(SourceFile.data_source_id == data_source.id)
    ).all()
    existing_map = {row.file_path: row for row in existing_rows}

    counters = ScanCounters()
    seen_paths: set[str] = set()
    files = _iter_files(
        root=root,
        recursive=data_source.recursive,
        include_patterns=data_source.include_patterns,
        exclude_patterns=data_source.exclude_patterns,
    )
    scan_job.total_files = len(files)
    db.commit()
    db.refresh(scan_job)

    for file_path in files:
        counters.total_files += 1
        stat = file_path.stat()
        abs_path = str(file_path.resolve())
        seen_paths.add(abs_path)
        existing = existing_map.get(abs_path)
        scanned_at = datetime.now(UTC)
        file_mtime = datetime.fromtimestamp(stat.st_mtime, tz=UTC)

        if existing is None:
            file_hash = _file_hash(file_path)
            new_row = SourceFile(
                data_source_id=data_source.id,
                file_path=abs_path,
                relative_path=str(file_path.relative_to(root)),
                file_name=file_path.name,
                file_ext=file_path.suffix.lower() or None,
                file_size=stat.st_size,
                mtime=file_mtime,
                file_hash=file_hash,
                discover_status="active",
                ingest_status="pending",
                last_scan_at=scanned_at,
                updated_at=scanned_at,
            )
            db.add(new_row)
            counters.new_files += 1
            continue

        existing.discover_status = "active"
        existing.last_scan_at = scanned_at
        changed = force_rescan or existing.file_size != stat.st_size or existing.mtime != file_mtime
        if changed:
            new_hash = _file_hash(file_path)
            if force_rescan or existing.file_hash != new_hash:
                existing.file_size = stat.st_size
                existing.mtime = file_mtime
                existing.file_hash = new_hash
                existing.ingest_status = "pending"
                existing.updated_at = scanned_at
                counters.changed_files += 1
        else:
            existing.updated_at = scanned_at

    for row in existing_rows:
        if row.file_path not in seen_paths and row.discover_status != "missing":
            row.discover_status = "missing"
            row.updated_at = datetime.now(UTC)
            counters.missing_files += 1

    scan_job.status = JobStatus.success.value
    scan_job.total_files = counters.total_files
    scan_job.new_files = counters.new_files
    scan_job.changed_files = counters.changed_files
    scan_job.missing_files = counters.missing_files
    scan_job.finished_at = datetime.now(UTC)
    db.commit()
    db.refresh(scan_job)
    return _to_scan_job_out(scan_job)


def run_scan(db: Session, data_source_id: str, force_rescan: bool = False) -> ScanJobOut | None:
    data_source = db.get(DataSource, data_source_id)
    if data_source is None:
        return None

    started_at = datetime.now(UTC)
    scan_job = ScanJob(
        data_source_id=data_source.id,
        status=JobStatus.running.value,
        started_at=started_at,
    )
    db.add(scan_job)
    db.commit()
    db.refresh(scan_job)
    return _execute_scan(db, data_source, scan_job, force_rescan=force_rescan)


def create_scan_job(db: Session, data_source_id: str) -> ScanJobOut | None:
    data_source = db.get(DataSource, data_source_id)
    if data_source is None:
        return None
    now = datetime.now(UTC)
    scan_job = ScanJob(
        data_source_id=data_source.id,
        status=JobStatus.pending.value,
        total_files=0,
        new_files=0,
        changed_files=0,
        missing_files=0,
        started_at=now,
        finished_at=None,
    )
    db.add(scan_job)
    db.commit()
    db.refresh(scan_job)
    return _to_scan_job_out(scan_job)


def execute_scan_job(db: Session, scan_job_id: str, force_rescan: bool = False) -> ScanJobOut | None:
    scan_job = db.get(ScanJob, scan_job_id)
    if scan_job is None:
        return None
    data_source = db.get(DataSource, scan_job.data_source_id)
    if data_source is None:
        scan_job.status = JobStatus.failed.value
        scan_job.finished_at = datetime.now(UTC)
        db.commit()
        db.refresh(scan_job)
        return _to_scan_job_out(scan_job)
    scan_job.status = JobStatus.running.value
    scan_job.started_at = scan_job.started_at or datetime.now(UTC)
    db.commit()
    db.refresh(scan_job)
    return _execute_scan(db, data_source, scan_job, force_rescan=force_rescan)


def list_source_files(
    db: Session,
    data_source_id: str,
    *,
    parse_status: str | None = None,
    ingest_status: str | None = None,
    file_ext: str | None = None,
    is_exact_duplicate: bool | None = None,
    build_ready: bool | None = None,
    ai_pending: bool | None = None,
    vector_pending: bool | None = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple[list[SourceFileOut], int]:
    stmt = select(SourceFile).where(SourceFile.data_source_id == data_source_id)
    if parse_status:
        stmt = stmt.where(SourceFile.parse_status == parse_status)
    if ingest_status:
        stmt = stmt.where(SourceFile.ingest_status == ingest_status)
    if file_ext:
        normalized_ext = file_ext if file_ext.startswith(".") else f".{file_ext}"
        stmt = stmt.where(SourceFile.file_ext == normalized_ext.lower())
    if is_exact_duplicate is not None:
        stmt = stmt.where(SourceFile.is_exact_duplicate == is_exact_duplicate)
    rows = db.scalars(
        stmt.order_by(SourceFile.file_path.asc())
    ).all()
    stats_map = _collect_task_stats(db, rows)
    items = [_to_source_file_out(row, task_stats=stats_map.get(str(row.id))) for row in rows]

    if build_ready is not None:
        items = [
            item for item in items
            if (
                item.classification_status in {"success", "skipped"}
                and item.parse_status in {"success", "skipped"}
                and item.parsed_chunk_total > 0
            ) == build_ready
        ]
    if ai_pending is not None:
        items = [
            item for item in items
            if (
                item.ai_chunk_total > 0
                and item.ai_chunk_completed < item.ai_chunk_total
                and item.extract_status not in {"failed"}
            ) == ai_pending
        ]
    if vector_pending is not None:
        items = [
            item for item in items
            if (
                item.embedding_chunk_total > 0
                and item.embedding_chunk_completed < item.embedding_chunk_total
                and item.index_status not in {"failed"}
            ) == vector_pending
        ]
    total = len(items)
    safe_page = max(1, page)
    safe_page_size = max(1, page_size)
    start = (safe_page - 1) * safe_page_size
    end = start + safe_page_size
    return items[start:end], total


def get_source_file(db: Session, source_file_id: str) -> SourceFileOut | None:
    row = db.get(SourceFile, source_file_id)
    if row is None:
        return None
    stats_map = _collect_task_stats(db, [row])
    return _to_source_file_out(row, task_stats=stats_map.get(str(row.id)))
