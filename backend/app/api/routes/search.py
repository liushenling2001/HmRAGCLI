from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.common import PageResult
from app.schemas.enums import DocType, UnitType
from app.schemas.search import UnifiedSearchItemOut, UnifiedSearchQuery
from app.services import search as service

router = APIRouter()


@router.get("", response_model=PageResult[UnifiedSearchItemOut])
def unified_search(
    keyword: str,
    exclude_dev_docs: bool = False,
    doc_type: DocType | None = None,
    unit_type: UnitType | None = None,
    organization: str | None = None,
    region: str | None = None,
    indicator: str | None = None,
    status: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[UnifiedSearchItemOut]:
    query = UnifiedSearchQuery(
        keyword=keyword,
        exclude_dev_docs=exclude_dev_docs,
        doc_type=doc_type,
        unit_type=unit_type,
        organization=organization,
        region=region,
        indicator=indicator,
        status=status,
        page=page,
        page_size=page_size,
    )
    items, total = service.search_unified(db, query)
    return PageResult(items=items, total=total, page=page, page_size=page_size)
