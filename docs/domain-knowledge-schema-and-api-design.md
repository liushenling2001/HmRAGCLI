# 面向智能体的领域知识编译系统数据模型与 API 细化设计

## 1. 文档目标

本文档在 `domain-knowledge-compilation-design.md` 基础上，继续细化以下内容：

1. 领域定义层的数据模型
2. 领域知识编译层的数据模型
3. 编译任务对象设计
4. 智能体消费对象设计
5. API 路由设计

目标是把“领域知识精炼系统”推进到可直接开发的层面。

---

## 2. 总体对象分层

系统建议新增三类核心对象：

1. `DomainDefinition`
2. `DomainCompilation`
3. `DomainMemoryPack`

其中：

1. `DomainDefinition` 负责定义知识空间
2. `DomainCompilation` 负责记录一次编译任务和编译产物
3. `DomainMemoryPack` 负责向智能体提供知识消费对象

---

## 3. 数据模型设计

## 3.1 `domain_definitions`

用于保存人工定义的领域。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| name | varchar(255) | 领域名称 |
| description | text | 领域描述 |
| goal | text | 该领域服务目标 |
| scope_rules_json | jsonb | 领域范围规则 |
| seed_queries_json | jsonb | 初始检索词 |
| include_data_sources_json | jsonb | 包含的数据源列表 |
| exclude_data_sources_json | jsonb | 排除的数据源列表 |
| priority | int | 优先级 |
| auto_refresh_enabled | boolean | 是否启用自动维护 |
| auto_refresh_cron | varchar(100) | 自动维护策略 |
| active_model_profile | varchar(100) | 默认模型配置 |
| status | varchar(50) | draft/active/archived |
| created_by | varchar(100) | 创建人 |
| metadata_json | jsonb | 其他元数据 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_domain_definitions_status`
2. `idx_domain_definitions_priority`
3. `idx_domain_definitions_auto_refresh_enabled`

## 3.2 `topic_definitions`

用于保存领域内专题树。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| domain_id | uuid | 所属领域 |
| parent_topic_id | uuid | 父专题 |
| name | varchar(255) | 专题名称 |
| description | text | 专题说明 |
| scope_rules_json | jsonb | 专题范围规则 |
| seed_queries_json | jsonb | 专题种子检索词 |
| priority | int | 优先级 |
| status | varchar(50) | active/disabled/archived |
| metadata_json | jsonb | 扩展元数据 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_topic_definitions_domain_id`
2. `idx_topic_definitions_parent_topic_id`
3. `idx_topic_definitions_status`

## 3.3 `domain_refine_jobs`

用于保存领域编译任务。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| job_type | varchar(50) | manual_domain/manual_topic/auto_incremental/auto_full |
| domain_id | uuid | 目标领域 |
| topic_id | uuid | 目标专题，可为空 |
| status | varchar(50) | queued/running/success/failed/cancelled |
| trigger_source | varchar(50) | user/scheduler/system |
| model_profile | varchar(100) | 本次编译使用的模型配置 |
| scope_snapshot_json | jsonb | 编译时的范围快照 |
| input_summary_json | jsonb | 编译输入摘要 |
| output_summary_json | jsonb | 编译输出摘要 |
| error_message | text | 失败原因 |
| started_at | timestamptz | 开始时间 |
| finished_at | timestamptz | 结束时间 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_domain_refine_jobs_domain_id`
2. `idx_domain_refine_jobs_topic_id`
3. `idx_domain_refine_jobs_status`
4. `idx_domain_refine_jobs_job_type`
5. `idx_domain_refine_jobs_created_at`

## 3.4 `domain_briefs`

领域级总览对象。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| domain_id | uuid | 所属领域 |
| refine_job_id | uuid | 来源任务 |
| version_no | int | 版本号 |
| status | varchar(50) | draft/reviewed/authoritative/maintained |
| summary | text | 领域总述 |
| domain_boundary | text | 边界说明 |
| core_concepts_json | jsonb | 核心概念 |
| core_topics_json | jsonb | 核心专题 |
| key_claims_json | jsonb | 关键结论 |
| key_metrics_json | jsonb | 关键指标 |
| conflicts_json | jsonb | 主要冲突 |
| timeline_summary_json | jsonb | 时间线摘要 |
| evidence_pack_id | uuid | 证据包 |
| llm_context_summary | text | 给 LLM 的压缩上下文 |
| source_coverage_json | jsonb | 证据覆盖情况 |
| compiled_at | timestamptz | 编译时间 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_domain_briefs_domain_id`
2. `idx_domain_briefs_status`
3. `idx_domain_briefs_compiled_at`

## 3.5 `topic_dossiers`

