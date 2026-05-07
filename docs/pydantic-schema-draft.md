# FastAPI / Pydantic Schema 草案

## 1. 文档目标

本文档给出“轻标签型业务知识库”的 Pydantic Schema 草案，覆盖：

1. 数据源管理
2. 本地路径批量接入
3. 文档与知识单元查询
4. 问答接口

目标是为下一步 `FastAPI` 开发提供直接可用的对象设计。

---

## 2. 枚举定义建议

```python
from enum import Enum


class DocType(str, Enum):
    rule = "rule"
    speech = "speech"
    report = "report"
    excel = "excel"
    notice = "notice"
    minutes = "minutes"


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
```

---

## 3. 通用对象

### 3.1 分页对象

```python
from pydantic import BaseModel
from typing import Generic, TypeVar, List

T = TypeVar("T")


class PageResult(BaseModel, Generic[T]):
    items: List[T]
    total: int
    page: int
    page_size: int
```

### 3.2 标签对象

```python
from pydantic import BaseModel
from uuid import UUID
from datetime import datetime


class TagDefinitionOut(BaseModel):
    id: UUID
    tag_type: str
    tag_name: str
    tag_code: str
    description: str | None = None
    is_active: bool
    created_at: datetime
```

---

## 4. 数据源 Schema

### 4.1 创建数据源

```python
from pydantic import BaseModel, Field


class DataSourceCreate(BaseModel):
    source_name: str = Field(..., max_length=255)
    source_type: SourceType
    root_path: str = Field(..., max_length=2000)
    include_patterns: list[str] = Field(default_factory=list)
    exclude_patterns: list[str] = Field(default_factory=list)
    recursive: bool = True
    metadata: dict = Field(default_factory=dict)
```

### 4.2 更新数据源

```python
class DataSourceUpdate(BaseModel):
    source_name: str | None = None
    include_patterns: list[str] | None = None
    exclude_patterns: list[str] | None = None
    recursive: bool | None = None
    status: SourceStatus | None = None
    metadata: dict | None = None
```

### 4.3 数据源输出

```python
from uuid import UUID
from datetime import datetime


class DataSourceOut(BaseModel):
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
```

### 4.4 数据源详情

```python
class DataSourceDetailOut(DataSourceOut):
    total_files: int = 0
    success_files: int = 0
    failed_files: int = 0
    pending_files: int = 0
    last_scan_at: datetime | None = None
    last_ingest_at: datetime | None = None
```

---

## 5. 文件与扫描任务 Schema

### 5.1 文件输出

```python
class SourceFileOut(BaseModel):
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
    last_scan_at: datetime | None = None
    last_ingest_at: datetime | None = None
    error_message: str | None = None
    doc_id: UUID | None = None
```

### 5.2 启动扫描

```python
class ScanStartRequest(BaseModel):
    force_rescan: bool = False
```

### 5.3 扫描任务输出

```python
class ScanJobOut(BaseModel):
    id: UUID
    data_source_id: UUID
    status: JobStatus
    total_files: int
    new_files: int
    changed_files: int
    missing_files: int
    started_at: datetime | None = None
    finished_at: datetime | None = None
```

### 5.4 启动批量入库

```python
class BatchIngestStartRequest(BaseModel):
    mode: str = "incremental"
    reprocess_failed: bool = False
    force_rebuild_index: bool = False
```

### 5.5 批量任务输出

```python
class BatchIngestJobOut(BaseModel):
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
```

---

## 6. 文档 Schema

### 6.1 文档输出

```python
class DocumentOut(BaseModel):
    id: UUID
    title: str
    doc_type: DocType
    source_file: str
    source_filename: str | None = None
    source_org: str | None = None
    author: str | None = None
    publish_date: str | None = None
    effective_date: str | None = None
    expiry_date: str | None = None
    status: DocumentStatus
    language: str
    parse_status: str
    metadata: dict
```

### 6.2 文档查询参数

```python
class DocumentQuery(BaseModel):
    keyword: str | None = None
    doc_type: DocType | None = None
    source_org: str | None = None
    status: DocumentStatus | None = None
    date_from: str | None = None
    date_to: str | None = None
    page: int = 1
    page_size: int = 20
```

---

## 7. 知识单元 Schema

### 7.1 知识单元输出

