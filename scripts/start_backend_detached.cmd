@echo off
setlocal

set PROJECT_ROOT=%~dp0..
set BACKEND_ROOT=%PROJECT_ROOT%\backend
set VENV_PYTHON=%PROJECT_ROOT%\.venv\Scripts\python.exe
set LOG_DIR=%PROJECT_ROOT%\logs

if not exist "%VENV_PYTHON%" (
  echo [HmRAGCLI] Project virtual environment not found: %VENV_PYTHON%
  echo [HmRAGCLI] Run scripts\bootstrap_env.ps1 or scripts\install_from_wheelhouse.ps1 first.
  exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

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

echo [HmRAGCLI] Starting backend in new window...
start "HmRAGCLI Backend" cmd /k ""%VENV_PYTHON%" -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
echo [HmRAGCLI] Backend start command sent.
echo [HmRAGCLI] Open http://127.0.0.1:8000/ops

endlocal
