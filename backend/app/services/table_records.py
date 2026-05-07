from uuid import uuid4

from app.schemas.table_records import TableRecordOut


def list_table_records() -> list[TableRecordOut]:
    return [
        TableRecordOut(
            id=uuid4(),
            doc_id=uuid4(),
            sheet_id=uuid4(),
            record_key="浦东新区|制造业|2025-02",
            time_value="2025-02",
            region="浦东新区",
            organization=None,
            dimensions={
                "region": "浦东新区",
                "industry": "制造业",
                "month": "2025-02",
            },
            metrics={"revenue": 3250, "yoy": 12},
            source_row_no=18,
            confidence=0.93,
        )
    ]
