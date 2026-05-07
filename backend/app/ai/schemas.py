from pydantic import BaseModel, Field


class DocumentClassificationResult(BaseModel):
    doc_type: str = Field(default="unknown")
    confidence: float = Field(default=0.0)
    reasons: list[str] = Field(default_factory=list)
    secondary_types: list[str] = Field(default_factory=list)


class KnowledgeUnitExtractionResult(BaseModel):
    unit_type: str = Field(default="summary")
    title: str | None = None
    normalized_text: str | None = None
    subject: str | None = None
    action: str | None = None
    organization: str | None = None
    person: str | None = None
    region: str | None = None
    time_expr: str | None = None
    indicator: str | None = None
    value_text: str | None = None
    unit_name: str | None = None
    status: str = Field(default="reference")
    priority: int = Field(default=0)
    confidence: float = Field(default=0.0)
    fields: dict = Field(default_factory=dict)
