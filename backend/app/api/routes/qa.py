from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.qa import QAQueryRequest, QAQueryResponse
from app.services import qa as service

router = APIRouter()


@router.post("/query", response_model=QAQueryResponse)
def query(payload: QAQueryRequest, db: Session = Depends(get_db)) -> QAQueryResponse:
    return service.answer_question(db, payload)
