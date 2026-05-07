from uuid import UUID

from pydantic import Field

from app.schemas.common import APIModel
from app.schemas.enums import DocType, QueryType


class QAQueryRequest(APIModel):
    query: str
    exclude_dev_docs: bool = False
    doc_types: list[DocType] = Field(default_factory=list)
    organizations: list[str] = Field(default_factory=list)
    regions: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    status: str | None = None
    top_k: int = 10


class CitationOut(APIModel):
    doc_id: UUID
    source_file: str
    source_filename: str | None = None
    relative_path: str | None = None
    unit_id: UUID | None = None
    chunk_id: UUID | None = None
    title: str | None = None
    source_span: str | None = None
    page_no: int | None = None


class StructuredAnswerOut(APIModel):
    subject: str | None = None
    action: str | None = None
    constraint: str | None = None
    exception: str | None = None
    indicator: str | None = None
    value: str | float | None = None
    unit_name: str | None = None
    time: str | None = None
    region: str | None = None
    summary_points: list[str] = Field(default_factory=list)


class QAQueryResponse(APIModel):
    query_type: QueryType
    answer: str
    structured_answer: StructuredAnswerOut | None = None
    citations: list[CitationOut] = Field(default_factory=list)
