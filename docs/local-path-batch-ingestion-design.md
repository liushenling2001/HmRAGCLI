# 本地路径海量文件批量接入设计

## 1. 文档目标

本文档补充“轻标签型业务知识库”的本地路径批量接入能力设计，解决以下问题：

1. 海量文献无法逐篇上传
2. 需要直接处理本地目录、共享盘、挂载盘中的文件
3. 需要支持增量扫描、重复文件去重、断点续跑
4. 需要支持长时间后台入库任务

目标是让系统既支持 API 上传，也支持**本地路径批量采集**。

---

## 2. 设计原则

### 2.1 批量接入优先于单文件上传

对于海量文档，主入口不应是“上传文件”，而应是：

1. 注册数据源目录
2. 后台扫描
3. 自动识别新增和变更文件
4. 分批解析与入库

### 2.2 文件系统是数据源，不是临时中转

系统应把本地路径作为正式数据源管理，而不是仅把文件复制进系统后再处理。

### 2.3 增量优先

每次扫描不应重复处理全量文件，而应基于：

1. 文件路径
2. 文件大小
3. 修改时间
4. 文件哈希

判断是否需要重跑。

### 2.4 任务可恢复

海量文件接入必须支持：

1. 中断恢复
2. 失败重试
3. 跳过坏文件
4. 任务进度查看

---

## 3. 支持的接入方式

建议支持三类接入方式：

### 3.1 单文件上传

适合临时测试和小规模补录。

### 3.2 目录注册

用户提供本地目录路径，系统自动扫描并入库。

示例：

1. `D:\data\reports`
2. `D:\data\rules`
3. `\\fileserver\shared\stats`

### 3.3 批量清单导入

用户提供一个文件清单，例如 CSV 或 TXT，列出要处理的路径。

适合：

1. 不连续目录
2. 多盘符混合路径
3. 手工筛选过的文件集

---

## 4. 核心能力设计

系统需要新增以下核心能力：

1. 数据源注册
2. 目录扫描
3. 文件变化检测
4. 批量任务编排
5. 增量入库
6. 失败重试
7. 断点续跑
8. 任务监控

---

## 5. 数据源设计

建议引入 `data_sources` 概念。

## 5.1 `data_sources` 表

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| source_name | varchar(255) | 数据源名称 |
| source_type | varchar(50) | local_dir/network_share/file_list |
| root_path | varchar(2000) | 根路径 |
| include_patterns | jsonb | 包含规则 |
| exclude_patterns | jsonb | 排除规则 |
| recursive | boolean | 是否递归扫描 |
| status | varchar(50) | active/paused/deleted |
| metadata_json | jsonb | 扩展配置 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

## 5.2 典型配置

```json
{
  "source_name": "规章制度目录",
  "source_type": "local_dir",
  "root_path": "D:\\data\\rules",
  "include_patterns": ["*.pdf", "*.docx", "*.doc", "*.xlsx", "*.xls", "*.md"],
  "exclude_patterns": ["~$*", "*.tmp", "*.bak"],
  "recursive": true
}
```

---

## 6. 文件发现与增量扫描

## 6.1 目录扫描逻辑

扫描器从 `root_path` 开始遍历目录，识别匹配文件。

建议支持：

1. 递归扫描
2. 最大目录深度限制
3. 文件类型过滤
4. 文件名过滤
5. 隐藏文件过滤

## 6.2 文件变化检测策略

每个文件建议记录以下指纹：

1. `file_path`
2. `file_name`
3. `file_ext`
4. `file_size`
5. `mtime`
6. `file_hash`

推荐判断逻辑：

### 快速判断

如果以下任一项变化，则视为“可能变更”：

1. 文件大小变化
2. 修改时间变化
3. 路径变化

### 精确判断

对“可能变更”的文件再计算哈希，确认是否内容变化。

推荐哈希：

1. `sha256`

这样可以减少全量哈希带来的 IO 压力。

## 6.3 去重策略

建议至少支持两级去重：

### 路径级去重

同一路径同一版本文件只处理一次。

### 内容级去重

即使路径不同，如果 `file_hash` 一样，也可以标记为重复文件，避免重复解析。

---

## 7. 文件索引表设计

建议新增 `source_files` 表，管理数据源中的每个文件。

