from __future__ import annotations

from sqlalchemy import text

from app.db.session import SessionLocal
from app.core.config import settings
from app.schemas.system import ComponentHealthResponse, SystemHealthResponse
from app.services.ai_health import check_ai_health
from app.services.embedder import embed_query
from app.services.libreoffice_health import check_libreoffice_health
from app.services import parser as parser_service
import importlib.util


def _check_database() -> ComponentHealthResponse:
    try:
        db = SessionLocal()
        try:
            value = db.execute(text("select 1")).scalar()
        finally:
            db.close()
        return ComponentHealthResponse(
            name="database",
            ok=value == 1,
            detail={"result": value},
            error=None if value == 1 else "Database ping returned unexpected result",
        )
    except Exception as exc:
        return ComponentHealthResponse(name="database", ok=False, detail=None, error=str(exc))


def _check_ai() -> ComponentHealthResponse:
    result = check_ai_health()
    return ComponentHealthResponse(
        name="ai",
        ok=result.ok,
        detail={
            "provider": result.provider,
            "model": result.model,
            "result": result.result,
        },
        error=result.error,
    )


def _check_embedding() -> ComponentHealthResponse:
    try:
        vector = embed_query("system health embedding test")
        dims = len(vector)
        return ComponentHealthResponse(
            name="embedding",
            ok=dims > 0,
            detail={
                "provider": "fallback" if dims == 32 else "configured",
                "dimensions": dims,
            },
            error=None if dims > 0 else "Embedding returned an empty vector",
        )
    except Exception as exc:
        return ComponentHealthResponse(name="embedding", ok=False, detail=None, error=str(exc))


def _check_libreoffice(sample_doc_path: str | None = None) -> ComponentHealthResponse:
    result = check_libreoffice_health(sample_doc_path)
    return ComponentHealthResponse(
        name="libreoffice",
        ok=result.ok,
        detail=result.model_dump(),
        error=result.error,
    )


def _check_parser(sample_doc_path: str | None = None) -> ComponentHealthResponse:
    try:
        detail = {
            "parse_mode": settings.parse_mode,
            "enhanced_parser_backend": settings.enhanced_parser_backend,
            "docling_installed": importlib.util.find_spec("docling") is not None,
        }
        if sample_doc_path:
            detail["plan"] = parser_service.build_parse_metadata(sample_doc_path)
        return ComponentHealthResponse(
            name="parser",
            ok=True,
            detail=detail,
            error=None,
        )
    except Exception as exc:
        return ComponentHealthResponse(name="parser", ok=False, detail=None, error=str(exc))


def check_system_health(sample_doc_path: str | None = None) -> SystemHealthResponse:
    components = [
        _check_database(),
        _check_ai(),
        _check_embedding(),
        _check_libreoffice(sample_doc_path),
        _check_parser(sample_doc_path),
    ]
    return SystemHealthResponse(
        ok=all(component.ok for component in components),
        components=components,
    )
