# HmRAG Java Backend

This is the new Spring Boot rewrite of the HmRAG backend.

Current scope:

- data source management
- source file scanning and inventory
- batch ingest job creation
- failure visibility API
- PostgreSQL schema managed by Flyway

Not migrated yet:

- real parser workers
- AI classification
- extraction
- embeddings and search
- query and QA routes

## Run

```bash
cd java-backend
mvn spring-boot:run
```
