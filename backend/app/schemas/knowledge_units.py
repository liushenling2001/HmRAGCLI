from uuid import UUID

from pydantic import Field

from app.schemas.common import APIModel
from app.schemas.enums import DocType, UnitType


class KnowledgeUnitOut(APIModel):
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


class KnowledgeUnitQuery(APIModel):
    keyword: str | None = None
    exclude_dev_docs: bool = False
    unit_type: UnitType | None = None
    doc_type: DocType | None = None
    organization: str | None = None
    region: str | None = None
    indicator: str | None = None
    status: str | None = None
    tags: list[str] = Field(default_factory=list)
    date_from: str | None = None
    date_to: str | None = None
    page: int = 1
    page_size: int = 20
