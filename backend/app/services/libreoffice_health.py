from __future__ import annotations

from pathlib import Path
import shutil
import subprocess

from app.core.config import settings
from app.schemas.system import LibreOfficeHealthResponse
from app.services.converter import convert_doc_to_docx


def _probe_libreoffice_version(configured_path: str) -> str | None:
    try:
        completed = subprocess.run(
            [configured_path, "--version"],
            capture_output=True,
            text=True,
            check=False,
            timeout=5,
        )
        if completed.returncode != 0:
            return None
        return (completed.stdout or completed.stderr or "").strip() or None
    except Exception:
        return None


def check_libreoffice_health(sample_doc_path: str | None = None) -> LibreOfficeHealthResponse:
    configured_path = settings.libreoffice_path
    resolved_exec = shutil.which(configured_path) or (configured_path if Path(configured_path).exists() else None)
    executable_found = resolved_exec is not None

    if not executable_found:
        return LibreOfficeHealthResponse(
            configured_path=configured_path,
            executable_found=False,
            ok=False,
            error="LibreOffice executable not found",
        )

    version_text = _probe_libreoffice_version(configured_path)
    try:
        converted_docx_path = None
        if sample_doc_path:
            sample_path = Path(sample_doc_path)
            if not sample_path.exists():
                return LibreOfficeHealthResponse(
                    configured_path=configured_path,
                    executable_found=True,
                    ok=False,
                    version=version_text,
                    sample_doc_path=str(sample_path),
                    error="Sample .doc file does not exist",
                )
            if sample_path.suffix.lower() != ".doc":
                return LibreOfficeHealthResponse(
                    configured_path=configured_path,
                    executable_found=True,
                    ok=False,
                    version=version_text,
                    sample_doc_path=str(sample_path),
                    error="Sample file must be a .doc file",
                )
            converted_docx_path = str(convert_doc_to_docx(str(sample_path)))

        return LibreOfficeHealthResponse(
            configured_path=configured_path,
            executable_found=True,
            ok=True,
            version=version_text,
            sample_doc_path=sample_doc_path,
            converted_docx_path=converted_docx_path,
            error=None,
        )
    except Exception as exc:
        return LibreOfficeHealthResponse(
            configured_path=configured_path,
            executable_found=True,
            ok=False,
            sample_doc_path=sample_doc_path,
            error=str(exc),
        )
