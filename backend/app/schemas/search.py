from uuid import UUID

from pydantic import Field

from app.schemas.common import APIModel
from app.schemas.enums import DocType, UnitType


class UnifiedSearchQuery(APIModel):
    keyword: str
    exclude_dev_docs: bool = False
    doc_type: DocType | None = None
    unit_type: UnitType | None = None
    organization: str | None = None
    region: str | None = None
    indicator: str | None = None
    status: str | None = None
    page: int = 1
    page_size: int = 20


class UnifiedSearchItemOut(APIModel):
    kind: str
    match_type: str = "content"
    score: float
    doc_id: UUID
    source_file: str
    source_filename: str | None = None
    relative_path: str | None = None
    chunk_id: UUID | None = None
    unit_id: UUID | None = None
    doc_title: str | None = None
    doc_type: str
    is_dev_doc: bool = False
    doc_domain: str = "business"
    title: str | None = None
    content: str
    snippet: str | None = None
    page_no: int | None = None
    source_span: str | None = None
    subject: str | None = None
    indicator: str | None = None
    tags: list[str] = Field(default_factory=list)
