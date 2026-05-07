# HmRAG Java Backend

This is the Spring Boot backend for HmRAGCLI.

Current scope:

- data source management
- source file scanning and inventory
- batch ingest job creation
- failure visibility API
- document parsing, extraction, embeddings, search, and QA routes
- domain knowledge refinement and memory pack APIs
- PostgreSQL schema bootstrap and offline upgrade SQL scripts

## Run

Use Java 25.

```powershell
cd java-backend
$env:JAVA_HOME = "D:\java"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn package
java -Xms1g -Xmx3g -jar target\hmrag-java-backend-0.1.0.jar --spring.config.location=file:application.yml
```
