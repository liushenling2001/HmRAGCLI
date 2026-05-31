# HmRAG Java Backend

This is the Spring Boot backend for HmRAGCLI.

Current scope:

- data source management
- source file scanning and inventory
- batch ingest job creation
- failure visibility API
- document parsing, extraction, embeddings, search, and QA routes
- domain knowledge refinement and memory pack APIs
- knowledge graph build, graph store integration, entity fusion, and graph UI APIs
- PostgreSQL schema bootstrap and offline upgrade SQL scripts

Knowledge graph implementation notes:

- Source filenames, document titles, import IDs, and chunk titles are source metadata. They must not be treated as business entities unless正文 explicitly proves they are official project/system/policy names.
- LLM output must enter a candidate ledger first. It must not be written directly as confirmed global graph data.
- Entity descriptions belong in `EntityDescription` or description facts. `EntityState` must be derived from validated facts with time, phase, version, role context, or transition evidence.
- Main graph views should project confirmed `relation_fact` data as `Entity -> Entity`; attributes, descriptions, states, and low-level Neo4j structure belong in detail, audit, or debug views.
- The recoverable pipeline design is documented in `../docs/knowledge-graph-evolution-upgrade-plan.md`; staged implementation tasks are in `../docs/knowledge-graph-evolution-implementation-tasks.md`.

## Run

Use Java 25.

```powershell
cd java-backend
$env:JAVA_HOME = "D:\java"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn package
java -Xms1g -Xmx3g -jar target\hmrag-java-backend-0.1.0.jar --spring.config.location=file:application.yml
```
