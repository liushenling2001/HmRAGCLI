from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.common import PageResult
from app.schemas.enums import DocType
from app.schemas.documents import ChunkOut, DocumentDetailOut, DocumentKnowledgeUnitOut, DocumentOut
from app.services import documents as service

router = APIRouter()


@router.get("", response_model=PageResult[DocumentOut])
def list_documents(
    doc_type: DocType | None = None,
    is_dev_doc: bool | None = None,
    doc_domain: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[DocumentOut]:
    items, total = service.list_documents(
        db,
        page=page,
        page_size=page_size,
        doc_type=doc_type,
        is_dev_doc=is_dev_doc,
        doc_domain=doc_domain,
    )
    return PageResult(items=items, total=total, page=page, page_size=page_size)


@router.get("/{doc_id}", response_model=DocumentDetailOut)
def get_document(doc_id: UUID, db: Session = Depends(get_db)) -> DocumentDetailOut:
    item = service.get_document_detail(db, doc_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Document not found")
    return item


@router.get("/{doc_id}/chunks", response_model=PageResult[ChunkOut])
def get_document_chunks(
    doc_id: UUID,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[ChunkOut]:
    result = service.list_document_chunks(db, doc_id, keyword=keyword, page=page, page_size=page_size)
    if result is None:
        raise HTTPException(status_code=404, detail="Document not found")
    items, total = result
    return PageResult(items=items, total=total, page=page, page_size=page_size)


@router.get("/{doc_id}/knowledge-units", response_model=PageResult[DocumentKnowledgeUnitOut])
def get_document_knowledge_units(
    doc_id: UUID,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 20,
    db: Session = Depends(get_db),
) -> PageResult[DocumentKnowledgeUnitOut]:
    result = service.list_document_knowledge_units(db, doc_id, keyword=keyword, page=page, page_size=page_size)
    if result is None:
        raise HTTPException(status_code=404, detail="Document not found")
    items, total = result
    return PageResult(items=items, total=total, page=page, page_size=page_size)
