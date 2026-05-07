from fastapi import APIRouter

from app.api.routes import ai, data_sources, documents, knowledge_units, operations, qa, search, system, table_records

api_router = APIRouter()
api_router.include_router(ai.router, prefix="/ai", tags=["ai"])
api_router.include_router(data_sources.router, prefix="/data-sources", tags=["data-sources"])
api_router.include_router(documents.router, prefix="/documents", tags=["documents"])
api_router.include_router(knowledge_units.router, prefix="/knowledge-units", tags=["knowledge-units"])
api_router.include_router(operations.router, prefix="/operations", tags=["operations"])
api_router.include_router(search.router, prefix="/search", tags=["search"])
api_router.include_router(system.router, prefix="/system", tags=["system"])
api_router.include_router(table_records.router, prefix="/table-records", tags=["table-records"])
api_router.include_router(qa.router, prefix="/qa", tags=["qa"])