## 7.1 `source_files`

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| data_source_id | uuid | 数据源 ID |
| file_path | varchar(2000) | 绝对路径 |
| relative_path | varchar(1000) | 相对路径 |
| file_name | varchar(500) | 文件名 |
| file_ext | varchar(20) | 扩展名 |
| file_size | bigint | 文件大小 |
| mtime | timestamp | 修改时间 |
| file_hash | varchar(128) | 内容哈希 |
| discover_status | varchar(50) | active/missing/ignored |
| ingest_status | varchar(50) | pending/queued/processing/success/failed/skipped |
| last_scan_at | timestamp | 最近扫描时间 |
| last_ingest_at | timestamp | 最近入库时间 |
| error_message | text | 错误信息 |
| doc_id | uuid | 对应文档 ID |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

建议索引：

1. `idx_source_files_data_source_id`
2. `idx_source_files_file_path`
3. `idx_source_files_file_hash`
4. `idx_source_files_ingest_status`

---

## 8. 批量入库任务设计

建议引入三层任务：

1. 扫描任务
2. 入库批次任务
3. 单文件处理任务

## 8.1 `scan_jobs`

负责扫描目录和发现文件变化。

字段建议：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| data_source_id | uuid | 数据源 ID |
| status | varchar(50) | pending/running/success/failed |
| total_files | int | 扫描总文件数 |
| new_files | int | 新文件数 |
| changed_files | int | 变更文件数 |
| missing_files | int | 缺失文件数 |
| started_at | timestamp | 开始时间 |
| finished_at | timestamp | 结束时间 |

## 8.2 `batch_ingest_jobs`

负责一次批量入库。

字段建议：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| data_source_id | uuid | 数据源 ID |
| trigger_type | varchar(50) | manual/scheduled/resume |
| status | varchar(50) | pending/running/success/partial_failed/failed |
| total_files | int | 总文件数 |
| success_files | int | 成功数 |
| failed_files | int | 失败数 |
| skipped_files | int | 跳过数 |
| started_at | timestamp | 开始时间 |
| finished_at | timestamp | 结束时间 |

## 8.3 `file_ingest_jobs`

负责单文件处理状态。

字段建议：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| batch_job_id | uuid | 批次任务 ID |
| source_file_id | uuid | 文件 ID |
| status | varchar(50) | queued/processing/success/failed/skipped |
| retry_count | int | 重试次数 |
| parser_name | varchar(100) | 解析器名 |
| extract_status | varchar(50) | pending/success/failed |
| index_status | varchar(50) | pending/success/failed |
| error_message | text | 错误信息 |
| started_at | timestamp | 开始时间 |
| finished_at | timestamp | 结束时间 |

---

## 9. 处理流程设计

## 9.1 首次全量扫描

1. 注册数据源目录
2. 启动扫描任务
3. 发现所有匹配文件
4. 写入 `source_files`
5. 创建批量入库任务
6. 分派单文件处理任务
7. 完成解析、抽取、索引

## 9.2 增量扫描

1. 重新扫描目录
2. 识别新增文件
3. 识别变更文件
4. 标记缺失文件
5. 只对新增和变更文件重新入库

## 9.3 缺失文件处理

如果文件已不存在：

1. `discover_status` 标记为 `missing`
2. 不立即物理删除知识库数据
3. 可根据策略选择：
   - 保留但标记失联
   - 逻辑下线
   - 延迟清理

推荐默认策略：**逻辑下线，不立即删除**

---

## 10. 路径处理规则

## 10.1 必须保存绝对路径

便于后续定位和增量扫描。

## 10.2 同时保存相对路径

便于目录迁移和界面展示。

## 10.3 路径规范化

Windows 环境建议统一：

1. 规范盘符大小写
2. 消除重复分隔符
3. 统一 UNC 路径格式

## 10.4 文件过滤规则

默认建议过滤：

1. `~$*.docx`
2. `~$*.xlsx`
3. `*.tmp`
4. `*.bak`
5. 临时导出文件

---

## 11. 性能与并发设计

海量文件接入必须考虑吞吐和资源控制。

## 11.1 建议的处理队列

建议分成 4 段流水线：

1. `discover queue`
2. `parse queue`
3. `extract queue`
4. `index queue`

## 11.2 并发控制

建议按文件类型限制并发：

1. `PDF`：低并发
2. `DOCX`：中并发
3. `XLSX`：中高并发
4. `MD/TXT`：高并发

