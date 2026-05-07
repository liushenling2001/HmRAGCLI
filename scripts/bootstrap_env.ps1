param(
    [switch]$WithDocling,
    [string]$VenvDir = ".venv"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BootstrapPython = "python"
$ResolvedVenv = if ([System.IO.Path]::IsPathRooted($VenvDir)) { $VenvDir } else { Join-Path $ProjectRoot $VenvDir }
$VenvPython = Join-Path $ResolvedVenv "Scripts\\python.exe"

if (-not (Test-Path $VenvPython)) {
    Write-Host "[HmRAGCLI] Creating project virtual environment at $ResolvedVenv ..."
    & $BootstrapPython -m venv $ResolvedVenv
    if ($LASTEXITCODE -ne 0) {
        throw "Virtual environment creation failed."
    }
}

Write-Host "[HmRAGCLI] Using project virtual environment: $ResolvedVenv"
Write-Host "[HmRAGCLI] Upgrading pip in project virtual environment ..."
& $VenvPython -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) {
    throw "pip upgrade failed."
}

Write-Host "[HmRAGCLI] Installing locked dependencies from requirements.lock.txt ..."
& $VenvPython -m pip install -r (Join-Path $ProjectRoot "requirements.lock.txt")

if ($LASTEXITCODE -ne 0) {
    throw "Dependency installation failed."
}

if ($WithDocling) {
    Write-Host "[HmRAGCLI] Installing optional Docling dependencies from requirements.docling.lock.txt ..."
    & $VenvPython -m pip install -r (Join-Path $ProjectRoot "requirements.docling.lock.txt")
    if ($LASTEXITCODE -ne 0) {
        throw "Docling dependency installation failed."
    }
}

Write-Host "[HmRAGCLI] Running environment check ..."
if ($WithDocling) {
    & $VenvPython (Join-Path $ProjectRoot "scripts\\check_environment.py") --with-docling
}
else {
    & $VenvPython (Join-Path $ProjectRoot "scripts\\check_environment.py")
}

if ($LASTEXITCODE -ne 0) {
    throw "Environment check failed after dependency installation."
}

Write-Host "[HmRAGCLI] Bootstrap completed. Virtual environment ready at $ResolvedVenv"
