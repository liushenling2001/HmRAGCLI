from sqlalchemy.orm import Session

from app.schemas.knowledge_units import KnowledgeUnitOut, KnowledgeUnitQuery
from app.services.search import search_knowledge_units


def list_knowledge_units(db: Session, query: KnowledgeUnitQuery) -> tuple[list[KnowledgeUnitOut], int]:
    return search_knowledge_units(db, query)
