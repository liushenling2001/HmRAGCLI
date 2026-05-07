param(
    [switch]$WithDocling,
    [string]$OutputDir = ".\wheelhouse"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Python = "python"
$Wheelhouse = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $ProjectRoot $OutputDir }

New-Item -ItemType Directory -Force -Path $Wheelhouse | Out-Null

Write-Host "[HmRAGCLI] Downloading base locked dependencies into $Wheelhouse ..."
& $Python -m pip download -r (Join-Path $ProjectRoot "requirements.lock.txt") -d $Wheelhouse
if ($LASTEXITCODE -ne 0) {
    throw "Base wheel download failed."
}

if ($WithDocling) {
    Write-Host "[HmRAGCLI] Downloading Docling locked dependencies into $Wheelhouse ..."
    & $Python -m pip download -r (Join-Path $ProjectRoot "requirements.docling.lock.txt") -d $Wheelhouse
    if ($LASTEXITCODE -ne 0) {
        throw "Docling wheel download failed."
    }
}

Write-Host "[HmRAGCLI] Wheelhouse prepared at $Wheelhouse"
