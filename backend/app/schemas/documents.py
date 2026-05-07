from uuid import UUID

from pydantic import Field

from app.schemas.common import APIModel
from app.schemas.enums import DocType, DocumentStatus, UnitType


class DocumentOut(APIModel):
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
    is_dev_doc: bool
    doc_domain: str
    parse_status: str
    metadata: dict


class ChunkOut(APIModel):
    id: UUID
    doc_id: UUID
    chunk_no: int
    chunk_type: str
    title: str | None = None
    content: str
    page_no: int | None = None
    token_count: int | None = None
    metadata: dict = Field(default_factory=dict)


class DocumentKnowledgeUnitOut(APIModel):
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
    region: str | None = None
    time_expr: str | None = None
    indicator: str | None = None
    value_num: float | None = None
    value_text: str | None = None
    unit_name: str | None = None
    status: str
    priority: int
    confidence: float | None = None
    source_span: str | None = None
    source_page: int | None = None
    fields: dict = Field(default_factory=dict)


class DuplicateSourceFileOut(APIModel):
    id: UUID
    file_name: str
    file_path: str
    relative_path: str | None = None
    data_source_id: UUID
    duplicate_type: str
    is_exact_duplicate: bool
    is_possible_duplicate: bool


class RelatedDocumentOut(APIModel):
    id: UUID
    title: str
    doc_type: DocType
    source_file: str
    source_filename: str | None = None
    is_dev_doc: bool
    doc_domain: str
    relation_type: str
    similarity_score: float


class ParseErrorOut(APIModel):
    type: str
    message: str


class DocumentDetailOut(APIModel):
    document: DocumentOut
    chunk_count: int
    knowledge_unit_count: int
    parse_error: ParseErrorOut | None = None
    duplicate_files: list[DuplicateSourceFileOut] = Field(default_factory=list)
    similar_documents: list[RelatedDocumentOut] = Field(default_factory=list)
    chunks: list[ChunkOut] = Field(default_factory=list)
    knowledge_units: list[DocumentKnowledgeUnitOut] = Field(default_factory=list)
