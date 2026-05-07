# HmRAGCLI 离线安装与测试手册

本文档用于在**离线环境**中完成 `HmRAGCLI` 的安装、启动与首轮测试。  
当前版本的测试重点是：

1. `Word` 文件稳定处理
2. 本地知识库构建
3. 检索结果可追踪到原始文件
4. 单文件失败不拖垮整批任务

## 1. 目标机需要的本地组件

离线机器需要提前准备以下组件：

1. `PostgreSQL`
2. `pgvector`
3. `LibreOffice`
4. `Ollama`
5. 本地 `OpenAI-compatible` 智能分析服务
6. `Python`

说明：

1. 当前第一版本建议以 `Word + Excel` 为主进行验证
2. `Docling` 暂时不是必需项
3. `MinerU` 当前不纳入离线安装主线

## 1.1 Python 环境策略

当前项目已经调整为：

1. 在项目根目录创建独立虚拟环境：
   - `.venv`
2. 所有依赖安装到 `.venv`
3. 后端启动固定使用 `.venv\\Scripts\\python.exe`

这意味着：

1. 不再依赖系统里已有的 Python 环境
2. 离线机器只要能创建 venv，就能把项目运行环境收敛在项目目录内
3. 更适合做离线测试和交付

## 2. 项目中已有的离线部署相关文件

项目中已经包含以下文件：

1. [offline-deployment-guide.md](D:\workspace\HmRAGCLI\docs\offline-deployment-guide.md)
2. [requirements.lock.txt](D:\workspace\HmRAGCLI\requirements.lock.txt)
3. [requirements.docling.lock.txt](D:\workspace\HmRAGCLI\requirements.docling.lock.txt)
4. [prepare_wheelhouse.ps1](D:\workspace\HmRAGCLI\scripts\prepare_wheelhouse.ps1)
5. [install_from_wheelhouse.ps1](D:\workspace\HmRAGCLI\scripts\install_from_wheelhouse.ps1)
6. [bootstrap_env.ps1](D:\workspace\HmRAGCLI\scripts\bootstrap_env.ps1)
7. [check_environment.py](D:\workspace\HmRAGCLI\scripts\check_environment.py)
8. [start_backend.ps1](D:\workspace\HmRAGCLI\scripts\start_backend.ps1)

## 3. 在联网机器准备离线依赖包

如果目标机不能联网，先在联网机器准备 Python 离线依赖。

### 3.1 基础版

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1
```

### 3.2 增强版

如果未来要带 `Docling`，执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_wheelhouse.ps1 -WithDocling
```

### 3.3 需要拷贝到离线机器的内容

建议至少拷贝：

1. 整个项目目录
2. wheelhouse 目录
3. PostgreSQL 安装包
4. pgvector 扩展
5. LibreOffice 安装包
6. Ollama 安装包
7. Ollama 模型文件
8. 本地分析模型服务及其运行文件

## 4. 在离线机器安装外部组件

### 4.1 PostgreSQL

安装 PostgreSQL，并创建业务数据库，例如：

- `hmrag`

### 4.2 pgvector

在 PostgreSQL 中启用：

```sql
CREATE EXTENSION vector;
```

### 4.3 LibreOffice

安装 LibreOffice，并确认 `soffice.exe` 路径可用。  
例如：

```text
D:\tools\libreoffice\program\soffice.exe
```

### 4.4 Ollama

安装 Ollama，并确保 embedding 模型可用：

```powershell
ollama list
```

需要至少有：

- `nomic-embed-text`

### 4.5 本地智能分析服务

确认你的 `OpenAI-compatible` 分析接口已经启动，并且本机可访问。  
例如：

```text
http://localhost:xxxx/v1
```

## 5. 在离线机器构建项目本地 Python 环境

### 5.1 基础版

这一步会：

1. 创建项目内 `.venv`
2. 使用离线 wheelhouse 安装依赖到 `.venv`
3. 用 `.venv` 运行环境检查

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1
```

### 5.2 增强版

如果要带 `Docling`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install_from_wheelhouse.ps1 -WithDocling
```

当前第一版本建议先只装**基础版**。

### 5.3 联网机器本地初始化

如果当前机器可以联网，也可以直接创建项目本地 `.venv`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1
```

如果要顺带装 `Docling`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_env.ps1 -WithDocling
```

## 6. 配置 .env

项目根目录需要准备 [`.env`](D:\workspace\HmRAGCLI\.env)。

建议最少包含以下配置：

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=hmrag
POSTGRES_USER=postgres
POSTGRES_PASSWORD=你的密码

