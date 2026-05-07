from __future__ import annotations

import argparse
import importlib.metadata as metadata
import importlib.util
from pathlib import Path
import shutil
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
BACKEND_ROOT = PROJECT_ROOT / "backend"
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.core.config import settings
from app.services.libreoffice_health import check_libreoffice_health
from app.services.system_health import check_system_health


REQUIRED_PACKAGES = {
    "fastapi": "0.135.1",
    "uvicorn": "0.41.0",
    "pydantic": "2.12.5",
    "pydantic-settings": "2.13.1",
    "sqlalchemy": "2.0.25",
    "psycopg": "3.3.3",
    "pgvector": "0.4.2",
    "python-docx": "1.2.0",
    "openpyxl": "3.1.5",
    "pypdf": "6.9.2",
    "httpx": "0.28.1",
}


def _check_python() -> tuple[bool, str]:
    version = sys.version_info
    ok = version >= (3, 11)
    return ok, f"{version.major}.{version.minor}.{version.micro}"


def _check_packages() -> tuple[bool, list[str]]:
    messages: list[str] = []
    ok = True
    for name, expected in REQUIRED_PACKAGES.items():
        try:
            installed = metadata.version(name)
            if installed != expected:
                ok = False
                messages.append(f"{name}: installed={installed}, expected={expected}")
        except metadata.PackageNotFoundError:
            ok = False
            messages.append(f"{name}: missing, expected={expected}")
    return ok, messages


def _check_env_file() -> tuple[bool, str]:
    env_path = PROJECT_ROOT / ".env"
    return env_path.exists(), str(env_path)


def _check_libreoffice_binary() -> tuple[bool, str]:
    configured = settings.libreoffice_path
    resolved = shutil.which(configured) or (configured if Path(configured).exists() else "")
    return bool(resolved), configured


def main() -> int:
    parser = argparse.ArgumentParser(description="HmRAGCLI environment precheck")
    parser.add_argument(
        "--with-docling",
        action="store_true",
        help="Require Docling to be installed as part of the environment check",
    )
    args = parser.parse_args()

    print("== HmRAGCLI Environment Check ==")
    py_ok, py_version = _check_python()
    print(f"Python: {'OK' if py_ok else 'FAIL'} ({py_version})")

    env_ok, env_path = _check_env_file()
    print(f".env: {'OK' if env_ok else 'FAIL'} ({env_path})")

    pkg_ok, pkg_messages = _check_packages()
    print(f"Packages: {'OK' if pkg_ok else 'FAIL'}")
    for message in pkg_messages:
        print(f"  - {message}")

    lo_bin_ok, lo_path = _check_libreoffice_binary()
    print(f"LibreOffice path: {'OK' if lo_bin_ok else 'FAIL'} ({lo_path})")

    lo_health = check_libreoffice_health()
    print(f"LibreOffice health: {'OK' if lo_health.ok else 'FAIL'}")
    if lo_health.error:
        print(f"  - {lo_health.error}")

    system_health = check_system_health()
    print(f"System health: {'OK' if system_health.ok else 'FAIL'}")
    for component in system_health.components:
        print(f"  - {component.name}: {'OK' if component.ok else 'FAIL'}")
        if component.error:
            print(f"    error: {component.error}")
    docling_installed = importlib.util.find_spec("docling") is not None
    print(f"Docling installed: {'YES' if docling_installed else 'NO'}")
    if args.with_docling and not docling_installed:
        print("Docling requirement: FAIL (expected installed for enhanced parser deployment)")

    return 0 if py_ok and env_ok and pkg_ok and lo_health.ok and system_health.ok and (docling_installed or not args.with_docling) else 1


if __name__ == "__main__":
    raise SystemExit(main())
