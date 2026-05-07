from pathlib import Path
import threading

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.api.router import api_router
from app.core.config import settings
from app.db.init_db import init_db
from app.db.session import SessionLocal
from app.services.ingest import recover_stale_processing_work, run_enhancement_worker

UI_ROOT = Path(__file__).resolve().parent / "ui"
_enhancement_worker_thread: threading.Thread | None = None
_enhancement_worker_stop: threading.Event | None = None


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        docs_url="/docs",
        redoc_url="/redoc",
    )
    app.include_router(api_router, prefix=settings.api_prefix)
    app.mount("/ui", StaticFiles(directory=str(UI_ROOT)), name="ui")

    @app.on_event("startup")
    def on_startup() -> None:
        global _enhancement_worker_thread, _enhancement_worker_stop
        init_db()
        db = SessionLocal()
        try:
            recover_stale_processing_work(db)
        finally:
            db.close()
        if settings.async_enhance_after_ingest and (_enhancement_worker_thread is None or not _enhancement_worker_thread.is_alive()):
            _enhancement_worker_stop = threading.Event()
            _enhancement_worker_thread = threading.Thread(
                target=run_enhancement_worker,
                args=(_enhancement_worker_stop,),
                name="hmrag-enhancement-worker",
                daemon=True,
            )
            _enhancement_worker_thread.start()

    @app.on_event("shutdown")
    def on_shutdown() -> None:
        global _enhancement_worker_stop
        if _enhancement_worker_stop is not None:
            _enhancement_worker_stop.set()

    @app.get("/health", tags=["system"])
    def healthcheck() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ops", include_in_schema=False)
    def operations_panel() -> FileResponse:
        return FileResponse(UI_ROOT / "ops" / "index.html")

    @app.get("/query", include_in_schema=False)
    def query_panel() -> FileResponse:
        return FileResponse(UI_ROOT / "query" / "index.html")

    return app


app = create_app()