专题知识页，是最重要的知识编译对象。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| domain_id | uuid | 所属领域 |
| topic_id | uuid | 所属专题 |
| refine_job_id | uuid | 来源任务 |
| version_no | int | 版本号 |
| status | varchar(50) | draft/reviewed/authoritative/maintained |
| title | varchar(500) | 标题 |
| summary | text | 专题概述 |
| scope_text | text | 适用范围 |
| key_points_json | jsonb | 核心要点 |
| exceptions_json | jsonb | 例外情况 |
| conflicts_json | jsonb | 冲突摘要 |
| timeline_json | jsonb | 专题时间线 |
| keywords_json | jsonb | 主题关键词 |
| claim_group_id | uuid | 关联结论组 |
| evidence_pack_id | uuid | 关联证据包 |
| llm_context_summary | text | 给 LLM 的压缩上下文 |
| source_coverage_json | jsonb | 证据覆盖情况 |
| compiled_at | timestamptz | 编译时间 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_topic_dossiers_domain_id`
2. `idx_topic_dossiers_topic_id`
3. `idx_topic_dossiers_status`
4. `idx_topic_dossiers_compiled_at`

## 3.6 `claim_sets`

结构化结论集合。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| owner_type | varchar(50) | domain_brief/topic_dossier |
| owner_id | uuid | 归属对象 |
| claim_type | varchar(50) | fact/rule/observation/judgement/trend |
| claim_text | text | 结论文本 |
| confidence | numeric(4,3) | 置信度 |
| valid_scope | text | 适用范围 |
| effective_time_range | varchar(255) | 时间范围 |
| priority | int | 优先级 |
| conflict_set_id | uuid | 所属冲突组 |
| evidence_pack_id | uuid | 所属证据包 |
| metadata_json | jsonb | 扩展信息 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_claim_sets_owner`
2. `idx_claim_sets_claim_type`
3. `idx_claim_sets_priority`

## 3.7 `conflict_sets`

