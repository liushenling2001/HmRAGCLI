from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.common import PageResult
from app.schemas.enums import DocType, UnitType
from app.schemas.knowledge_units import KnowledgeUnitOut, KnowledgeUnitQuery
from app.services import knowledge_units as service

router = APIRouter()


@router.get("", response_model=PageResult[KnowledgeUnitOut])
def list_knowledge_units(
    keyword: str | None = None,
    exclude_dev_docs: bool = False,
    unit_type: UnitType | None = None,
    doc_type: DocType | None = None,
    organization: str | None = None,
    region: str | None = None,
    indicator: str | None = None,
    status: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[KnowledgeUnitOut]:
    query = KnowledgeUnitQuery(
        keyword=keyword,
        exclude_dev_docs=exclude_dev_docs,
        unit_type=unit_type,
        doc_type=doc_type,
        organization=organization,
        region=region,
        indicator=indicator,
        status=status,
        page=page,
        page_size=page_size,
    )
    items, total = service.list_knowledge_units(db, query)
    return PageResult(items=items, total=total, page=page, page_size=page_size)
