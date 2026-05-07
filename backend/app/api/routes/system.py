from fastapi import APIRouter

from app.schemas.system import LibreOfficeHealthResponse, SystemHealthResponse
from app.services.libreoffice_health import check_libreoffice_health
from app.services.system_health import check_system_health

router = APIRouter()


@router.get("/health", response_model=SystemHealthResponse)
def system_health(sample_doc_path: str | None = None) -> SystemHealthResponse:
    return check_system_health(sample_doc_path)


@router.get("/libreoffice/health", response_model=LibreOfficeHealthResponse)
def libreoffice_health(sample_doc_path: str | None = None) -> LibreOfficeHealthResponse:
    return check_libreoffice_health(sample_doc_path)