冲突集合对象。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| owner_type | varchar(50) | domain_brief/topic_dossier |
| owner_id | uuid | 归属对象 |
| conflict_type | varchar(50) | time/source/scope/definition |
| summary | text | 冲突摘要 |
| side_a_json | jsonb | 一方观点 |
| side_b_json | jsonb | 另一方观点 |
| possible_reason | text | 冲突原因 |
| resolution_hint | text | 消解建议 |
| evidence_pack_id | uuid | 关联证据包 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_conflict_sets_owner`
2. `idx_conflict_sets_conflict_type`

## 3.8 `evidence_packs`

证据包对象，用于回溯原文。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| owner_type | varchar(50) | domain_brief/topic_dossier/claim/conflict |
| owner_id | uuid | 归属对象 |
| primary_refs_json | jsonb | 主证据引用 |
| support_refs_json | jsonb | 补充证据引用 |
| quote_spans_json | jsonb | 精确引用段 |
| context_window_refs_json | jsonb | 上下文回查引用 |
| section_refs_json | jsonb | 章节回查引用 |
| citation_ready_refs_json | jsonb | 适合直接引用的内容 |
| metadata_json | jsonb | 扩展元数据 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

建议索引：

1. `idx_evidence_packs_owner`

---

## 4. 智能体消费对象

## 4.1 `DomainMemoryPack`

这是最终给智能体使用的聚合对象，不一定独立落表，可以通过多个对象拼装生成。

建议结构：

```json
{
  "domain": {
    "id": "uuid",
    "name": "领域名",
    "description": "领域描述"
  },
  "brief": {
    "summary": "...",
    "boundary": "...",
    "coreConcepts": [],
    "coreTopics": []
  },
  "topics": [
    {
      "topicId": "uuid",
      "title": "专题名",
      "summary": "...",
      "keyPoints": [],
      "claimRefs": [],
      "evidencePackId": "uuid"
    }
  ],
  "claims": [],
  "conflicts": [],
  "retrievalHooks": {
    "evidenceExpandApi": "/api/v1/evidence/expand"
  }
}
```

## 4.2 设计要求

面向智能体的对象必须同时满足：

1. 足够压缩，能直接进入上下文
2. 足够结构化，便于程序消费
3. 可追溯，能回查到底层正文

---

## 5. 证据引用对象设计

建议统一定义 `EvidenceRef`：

```json
{
  "docId": "uuid",
  "chunkId": "uuid",
  "knowledgeUnitId": "uuid",
  "sourceFile": "path-or-name",
  "pageNo": 12,
  "sourceSpan": "123-456",
  "quoteText": "精确引用文本",
  "contextMode": "quote|context|section"
}
```

说明：

1. `quoteText` 用于精确引用和核验
2. `contextMode` 用于告诉智能体扩展方式
3. `chunkId` 和 `knowledgeUnitId` 可以二选一或同时存在

---

## 6. API 设计

## 6.1 领域定义 API

### 6.1.1 创建领域

`POST /api/v1/domains`

请求：

```json
{
  "name": "电力现货市场",
  "description": "围绕电力现货市场规则、机制、指标和政策演进进行知识维护",
  "goal": "为智能体提供领域知识包",
  "scopeRules": {},
  "seedQueries": ["电力现货", "节点电价", "市场出清"],
  "includeDataSources": [],
  "excludeDataSources": [],
  "priority": 10,
  "autoRefreshEnabled": true,
  "autoRefreshCron": "daily",
  "activeModelProfile": "scheduled_refine_default"
}
```

### 6.1.2 查询领域列表

`GET /api/v1/domains`

### 6.1.3 查询领域详情

`GET /api/v1/domains/{id}`

### 6.1.4 更新领域

`PATCH /api/v1/domains/{id}`

## 6.2 专题定义 API

### 6.2.1 创建专题

`POST /api/v1/domains/{id}/topics`

### 6.2.2 更新专题

`PATCH /api/v1/topics/{id}`

### 6.2.3 查询领域专题树

`GET /api/v1/domains/{id}/topics`

## 6.3 编译任务 API

### 6.3.1 人工触发领域编译

`POST /api/v1/domains/{id}/refine`

请求：

```json
{
  "jobType": "manual_domain",
  "modelProfile": "manual_refine_large",
  "rebuildMode": "full"
}
```

### 6.3.2 人工触发专题编译

`POST /api/v1/topics/{id}/refine`

### 6.3.3 查询编译任务

`GET /api/v1/refine-jobs`

### 6.3.4 查询单个编译任务

`GET /api/v1/refine-jobs/{id}`

## 6.4 知识消费 API

### 6.4.1 获取领域知识包

`GET /api/v1/domains/{id}/memory-pack`

返回：

1. 当前有效领域总览
2. 专题映射
3. 主结论和冲突
4. 证据回查入口

### 6.4.2 获取专题知识页

`GET /api/v1/topics/{id}/dossier`

### 6.4.3 获取专题结论集合

`GET /api/v1/topics/{id}/claims`

### 6.4.4 获取证据包

`GET /api/v1/evidence-packs/{id}`

### 6.4.5 展开正文回溯

`POST /api/v1/evidence/expand`

请求：

```json
{
  "refs": [
    {
      "docId": "uuid",
      "chunkId": "uuid",
      "knowledgeUnitId": null
    }
  ],
  "mode": "context",
  "windowSize": 2
}
```

支持模式：

1. `quote`
2. `context`
3. `section`

---

## 7. 编译任务内部阶段建议

一条领域编译任务建议拆成以下阶段：

1. `scope_resolve`
2. `evidence_collect`
3. `topic_grouping`
4. `summary_compile`
5. `claims_compile`
6. `conflict_compile`
7. `evidence_bind`
8. `memory_pack_publish`

每阶段都建议记录：

1. 状态
2. 输入数
3. 输出数
4. 错误信息
5. 开始与结束时间

---

## 8. 与当前项目的衔接方式

## 8.1 继续复用的基础对象

当前项目已有：

1. `documents`
2. `chunks`
3. `knowledge_units`
4. `embedding_vector`
5. 全文检索与向量检索能力

新的领域编译系统不需要改写这些表，只需要在其上增加新对象。

## 8.2 初始证据采集方式

建议先复用当前查询层，作为领域编译任务的证据收集器：

1. 使用领域 `seed_queries`
2. 使用标签和结构化条件过滤
3. 拉取相关 `documents / chunks / knowledge_units`
4. 进入后续编译

## 8.3 未来演进

未来可以在不动底层解析流程的情况下继续增强：

1. 更强的专题聚类
2. 更稳定的冲突发现
3. 更细粒度的正文展开
4. 更智能的领域健康检查

---

## 9. 推荐优先实现内容

如果按最小可用版本推进，建议优先做：

### 第一步

1. `domain_definitions`
2. `topic_definitions`
3. `domain_refine_jobs`

### 第二步

1. `domain_briefs`
2. `topic_dossiers`
3. `evidence_packs`

### 第三步

1. `memory-pack`
2. `evidence/expand`
3. 自动任务调度

---

## 10. 最终结论

本方案建议把“领域知识精炼”实现为一套新增对象，而不是把现有检索系统硬改成在线临时总结器。

最终系统形态应为：

1. 人工定义领域与专题
2. 手工或自动触发领域编译任务
3. 生成可追溯的领域知识对象
4. 面向智能体输出稳定的领域知识包
5. 在需要时通过正文回查接口拿到底层原文

这样可以同时满足：

1. 长篇材料要点归纳
2. 专题级知识组织
3. 可追溯证据引用
4. 智能体写作和分析的稳定供给

