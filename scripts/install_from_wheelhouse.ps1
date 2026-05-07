param(
    [switch]$WithDocling,
    [string]$WheelhouseDir = ".\wheelhouse",
    [string]$VenvDir = ".venv"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BootstrapPython = "python"
$Wheelhouse = if ([System.IO.Path]::IsPathRooted($WheelhouseDir)) { $WheelhouseDir } else { Join-Path $ProjectRoot $WheelhouseDir }
$ResolvedVenv = if ([System.IO.Path]::IsPathRooted($VenvDir)) { $VenvDir } else { Join-Path $ProjectRoot $VenvDir }
$VenvPython = Join-Path $ResolvedVenv "Scripts\\python.exe"

if (-not (Test-Path $Wheelhouse)) {
    throw "Wheelhouse directory not found: $Wheelhouse"
}

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

Write-Host "[HmRAGCLI] Installing base locked dependencies from $Wheelhouse ..."
& $VenvPython -m pip install --no-index --find-links $Wheelhouse -r (Join-Path $ProjectRoot "requirements.lock.txt")
if ($LASTEXITCODE -ne 0) {
    throw "Base offline installation failed."
}

if ($WithDocling) {
    Write-Host "[HmRAGCLI] Installing Docling locked dependencies from $Wheelhouse ..."
    & $VenvPython -m pip install --no-index --find-links $Wheelhouse -r (Join-Path $ProjectRoot "requirements.docling.lock.txt")
    if ($LASTEXITCODE -ne 0) {
        throw "Docling offline installation failed."
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
    throw "Environment check failed after offline installation."
}

Write-Host "[HmRAGCLI] Offline installation completed. Virtual environment ready at $ResolvedVenv"