原因：

1. PDF 解析最耗 CPU/内存
2. Excel 处理速度通常更快

## 11.3 大目录优化

建议支持：

1. 分页扫描
2. 扫描结果流式写库
3. 哈希延迟计算
4. 批量提交任务

---

## 12. 错误处理与恢复

## 12.1 常见错误类型

1. 文件损坏
2. 文件被占用
3. 路径无权限访问
4. 解析器异常
5. OCR 失败
6. Excel 格式异常

## 12.2 处理策略

### 可重试错误

例如：

1. 文件短暂占用
2. 网络盘瞬时不可达
3. 临时解析失败

策略：

1. 自动重试 3 次
2. 指数退避

### 不可重试错误

例如：

1. 文件已损坏
2. 格式不支持

策略：

1. 标记失败
2. 记录错误信息
3. 进入人工复核列表

## 12.3 断点续跑

恢复时只处理以下文件：

1. `queued`
2. `processing` 但超时
3. `failed` 且允许重试
4. 新发现或已变更文件

---

## 13. API 设计

除了单文件上传外，建议新增以下接口。

## 13.1 注册数据源

`POST /api/v1/data-sources`

请求示例：

```json
{
  "source_name": "统计报告目录",
  "source_type": "local_dir",
  "root_path": "D:\\data\\stats",
  "include_patterns": ["*.pdf", "*.docx", "*.xlsx"],
  "exclude_patterns": ["~$*", "*.tmp"],
  "recursive": true
}
```

响应示例：

```json
{
  "data_source_id": "uuid",
  "status": "active"
}
```

## 13.2 启动扫描

`POST /api/v1/data-sources/{id}/scan`

响应示例：

```json
{
  "scan_job_id": "uuid",
  "status": "pending"
}
```

## 13.3 启动批量入库

`POST /api/v1/data-sources/{id}/ingest`

请求示例：

```json
{
  "mode": "incremental",
  "reprocess_failed": false
}
```

响应示例：

```json
{
  "batch_job_id": "uuid",
  "status": "pending"
}
```

## 13.4 查询数据源状态

`GET /api/v1/data-sources/{id}`

返回内容建议包括：

1. 根路径
2. 文件总数
3. 最近扫描时间
4. 最近入库时间
5. 成功/失败文件数

## 13.5 查询文件列表

`GET /api/v1/data-sources/{id}/files`

支持过滤：

1. `ingest_status`
2. `file_ext`
3. `keyword`
4. `changed_only`

## 13.6 重试失败文件

`POST /api/v1/batch-jobs/{id}/retry-failed`

---

## 14. 命令行与本地程序入口

如果这个程序本身是桌面端或本地运行程序，建议同时提供 CLI。

建议命令：

### 注册目录

```bash
kb ingest add-source --name "规则目录" --path "D:\data\rules" --recursive
```

### 扫描目录

```bash
kb ingest scan --source-id <id>
```

### 批量入库

```bash
kb ingest run --source-id <id> --mode incremental
```

### 查看状态

```bash
kb ingest status --source-id <id>
```

CLI 的价值：

1. 适合本地离线环境
2. 适合无人值守任务
3. 适合批处理和脚本集成

---

## 15. 推荐实现方案

如果要支持你当前这个“海量本地文献目录”的场景，我建议这样落地：

### 第一阶段必须做

1. `data_sources`
2. `source_files`
3. 目录扫描
4. 增量变更检测
5. 批量入库任务
6. 失败重试
7. CLI 命令入口

### 第二阶段增强

1. 定时扫描
2. 网络共享盘支持
3. 清单文件导入
4. 断点续跑优化
5. 文件删除后的逻辑下线策略

### 第三阶段增强

1. 多目录统一管理
2. 按目录自动推断文种
3. 按目录绑定标签模板
4. 批次质量报告

---

## 16. 最终建议

对海量文献场景，系统主入口应从“单文件上传”升级为“本地路径数据源管理”。

推荐最终模式：

1. 用户注册本地目录
2. 系统扫描目录
3. 自动识别新增和变更文件
4. 后台批量解析、抽取和索引
5. 用户在知识库中直接检索和问答

一句话概括：

**海量文件知识库的关键不是上传能力，而是数据源目录管理、增量扫描和可恢复的批量入库能力。**
