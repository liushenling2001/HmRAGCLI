@echo off
setlocal

set PROJECT_ROOT=%~dp0..
set BACKEND_ROOT=%PROJECT_ROOT%\backend
set VENV_PYTHON=%PROJECT_ROOT%\.venv\Scripts\python.exe

if not exist "%VENV_PYTHON%" (
  echo [HmRAGCLI] Project virtual environment not found: %VENV_PYTHON%
  echo [HmRAGCLI] Run scripts\bootstrap_env.ps1 or scripts\install_from_wheelhouse.ps1 first.
  exit /b 1
)

echo [HmRAGCLI] Checking local environment...
"%VENV_PYTHON%" "%PROJECT_ROOT%\scripts\check_environment.py"
if errorlevel 1 (
  echo [HmRAGCLI] Environment check failed.
  exit /b 1
)

echo [HmRAGCLI] Initializing database schema...
cd /d "%BACKEND_ROOT%"
"%VENV_PYTHON%" -c "from app.db.init_db import init_db; init_db(); print('init_db ok')"
if errorlevel 1 (
  echo [HmRAGCLI] Database initialization failed.
  exit /b 1
)

echo [HmRAGCLI] Starting backend in foreground...
"%VENV_PYTHON%" -m uvicorn app.main:app --host 0.0.0.0 --port 8000

endlocal