```python
class KnowledgeUnitOut(BaseModel):
    id: UUID
    doc_id: UUID
    chunk_id: UUID | None = None
    unit_type: UnitType
    title: str | None = None
    content: str
    normalized_text: str | None = None
    subject: str | None = None
    action: str | None = None
    organization: str | None = None
    person: str | None = None
    region: str | None = None
    time_expr: str | None = None
    event_date: str | None = None
    indicator: str | None = None
    value_num: float | None = None
    value_text: str | None = None
    unit_name: str | None = None
    effective_date: str | None = None
    expiry_date: str | None = None
    status: str
    priority: int
    confidence: float | None = None
    source_span: str | None = None
    source_page: int | None = None
    fields: dict
    tags: list[str] = []
```

### 7.2 知识单元查询参数

```python
class KnowledgeUnitQuery(BaseModel):
    keyword: str | None = None
    unit_type: UnitType | None = None
    doc_type: DocType | None = None
    organization: str | None = None
    region: str | None = None
    indicator: str | None = None
    status: str | None = None
    tags: list[str] = []
    date_from: str | None = None
    date_to: str | None = None
    page: int = 1
    page_size: int = 20
```

---

## 8. Excel 结构化数据 Schema

### 8.1 表记录输出

```python
class TableRecordOut(BaseModel):
    id: UUID
    doc_id: UUID
    sheet_id: UUID
    record_key: str | None = None
    time_value: str | None = None
    region: str | None = None
    organization: str | None = None
    dimensions: dict
    metrics: dict
    source_row_no: int | None = None
    confidence: float | None = None
```

### 8.2 表记录查询参数

```python
class TableRecordQuery(BaseModel):
    doc_id: UUID | None = None
    sheet_name: str | None = None
    region: str | None = None
    organization: str | None = None
    time_value: str | None = None
    indicator: str | None = None
    page: int = 1
    page_size: int = 20
```

---

## 9. 问答 Schema

### 9.1 问答请求

```python
class QAQueryRequest(BaseModel):
    query: str
    doc_types: list[DocType] = []
    organizations: list[str] = []
    regions: list[str] = []
    tags: list[str] = []
    status: str | None = None
    top_k: int = 10
```

### 9.2 引用对象

```python
class CitationOut(BaseModel):
    doc_id: UUID
    unit_id: UUID | None = None
    chunk_id: UUID | None = None
    title: str | None = None
    source_span: str | None = None
    page_no: int | None = None
```

### 9.3 结构化回答对象

```python
class StructuredAnswerOut(BaseModel):
    subject: str | None = None
    action: str | None = None
    constraint: str | None = None
    exception: str | None = None
    indicator: str | None = None
    value: str | float | None = None
    unit_name: str | None = None
    time: str | None = None
    region: str | None = None
    summary_points: list[str] = []
```

### 9.4 问答响应

```python
class QAQueryResponse(BaseModel):
    query_type: QueryType
    answer: str
    structured_answer: StructuredAnswerOut | None = None
    citations: list[CitationOut] = []
```

---

## 10. 接口与 Schema 对应关系

建议接口与对象对应如下：

1. `POST /api/v1/data-sources`
   - `DataSourceCreate`
   - `DataSourceOut`

2. `GET /api/v1/data-sources/{id}`
   - `DataSourceDetailOut`

3. `POST /api/v1/data-sources/{id}/scan`
   - `ScanStartRequest`
   - `ScanJobOut`

4. `POST /api/v1/data-sources/{id}/ingest`
   - `BatchIngestStartRequest`
   - `BatchIngestJobOut`

5. `GET /api/v1/data-sources/{id}/files`
   - `PageResult[SourceFileOut]`

6. `GET /api/v1/documents`
   - `PageResult[DocumentOut]`

7. `GET /api/v1/knowledge-units`
   - `PageResult[KnowledgeUnitOut]`

8. `GET /api/v1/table-records`
   - `PageResult[TableRecordOut]`

9. `POST /api/v1/qa/query`
   - `QAQueryRequest`
   - `QAQueryResponse`

---

## 11. 开发建议

下一步如果直接开始写 `FastAPI`，建议先落以下 4 组 schema：

1. `data_sources`
2. `source_files`
3. `knowledge_units`
4. `qa`

原因：

1. 这四组已经覆盖主流程
2. 能先跑通本地目录批量接入
3. 能先打通基础检索和问答闭环

一句话概括：

**先把数据源、知识单元和问答对象定义稳定，后面的服务实现才不会反复返工。**
