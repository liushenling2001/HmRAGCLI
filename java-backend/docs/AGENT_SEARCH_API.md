# Agent Search API（智能体检索接口文档）

本文档描述 HmRAG Java 后端为智能体提供的检索与问答接口。  
Base URL：`http://<host>:<port>/api/v1`

## 1. 统一检索（支持第一跳/第二跳）

### 1.1 `POST /agent/search`

用途：单次检索入口，支持同步/异步，支持返回第一跳（文档级）、第二跳（片段级）或两者。

请求体：

```json
{
  "query": "性能指标要求",
  "excludeDevDocs": false,
  "hop": "both",
  "page": 1,
  "pageSize": 20,
  "topKDocs": 20,
  "topKEvidencePerDoc": 5,
  "recallMode": "hybrid",
  "rerankModel": "builtin_hybrid",
  "includeOverview": true,
  "includeRawChunk": true,
  "debug": true,
  "async": false,
  "filters": {
    "dataSourceId": null,
    "docTypes": ["rule", "spec"],
    "dateFrom": "2020-01-01",
    "dateTo": "2026-12-31"
  }
}
```

关键参数：
- `hop`: `first` | `second` | `both`
- `async=true` 时立即返回 `taskId`，结果通过任务接口获取
- `filters` 支持按数据源、文档类型、日期区间过滤

响应（同步）：

```json
{
  "taskId": null,
  "taskStatus": "success",
  "result": {
    "docHits": [],
    "evidenceHits": [],
    "items": [],
    "total": 0,
    "page": 1,
    "pageSize": 20
  },
  "trace": {
    "query": "性能指标要求",
    "normalizedQuery": "性能指标要求",
    "hop": "both",
    "recallMode": "hybrid",
    "rerankModel": "builtin_hybrid",
    "tookMs": 38,
    "filters": {},
    "scoreBreakdown": {
      "lexicalWeight": 0.78,
      "vectorWeight": 0.22,
      "exactPhraseBoost": 1.3,
      "titleBoost": 1.2,
      "fulltextBoost": 1.1
    }
  }
}
```

响应（异步）：

```json
{
  "taskId": "9db6f99d-22c4-4a6d-8ab6-7fa8f4cc31e1",
  "taskStatus": "queued",
  "result": null,
  "trace": null
}
```

## 2. 检索计划（Plan / Execute）

### 2.1 `POST /agent/search/plan`

用途：生成可复用检索计划。

请求体：

```json
{
  "query": "负载均衡设计",
  "excludeDevDocs": false,
  "hop": "both",
  "topKDocs": 20,
  "topKEvidencePerDoc": 5,
  "recallMode": "hybrid",
  "rerankModel": "builtin_hybrid",
  "includeOverview": true,
  "includeRawChunk": true,
  "filters": {}
}
```

响应：

```json
{
  "plan": {
    "planId": "50b4174f-8223-45d8-a8ea-71d90a708407",
    "query": "负载均衡设计",
    "excludeDevDocs": false,
    "hop": "both",
    "topKDocs": 20,
    "topKEvidencePerDoc": 5,
    "recallMode": "hybrid",
    "rerankModel": "builtin_hybrid",
    "includeOverview": true,
    "includeRawChunk": true,
    "filters": {},
    "createdAt": "2026-04-08T10:18:21.230+08:00"
  }
}
```

### 2.2 `POST /agent/search/execute`

用途：执行已有计划。

请求体：

```json
{
  "planId": "50b4174f-8223-45d8-a8ea-71d90a708407",
  "page": 1,
  "pageSize": 20,
  "debug": true,
  "async": false
}
```

## 3. 任务状态

### `GET /agent/tasks/{taskId}`

用途：获取异步检索任务状态与结果。

响应：

```json
{
  "taskId": "9db6f99d-22c4-4a6d-8ab6-7fa8f4cc31e1",
  "status": "running",
  "progressPercent": 15,
  "message": "检索任务执行中",
  "result": null,
  "createdAt": "2026-04-08T10:20:22.100+08:00",
  "updatedAt": "2026-04-08T10:20:22.135+08:00"
}
```

状态值：`queued` / `running` / `success` / `failed`

## 4. 文档画像与分块

### 4.1 `GET /documents/{id}/overview`

用途：获取文档画像（已存在接口）。

### 4.2 `GET /documents/{id}/chunks`

用途：按文档分页获取原始分块，支持按章节标题与页码过滤。

参数：
- `section`：按 `chunks.title` 模糊匹配
- `pageNo`：页码过滤
- `page`、`pageSize`
- `includeContent=true|false`：是否返回完整块文本

示例：

```http
GET /api/v1/documents/7cbb9d91-a1fd-4f61-a362-3f0ed9ef8d9d/chunks?page=1&pageSize=10&includeContent=true
```

## 5. 智能体问答

### `POST /agent/answer`

用途：检索 + 结构化答案，返回引用与置信度。

请求体：

```json
{
  "query": "性能指标要求有哪些",
  "excludeDevDocs": false,
  "topK": 5,
  "hop": "both",
  "filters": {},
  "includeOverview": true
}
```

响应包含：
- `answer`
- `structuredAnswer`
- `citations`
- `usedDocIds`
- `confidence`
- `unansweredParts`
- `docOverview`
- `trace`

## 6. 兼容接口增强

### `GET /search`

已有接口新增参数：
- `hop=first|second|both`（默认 `both`）

示例：

```http
GET /api/v1/search?keyword=性能指标要求&hop=first&page=1&pageSize=20
```

## 7. 推荐调用链（Agent）

1. `POST /agent/search/plan` 生成计划  
2. `POST /agent/search/execute` 执行计划（可 async）  
3. `GET /agent/tasks/{taskId}` 轮询异步结果  
4. 对候选文档调用 `/documents/{id}/overview` 与 `/documents/{id}/chunks` 深读  
5. 最后调用 `/agent/answer` 生成可解释答案

## 8. 错误语义

- `400 Bad Request`：参数不合法（如 `planId` 缺失、日期格式不正确）
- `404 Not Found`：任务或计划不存在（当前实现会返回 `IllegalArgumentException` 映射后的错误响应）
- `500 Internal Server Error`：后端执行异常

