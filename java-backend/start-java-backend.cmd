@echo off
setlocal

set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%target\hmrag-java-backend-0.1.0.jar
set CONFIG_PATH=%SCRIPT_DIR%application.yml

if not exist "%JAR_PATH%" (
  echo [HmRAGCLI-Java] Jar not found: %JAR_PATH%
  exit /b 1
)

if not exist "%CONFIG_PATH%" (
  echo [HmRAGCLI-Java] Config not found: %CONFIG_PATH%
  exit /b 1
)

java -Xms1g -Xmx3g -jar "%JAR_PATH%" --spring.config.location=file:"%CONFIG_PATH%"

endlocal
