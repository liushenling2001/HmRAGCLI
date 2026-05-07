# 查询接口说明（面向 AI / 智能体）

本文档面向“由 AI 自动调用接口”的场景，覆盖检索、文档画像、分块阅读、下载原文、智能体检索编排与任务轮询。

## 1. 基础信息

- 基础路径：`/api/v1`
- 返回格式：`application/json`（下载接口返回文件流）
- 鉴权：当前接口定义中未要求额外 token（若部署层有网关鉴权，以网关为准）
- 统一错误结构：
  - HTTP 4xx/5xx 时通常返回：`{"detail":"错误说明"}`

---

## 2. 快速检索接口（推荐先接入）

### 2.1 文档+证据检索

`GET /api/v1/search`

查询参数：
- `keyword` `string` 必填：检索词/问题
- `excludeDevDocs` `boolean` 可选，默认 `false`
- `hop` `string` 可选，默认 `both`，取值：`first|second|both`
- `page` `int` 可选，默认 `1`
- `pageSize` `int` 可选，默认 `20`

返回：`QueryDtos.SearchResponse`
- `docHits`：文档级命中（第一跳）
- `evidenceHits`：证据片段（第二跳）
- `items`：与 `evidenceHits` 等价（兼容字段）
- `total/page/pageSize`

建议：
- AI 首轮建议 `hop=both`，拿文档与证据一起做判断
- 若只做文档召回可用 `hop=first`
- 若只拿证据列表可用 `hop=second`

---

### 2.2 文档画像

`GET /api/v1/documents/{id}/overview`

路径参数：
- `id` `uuid`：文档 ID（来自 `docHits[].docId`）

返回：`DocumentOverviewResponse`
- `docId/docTitle/sourceFile/sourceFilename/relativePath`
- `overview`：摘要、章节、关键词、实体、时间范围等

---

### 2.3 文档分块阅读

`GET /api/v1/documents/{id}/chunks`

路径参数：
- `id` `uuid`

查询参数：
- `section` `string` 可选
- `pageNo` `int` 可选
- `page` `int` 可选，默认 `1`
- `pageSize` `int` 可选，默认 `20`
- `includeContent` `boolean` 可选，默认 `false`

返回：`DocumentChunksResponse`
- `items[]`：包含 `chunkId/chunkNo/title/snippet/content/pageNo...`

建议：
- 默认 `includeContent=false` 拉目录化片段，减少传输
- 仅对候选片段再二次请求 `includeContent=true`

---

### 2.4 下载原始文件

`GET /api/v1/documents/{id}/download`

路径参数：
- `id` `uuid`

返回：
- 文件流（`Content-Disposition: attachment`）

后端安全校验（已实现）：
- 文档必须存在并有 `source_file`
- 物理文件必须存在且可读
- 文件路径需落在该文档关联数据源 `root_path` 下

---

### 2.5 QA 聚合回答

`POST /api/v1/qa/query`

请求体：
```json
{
  "query": "string",
  "excludeDevDocs": true,
  "topK": 5
}
```

返回：`QAQueryResponse`
- `answer`
- `structuredAnswer`
- `citations[]`
- `docOverview`

适用：
- 需要后端直接给“答案+引用”的简单问答场景

---

## 3. 智能体增强接口（复杂任务推荐）

基础前缀：`/api/v1/agent`

### 3.1 一步搜索
`POST /api/v1/agent/search`

请求体：`AgentSearchRequest`
- 核心字段：
  - `query` 必填
  - `excludeDevDocs`
  - `hop`
  - `topKDocs`
  - `topKEvidencePerDoc`
  - `recallMode`
  - `rerankModel`
  - `includeOverview`
  - `includeRawChunk`
  - `debug`
  - `async`
  - `filters`（见下）

`filters`：
- `dataSourceId` `uuid`
- `docTypes` `string[]`
- `dateFrom/dateTo`：`YYYY-MM-DD`

返回：`AgentSearchResponse`
- `taskId/taskStatus`
- `result`（与普通检索 `SearchResponse` 结构一致）
- `trace`（耗时、策略、过滤器、评分分解）

---

### 3.2 计划/执行分离

1) 生成计划：`POST /api/v1/agent/search/plan`  
2) 执行计划：`POST /api/v1/agent/search/execute`

适用：
- 多轮会话里固定检索参数，反复换页/执行

---

### 3.3 智能体回答

`POST /api/v1/agent/answer`

返回：`AgentAnswerResponse`
- `answer`
- `structuredAnswer`
- `citations`
- `usedDocIds`
- `confidence`
- `unansweredParts`
- `docOverview`
- `trace`

---

### 3.4 异步任务轮询

`GET /api/v1/agent/tasks/{taskId}`

返回：`TaskStatusResponse`
- `status/progressPercent/message`
- 完成后 `result` 可用

---

### 3.5 智能体文档分块

`GET /api/v1/agent/documents/{docId}/chunks`

参数与普通 `/documents/{id}/chunks` 基本一致。

---

## 4. 关键数据字段（AI 常用）

### 4.1 文档命中 `docHits[]`
- `docId`
- `docTitle`
- `sourceFile`（绝对路径）
- `sourceFilename`
- `relativePath`
- `score`
- `hitCount`
- `overview`

### 4.2 证据命中 `evidenceHits[]`
- `kind`：`knowledge_unit|chunk`
- `matchType`：`title|filename|fulltext|...`
- `docId/chunkId/unitId`
- `title/snippet/content`
- `subject/indicator`
- `sourceFile/sourceFilename/relativePath`
- `pageNo/sourceSpan`
- `score`

---

## 5. 推荐调用流程（给 AI）

### 流程 A：检索问答（高召回）
1. `GET /search?hop=both`
2. 取 `docHits` 前 N 篇做主上下文
3. 取 `evidenceHits` 做可引用证据
4. 对关键文档调用 `/documents/{id}/overview` 补结构化摘要
5. 必要时调用 `/documents/{id}/chunks?includeContent=true` 拉正文段
6. 输出答案并附引用（`docId + pageNo/sourceSpan`）

### 流程 B：文档交付（含下载）
1. `GET /search?hop=first`
2. 用户确认文档后，调用 `/documents/{id}/download`

### 流程 C：复杂智能体任务
1. `POST /agent/search`（必要时 `async=true`）
2. 若异步，轮询 `/agent/tasks/{taskId}`
3. 对结果再用 `/agent/answer` 做结构化整合

---

## 6. 调用示例

### 6.1 检索
```bash
curl "http://127.0.0.1:8012/api/v1/search?keyword=研究生教育管理历史&excludeDevDocs=true&hop=both&page=1&pageSize=20"
```

### 6.2 文档画像
```bash
curl "http://127.0.0.1:8012/api/v1/documents/{docId}/overview"
```

### 6.3 文档下载
```bash
curl -L "http://127.0.0.1:8012/api/v1/documents/{docId}/download" -o original-file.bin
```

### 6.4 智能体搜索
```bash
curl -X POST "http://127.0.0.1:8012/api/v1/agent/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query":"研究生教育管理的历史演进与政策变化",
    "excludeDevDocs":true,
    "hop":"both",
    "topKDocs":20,
    "topKEvidencePerDoc":8,
    "includeOverview":true,
    "includeRawChunk":false,
    "async":false
  }'
```

---

## 7. 版本说明

- 文档基于当前代码接口：
  - `QueryController`
  - `AgentQueryController`
  - `QueryDtos / AgentQueryDtos`
- 若后续 DTO 字段扩展，以实际返回 JSON 为准，建议 AI 侧做“字段容错读取”（兼容 `camelCase/snake_case`）。
