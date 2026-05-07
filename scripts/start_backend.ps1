$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendRoot = Join-Path $ProjectRoot "backend"
$VenvPython = Join-Path $ProjectRoot ".venv\\Scripts\\python.exe"

if (-not (Test-Path $VenvPython)) {
    throw "Project virtual environment not found: $VenvPython. Run scripts\\bootstrap_env.ps1 or scripts\\install_from_wheelhouse.ps1 first."
}

Write-Host "[HmRAGCLI] Checking local environment..."
& $VenvPython (Join-Path $ProjectRoot "scripts\\check_environment.py")
if ($LASTEXITCODE -ne 0) {
    throw "Environment check failed. Fix the reported issues before starting the backend."
}

Write-Host "[HmRAGCLI] Initializing database schema..."
Set-Location $BackendRoot
& $VenvPython -c "from app.db.init_db import init_db; init_db(); print('init_db ok')"
if ($LASTEXITCODE -ne 0) {
    throw "Database initialization failed. Fix the reported issues before starting the backend."
}

Write-Host "[HmRAGCLI] Starting backend..."
Set-Location $BackendRoot
& $VenvPython -m uvicorn app.main:app --host 0.0.0.0 --port 8000
