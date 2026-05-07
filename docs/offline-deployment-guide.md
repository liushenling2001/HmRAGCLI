# HmRAGCLI 离线部署手册

## 1. 目标

本系统按“部署前准备、运行时不下载”的原则部署。

运行时只依赖本地已存在的：

- PostgreSQL
- pgvector
- LibreOffice
- Ollama
- OpenAI-compatible API 服务
- Python 环境与已安装依赖

不在这些阶段临时下载内容：

- 文档扫描
- 文档解析
- 知识抽取
- 向量化
- 检索
- API 启动

## 2. 当前系统依赖

### 2.1 系统组件

必须准备：

1. Python 3.11
2. PostgreSQL
3. pgvector 扩展
4. LibreOffice
5. Ollama
6. OpenAI-compatible API 服务

### 2.2 Python 依赖

项目根目录已提供锁定文件：

- [requirements.lock.txt](D:\workspace\HmRAGCLI\requirements.lock.txt)
- [requirements.docling.lock.txt](D:\workspace\HmRAGCLI\requirements.docling.lock.txt)

当前锁定版本：

```text
fastapi==0.135.1
uvicorn==0.41.0
pydantic==2.12.5
pydantic-settings==2.13.1
sqlalchemy==2.0.25
psycopg[binary]==3.3.3
pgvector==0.4.2
python-docx==1.2.0
openpyxl==3.1.5
pypdf==6.9.2
httpx==0.28.1
```

如果要启用增强解析版 `Docling`，额外准备：

```text
docling==2.78.0
```

## 3. 推荐部署方式

推荐做法是：

1. 在联网机器准备 Python 包
2. 在目标机器准备 PostgreSQL / pgvector / LibreOffice / Ollama
3. 将代码、`.env`、依赖包、模型、启动脚本整体拷贝到离线环境
4. 在离线机器上只做安装和检查，不做下载

## 4. 部署目录建议

建议目标目录如下：

```text
D:\deploy\HmRAGCLI\
  backend\
  docs\
  scripts\
  requirements.lock.txt
  .env
  pyproject.toml
```

## 5. PostgreSQL 准备

### 5.1 基本要求

需要本地 PostgreSQL 实例，例如：

```text
host=localhost
port=5432
database=hmrag
user=postgres
```

### 5.2 pgvector

必须提前安装 `vector` 扩展。

在数据库中验证：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

## 6. LibreOffice 准备

系统使用 LibreOffice 处理老式 Word `.doc`：

```text
.doc -> LibreOffice -> .docx -> 当前 docx 解析链
```

你当前已验证可用路径：

```text
D:\tools\libreoffice\program\soffice.exe
```

配置写入 `.env`：

```env
LIBREOFFICE_PATH=D:\tools\libreoffice\program\soffice.exe
```

## 7. Ollama 准备

当前 embedding 依赖本地 Ollama：

```env
EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://localhost:11434
EMBEDDING_MODEL=nomic-embed-text
```

离线部署前，必须在本机提前准备模型：

```bash
ollama pull nomic-embed-text
```

离线机器上只应启动本地 Ollama，不再联网拉模型。

## 8. AI 服务准备

当前分类/抽取依赖 OpenAI-compatible API：

```env
AI_PROVIDER=openai_compatible
AI_BASE_URL=...
AI_API_KEY=...
AI_MODEL=qwen3-coder-next
```

如果离线环境不能访问外部服务，应提前准备本地兼容服务。

要求：

1. 提供 `/v1/chat/completions`
2. 支持当前文档分类与知识抽取 prompt
3. 运行时不再联网下载模型

## 9. Python 环境准备

### 9.1 直接复用现有 Python 环境

如果离线机器已具备可用 Python 3.11，可直接安装锁定依赖：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1
```

如果要一并安装增强版 Docling 依赖：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1 -WithDocling
```

### 9.2 联网机器提前打包依赖

