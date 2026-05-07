from fastapi import APIRouter

from app.schemas.common import PageResult
from app.schemas.table_records import TableRecordOut
from app.services import table_records as service

router = APIRouter()


@router.get("", response_model=PageResult[TableRecordOut])
def list_table_records(page: int = 1, page_size: int = 20) -> PageResult[TableRecordOut]:
    items = service.list_table_records()
    return PageResult(items=items, total=len(items), page=page, page_size=page_size)
