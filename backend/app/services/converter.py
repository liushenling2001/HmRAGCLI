from __future__ import annotations

from hashlib import sha1
from pathlib import Path
import subprocess
import tempfile

from app.core.config import settings


class DocConversionError(RuntimeError):
    pass


def _conversion_key(path: Path) -> str:
    stat = path.stat()
    raw = f"{path.resolve()}|{stat.st_size}|{int(stat.st_mtime)}"
    return sha1(raw.encode("utf-8")).hexdigest()[:16]


def _converted_output_dir(path: Path) -> Path:
    base_dir = Path(tempfile.gettempdir()) / "hmragcli" / "converted" / _conversion_key(path)
    base_dir.mkdir(parents=True, exist_ok=True)
    return base_dir


def convert_doc_to_docx(path_str: str) -> Path:
    source_path = Path(path_str)
    if source_path.suffix.lower() != ".doc":
        return source_path

    output_dir = _converted_output_dir(source_path)
    target_path = output_dir / f"{source_path.stem}.docx"
    if target_path.exists():
        return target_path

    command = [
        settings.libreoffice_path,
        "--headless",
        "--convert-to",
        "docx",
        "--outdir",
        str(output_dir),
        str(source_path),
    ]
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
        timeout=120,
    )
    if completed.returncode != 0 or not target_path.exists():
        message = completed.stderr.strip() or completed.stdout.strip() or "LibreOffice conversion failed"
        raise DocConversionError(f".doc conversion failed for {source_path.name}: {message}")
    return target_path


def resolve_input_path(path_str: str) -> tuple[Path, dict]:
    source_path = Path(path_str)
    metadata = {
        "original_path": str(source_path),
        "original_suffix": source_path.suffix.lower(),
        "converted": False,
        "converter": None,
        "converted_path": None,
    }
    if source_path.suffix.lower() != ".doc":
        return source_path, metadata

    converted_path = convert_doc_to_docx(path_str)
    metadata.update(
        {
            "converted": True,
            "converter": "libreoffice",
            "converted_path": str(converted_path),
            "converted_suffix": converted_path.suffix.lower(),
        }
    )
    return converted_path, metadata
