from uuid import UUID

from app.schemas.common import APIModel


class TableRecordOut(APIModel):
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
