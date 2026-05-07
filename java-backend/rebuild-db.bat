@echo off
setlocal

REM One-click rebuild script for test DB.
REM Requires:
REM   1) psql in PATH
REM   2) schema file at target\classes\db\bootstrap\schema.sql
REM Optional env overrides:
REM   POSTGRES_HOST POSTGRES_PORT POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB

set "PGHOST=%POSTGRES_HOST%"
if "%PGHOST%"=="" set "PGHOST=localhost"

set "PGPORT=%POSTGRES_PORT%"
if "%PGPORT%"=="" set "PGPORT=5432"

set "PGUSER=%POSTGRES_USER%"
if "%PGUSER%"=="" set "PGUSER=postgres"

set "PGPASSWORD=%POSTGRES_PASSWORD%"
set "DBNAME=%POSTGRES_DB%"
if "%DBNAME%"=="" set "DBNAME=hmrag"

set "SCHEMA_FILE=%~dp0target\classes\db\bootstrap\schema.sql"

echo [INFO] PostgreSQL: %PGHOST%:%PGPORT%, user=%PGUSER%, db=%DBNAME%

where psql >nul 2>nul
if errorlevel 1 (
  echo [ERROR] psql not found in PATH.
  exit /b 1
)

if not exist "%SCHEMA_FILE%" (
  echo [ERROR] Schema file not found: %SCHEMA_FILE%
  echo [HINT] Run package first to generate target\classes resources.
  exit /b 1
)

echo [INFO] Terminating active connections to %DBNAME% ...
psql -h "%PGHOST%" -p "%PGPORT%" -U "%PGUSER%" -d postgres -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '%DBNAME%' AND pid <> pg_backend_pid();" >nul
if errorlevel 1 (
  echo [ERROR] Failed to terminate active DB sessions.
  exit /b 1
)

echo [INFO] DROP DATABASE IF EXISTS %DBNAME% ...
psql -h "%PGHOST%" -p "%PGPORT%" -U "%PGUSER%" -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"%DBNAME%\";"
if errorlevel 1 (
  echo [ERROR] DROP DATABASE failed.
  exit /b 1
)

echo [INFO] CREATE DATABASE %DBNAME% ...
psql -h "%PGHOST%" -p "%PGPORT%" -U "%PGUSER%" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"%DBNAME%\";"
if errorlevel 1 (
  echo [ERROR] CREATE DATABASE failed.
  exit /b 1
)

echo [INFO] Importing schema ...
psql -h "%PGHOST%" -p "%PGPORT%" -U "%PGUSER%" -d "%DBNAME%" -v ON_ERROR_STOP=1 -f "%SCHEMA_FILE%"
if errorlevel 1 (
  echo [ERROR] Schema import failed.
  exit /b 1
)

echo [OK] Rebuild complete: %DBNAME%
exit /b 0

