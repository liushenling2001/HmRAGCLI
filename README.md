# HmRAGCLI

HmRAGCLI is a local/offline RAG system with a Java backend, Vue operations UI, PostgreSQL full-text search, pgvector semantic search, and domain knowledge refinement workflows.

## Main Modules

- `java-backend/`: Spring Boot backend, database migrations, offline SQL scripts, and generated UI resources under `src/main/resources/static/`.
- `frontend/`: local Vue UI source workspace, ignored by Git; commit generated UI resources only.
- `backend/`: legacy/Python service code kept for compatibility and reference.
- `docs/`: design notes, API notes, and generated architecture documents.
- `scripts/`: helper scripts.

## Local Configuration

Do not commit real runtime configuration. Use:

- `java-backend/src/main/resources/application.yml` for safe default environment-variable based config.
- `java-backend/application.example.yml` as a local runtime config template.
- `java-backend/application.yml` for actual local/offline secrets and machine paths. It is ignored by Git.

## Build

```powershell
cd java-backend
mvn package
```

The packaged jar is generated under `java-backend/target/`, which is ignored by Git.

## Run

```powershell
java -Xms1g -Xmx3g -jar java-backend\target\hmrag-java-backend-0.1.0.jar --spring.config.location=file:java-backend\application.yml
```

## Database

Offline upgrade scripts are under `java-backend/sql/`. For the current offline deployment baseline, use:

```powershell
psql -h 127.0.0.1 -U postgres -d hmrag -f java-backend\sql\offline_db_upgrade_20260506.sql
```