AI_PROVIDER=openai_compatible
AI_BASE_URL=http://localhost:你的兼容接口/v1
AI_API_KEY=
AI_MODEL=你的分析模型

EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://localhost:11434
EMBEDDING_API_KEY=
EMBEDDING_MODEL=nomic-embed-text

LIBREOFFICE_PATH=D:\tools\libreoffice\program\soffice.exe

PARSE_MODE=auto
ENHANCED_PARSER_BACKEND=none
```

说明：

1. 第一版本建议保持 `ENHANCED_PARSER_BACKEND=none`
2. 第一版本以 `Word` 轻量解析链为正式主线

## 7. 环境检查

### 7.1 基础版检查

```powershell
.\.venv\Scripts\python.exe .\scripts\check_environment.py
```

### 7.2 增强版检查

如果安装了 `Docling`：

```powershell
.\.venv\Scripts\python.exe .\scripts\check_environment.py --with-docling
```

基础版通过时，至少应看到：

1. `database OK`
2. `ai OK`
3. `embedding OK`
4. `libreoffice OK`

## 8. 启动后端服务

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start_backend.ps1
```

如果 `8000` 端口被占用，可改成其他端口启动。  
当前本地测试常用：

- `8010`

启动后可以访问：

1. 运维面板：`/ops`
2. 查询页面：`/query`

示例：

```text
http://127.0.0.1:8010/ops
http://127.0.0.1:8010/query
```

说明：

`start_backend.ps1` 现在会强制使用：

```text
.venv\Scripts\python.exe
```

## 9. 推荐的首轮测试顺序

### 9.1 检查系统状态

打开运维面板：

```text
/ops
```

确认：

1. 数据库可用
2. AI 服务可用
3. Embedding 服务可用
4. LibreOffice 可用

### 9.2 新增数据源

在运维面板新增一个本地目录数据源，建议目录中先放少量测试文件：

1. `docx`
2. `doc`
3. `xlsx`
4. 少量 `pdf`

### 9.3 扫描

执行：

1. 扫描

确认：

1. 子文件夹可以被递归发现
2. 文件状态进入列表

### 9.4 入库

执行：

1. 入库

确认：

1. 文件页能看到逐文件状态
2. 成功与失败文件可区分
3. 单文件失败不会导致整批任务停摆

### 9.5 查询

打开：

```text
/query
```

测试两类能力：

1. 全文检索
2. 智能问答

当前重点建议先测：

1. 关键词检索
2. 原始文件追踪

确认结果中有：

1. 原始文件名
2. 原始路径
3. 位置

### 9.6 失败文件重跑

针对失败文件测试：

1. `重入库`
2. `重抽取`
3. `重向量`

确认：

1. 失败阶段能被明确提示
2. 重跑不影响其他文件

## 10. 当前版本的重点测试项

按你当前目标，建议优先关注这些：

### 10.1 Word 主链

1. `docx` 正文是否能稳定入库
2. `docx` 标题、列表是否识别合理
3. `docx` 表格是否能进库
4. `doc` 是否能通过 LibreOffice 稳定转换

### 10.2 检索追踪

1. 查询结果是否能回到原始文件
2. 原始路径是否显示正确
3. 位置是否能辅助定位

### 10.3 批处理稳定性

1. 单文件失败是否不拖垮整批
2. 是否能对失败文件单独重跑
3. 重跑后状态是否能正确更新

## 11. 第一版本建议的测试边界

为降低离线首轮测试风险，建议：

1. 先不启用 `Docling`
2. 先不启用 `MinerU`
3. 先以 `Word + Excel` 为主
4. PDF 只放少量样本
5. 先验证“构建、检索、追踪”主链

## 12. 常见问题排查顺序

如果离线机器上出问题，建议按这个顺序排查：

1. `.env` 是否正确
2. PostgreSQL 是否可连接
3. `vector` 扩展是否可用
4. LibreOffice 路径是否正确
5. Ollama 是否可访问
6. `nomic-embed-text` 是否存在
7. 本地 OpenAI-compatible 服务是否可访问
8. `check_environment.py` 是否全部通过

## 13. 当前版本结论

按目前工程状态，第一版本更适合这样使用：

1. 以 `Word` 文件为主知识源
2. 以知识库构建与检索为主
3. 重点验证可追踪检索
4. 把 `QA` 当成附加入口，而不是主目标

如果后续进入第二阶段，再继续做：

1. Word 解析进一步增强
2. PDF 容错与降级
3. Docling 增强解析的离线接入
