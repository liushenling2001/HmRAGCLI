# HmRAGCLI Backend

## Offline Deployment Principle

This project is now organized for offline-style deployment:

```text
- prepare dependencies before runtime
- reuse local PostgreSQL / Ollama / LibreOffice
- do not download packages or models during document ingestion
```

## Run

```bash
uvicorn app.main:app --reload
```

Working directory:

```bash
cd backend
```

## Environment

Copy `.env.example` to `.env` in the project root and set:

```bash
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=hmrag
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_password
AI_PROVIDER=mock
AI_BASE_URL=
AI_API_KEY=
AI_MODEL=qwen2.5-3b-instruct
EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://127.0.0.1:11434
EMBEDDING_API_KEY=
EMBEDDING_MODEL=nomic-embed-text
LIBREOFFICE_PATH=soffice
PARSE_MODE=auto
ENHANCED_PARSER_BACKEND=none
```

## Parser Strategy

The parser layer now supports:

```text
- light: current built-in parser
- enhanced: Docling backend is now wired as an optional offline backend; MinerU remains reserved
- auto: choose enhanced only when configured and size thresholds are met
```

Offline-safe default:

```env
PARSE_MODE=auto
ENHANCED_PARSER_BACKEND=none
```

This keeps runtime behavior predictable:

```text
- built-in light parser remains the default path
- no parser package is downloaded during ingestion
- enhanced mode only works after its backend is prepared offline
```

If you want to enable Docling later, prepare it offline in the same Python environment first, then set:

```env
ENHANCED_PARSER_BACKEND=docling
```

If Docling is not installed, the system will return a clear `enhanced_parser_not_ready` error instead of silently falling back.

## Legacy Word `.doc`

The lightweight parser now handles legacy Word `.doc` files through LibreOffice pre-conversion:

```bash
soffice --headless --convert-to docx input.doc
```

Set this if `soffice` is not in `PATH`:

```bash
LIBREOFFICE_PATH=C:\\Program Files\\LibreOffice\\program\\soffice.exe
```

The app will:

```text
- detect .doc files
- convert them to .docx in a temp cache directory
- parse the converted .docx with the existing docx pipeline
```

Converted documents will also record parse metadata in `documents.metadata_json.parse`, including:

```text
- original_path
- original_suffix
- converted
- converter
- converted_path
```

You can verify LibreOffice directly with:

```bash
GET /api/v1/system/health
GET /api/v1/system/health?sample_doc_path=D:\workspace\HmRAGCLI\test\学生成绩.doc
GET /api/v1/system/libreoffice/health
GET /api/v1/system/libreoffice/health?sample_doc_path=D:\workspace\HmRAGCLI\test\学生成绩.doc
```

## OpenAI-Compatible API

To use an OpenAI-compatible model server instead of the mock provider:

```bash
AI_PROVIDER=openai_compatible
AI_BASE_URL=http://127.0.0.1:8000/v1
AI_API_KEY=your_key_if_needed
AI_MODEL=qwen2.5-3b-instruct
```

Notes:

```text
- AI_BASE_URL should point to the /v1 root, not directly to /chat/completions
- Some compatible services do not support response_format=json_object
- The provider already includes a fallback request for those services
```

## Ollama Embeddings

If your local Ollama provides `nomic-embed-text`, set:

```bash
EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://127.0.0.1:11434
EMBEDDING_MODEL=nomic-embed-text
```

This lets you keep:

```bash
AI_PROVIDER=openai_compatible
```

for classification/extraction, while embeddings use Ollama separately.

## Unified Search

The backend now exposes:

```bash
GET /api/v1/search?keyword=PostgreSQL
```

This endpoint merges:

```text
- knowledge_units: structured answers first
- chunks: raw source block recall as fallback/evidence
```

## pgvector Status

The project now supports native pgvector columns on:

```text
- knowledge_unit_embeddings.vector
- chunk_embeddings.vector
```

`init_db()` will try to create:

```text
CREATE EXTENSION IF NOT EXISTS vector
```

and HNSW cosine indexes:

```text
- idx_knowledge_unit_embeddings_vector_hnsw
- idx_chunk_embeddings_vector_hnsw
```

The JSONB vector copy is still kept as a compatibility fallback, but normal search now prefers database-side pgvector similarity.

## Locked Environment

Use the locked dependency file at the workspace root:

```bash
requirements.lock.txt
```

If you want the enhanced Docling parser in the same project environment, also prepare:

```bash
requirements.docling.lock.txt
```

Bootstrap a project-local virtual environment at `.venv`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1
```

Install the optional Docling-enhanced set in the same environment only when you have prepared the package offline:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1 -WithDocling
```

Run an environment precheck only:

```powershell
.\.venv\Scripts\python.exe .\scripts\check_environment.py
```

For the enhanced Docling deployment target:

```powershell
.\.venv\Scripts\python.exe .\scripts\check_environment.py --with-docling
```

Start the backend with precheck:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start_backend.ps1
```

Prepare an offline wheelhouse on a connected machine:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1 -WithDocling
```

Install from a local wheelhouse on the offline machine into `.venv`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1 -WithDocling
```