推荐在联网机器提前下载 wheel：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1 -WithDocling
```

然后把 `wheelhouse` 整体拷贝到离线环境。

离线安装：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1 -WithDocling
```

## 10. 配置文件

项目根目录使用：

- [`.env`](D:\workspace\HmRAGCLI\.env)
- [`.env.example`](D:\workspace\HmRAGCLI\.env.example)

当前关键配置项：

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=hmrag
POSTGRES_USER=postgres
POSTGRES_PASSWORD=...

AI_PROVIDER=openai_compatible
AI_BASE_URL=...
AI_API_KEY=...
AI_MODEL=qwen3-coder-next

EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://localhost:11434
EMBEDDING_MODEL=nomic-embed-text

LIBREOFFICE_PATH=D:\tools\libreoffice\program\soffice.exe
```

## 11. 启动前检查

先执行：

```powershell
python .\scripts\check_environment.py
```

如果目标环境是增强版 Docling：

```powershell
python .\scripts\check_environment.py --with-docling
```

检查项包括：

1. Python 版本
2. `.env` 存在
3. 锁定依赖版本
4. LibreOffice 可执行路径
5. 数据库连通性
6. AI 服务连通性
7. Embedding 服务连通性
8. LibreOffice 健康状态

还可通过 API 验证：

```text
GET /api/v1/system/health
GET /api/v1/system/libreoffice/health
```

## 12. 启动方式

推荐使用启动脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start_backend.ps1
```

该脚本会：

1. 先跑环境检查
2. 检查通过后再启动 FastAPI

## 13. 数据接入注意事项

### 13.1 默认纳入的文件类型

新建数据源默认包含：

```text
*.pdf
*.doc
*.docx
*.xlsx
*.md
```

### 13.2 `.doc` 文件

`.doc` 在运行时会先转换到临时目录：

```text
%TEMP%\hmragcli\converted\...
```

并且系统会在 `documents.metadata_json.parse` 中记录：

```text
original_path
original_suffix
converted
converter
converted_path
converted_suffix
```

## 14. 故障处理

### 14.1 `.doc` 转换失败

当前会明确标记为：

```text
source_files.parse_status = conversion_failed
documents.parse_status = conversion_failed
documents.metadata_json.parse_error.type = doc_conversion_failed
```

可筛选：

```text
GET /api/v1/data-sources/{id}/files?parse_status=conversion_failed
```

### 14.2 常见排查顺序

建议顺序：

1. 先跑 `python .\scripts\check_environment.py`
2. 再看 `GET /api/v1/system/health`
3. 再看 `GET /api/v1/system/libreoffice/health`
4. 再检查 `source_files` 状态
5. 最后重跑单文件：
   - `reingest`
   - `reextract`
   - `reembed`

## 15. 后续扩展原则

后面如果接入增强解析器，如 `Docling` 或 `MinerU`，也必须遵守同一原则：

1. 模型和依赖在部署前准备
2. 运行时只调用本地资源
3. 不接受运行中自动下载
4. 必须有环境检查项

当前代码层已经预留了解析策略：

```text
PARSE_MODE=light|auto|enhanced
ENHANCED_PARSER_BACKEND=none|docling|mineru
```

默认推荐：

```text
PARSE_MODE=auto
ENHANCED_PARSER_BACKEND=none
```

这表示：

1. 生产默认继续使用内置轻量解析
2. 当前 Docling 已接入为可选增强后端，但必须先在离线环境准备好 Python 包和其依赖
3. MinerU 仍保留为预留后端
4. 如果误开增强解析但后端未就绪，系统会返回明确的 `enhanced_parser_not_ready`

启用方式：

```env
PARSE_MODE=auto
ENHANCED_PARSER_BACKEND=docling
```

启用前必须满足：

1. `requirements.docling.lock.txt` 已离线安装
2. `python -c "import docling"` 成功
3. `GET /api/v1/system/health` 中 `parser.detail.docling_installed = true`
