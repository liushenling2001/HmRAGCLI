$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarPath = Join-Path $ScriptDir "target\hmrag-java-backend-0.1.0.jar"
$ConfigPath = Join-Path $ScriptDir "application.yml"

if (-not (Test-Path $JarPath)) {
    throw "[HmRAGCLI-Java] Jar not found: $JarPath"
}

if (-not (Test-Path $ConfigPath)) {
    throw "[HmRAGCLI-Java] Config not found: $ConfigPath"
}

& java -jar $JarPath "--spring.config.location=file:$ConfigPath"
