from fastapi import APIRouter

from app.schemas.ai import AIHealthResponse
from app.services.ai_health import check_ai_health

router = APIRouter()


@router.get("/health", response_model=AIHealthResponse)
def ai_health() -> AIHealthResponse:
    return check_ai_health()
