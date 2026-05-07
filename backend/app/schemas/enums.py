from enum import Enum


class DocType(str, Enum):
    rule = "rule"
    speech = "speech"
    report = "report"
    excel = "excel"
    notice = "notice"
    minutes = "minutes"
    unknown = "unknown"


class UnitType(str, Enum):
    rule = "rule"
    statement = "statement"
    fact = "fact"
    table_record = "table_record"
    summary = "summary"


class SourceType(str, Enum):
    local_dir = "local_dir"
    network_share = "network_share"
    file_list = "file_list"


class SourceStatus(str, Enum):
    active = "active"
    paused = "paused"
    deleted = "deleted"


class JobStatus(str, Enum):
    pending = "pending"
    queued = "queued"
    running = "running"
    success = "success"
    partial_failed = "partial_failed"
    failed = "failed"
    skipped = "skipped"


class DocumentStatus(str, Enum):
    draft = "draft"
    effective = "effective"
    revised = "revised"
    expired = "expired"
    reference = "reference"


class QueryType(str, Enum):
    rule_qa = "rule_qa"
    speech_summary = "speech_summary"
    data_qa = "data_qa"
    document_lookup = "document_lookup"
    comparative_analysis = "comparative_analysis"
