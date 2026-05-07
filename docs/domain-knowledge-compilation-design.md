# 面向智能体的领域知识精炼与编译式知识库落地设计

## 1. 文档目标

本文档基于当前 `HmRAGCLI` 项目的既有数据基础，提出一套新的领域知识精炼设计，用于解决以下问题：

1. 现有检索虽然已有改进，但对长篇材料、跨文档专题、领域全貌的覆盖仍然不足
2. LLM 在线回答仍较依赖临场检索和拼接，难以稳定把握长篇要点
3. 需要让知识库不仅“能搜到”，还要“能被智能体高质量使用”
4. 精炼结果必须可回溯到原文，并在写作时可进一步拉取正文上下文

本设计不替代当前文档解析、切块、知识单元抽取和向量化流程，而是在其上新增一层“领域知识编译层”。

---

## 2. 设计结论

系统目标不再只是传统 RAG，而是：

**原始证据库 + 领域知识编译层 + 智能体消费层**

其中：

1. 原始证据库继续保存 `documents / chunks / knowledge_units / embeddings`
2. 领域知识编译层把证据库编译为稳定、可维护、可溯源的领域知识对象
3. 智能体消费层优先使用编译后的领域知识，再按需回查原始正文

因此，未来查询系统会从“检索系统”升级为“检索 + 编译式知识服务系统”。

---

## 3. 设计边界

本设计包含：

1. 人工定义领域与专题
2. 人工发起的高质量领域精炼流程
3. 系统主动执行的领域知识归纳与增量维护流程
4. 面向智能体的知识包设计
5. 精炼结果的证据绑定与正文回溯机制
6. 建议的数据模型与 API 方向

本设计不包含：

1. 现有扫描、解析、切块、知识单元抽取的重写
2. 现有索引构建主流程的推翻
3. 图数据库与复杂图谱推理
4. 在线问答阶段的重型临时知识编译

---

## 4. 核心原则

### 4.1 原文永远保留

精炼层永远不替代原文，只负责：

1. 压缩
2. 组织
3. 聚合
4. 导航
5. 溯源

### 4.2 领域边界由人定义

领域和专题的边界不由系统自由发现，而由人从前端维护。系统只在既定边界内持续归纳和更新。

### 4.3 自动流程负责维护，不负责任意扩张知识空间

自动任务可以：

1. 识别新增证据
2. 识别受影响专题
3. 更新知识对象
4. 发现冲突和缺口

自动任务不直接决定新增领域边界。

### 4.4 精炼结果优先服务智能体

精炼结果最终不是面向人类报告，而是面向智能体消费。因此输出对象必须：

1. 可压缩
2. 可结构化读取
3. 可回溯
4. 可扩展到正文上下文

---

## 5. 总体架构

系统建议升级为四层结构：

1. 原始证据层
2. 领域定义层
3. 领域知识编译层
4. 智能体消费层

### 5.1 原始证据层

沿用现有对象：

1. `Document`
2. `Chunk`
3. `KnowledgeUnit`
4. `Embedding`

该层是最终事实和证据来源。

### 5.2 领域定义层

新增人工定义对象：

1. `DomainDefinition`
2. `TopicDefinition`
3. `ScopeRule`
4. `SeedQuery`

该层决定：

1. 什么内容属于某个领域
2. 领域内有哪些主要专题
3. 自动维护任务应该覆盖哪些范围

### 5.3 领域知识编译层

新增持久化知识对象：

1. `DomainBrief`
2. `TopicDossier`
3. `ConceptCard`
4. `TimelineBrief`
5. `EvidencePack`
6. `ClaimSet`
7. `ConflictSet`

该层是“熔炼后知识”的主要承载层。

### 5.4 智能体消费层

向智能体暴露：

1. 面向认知的知识包
2. 面向写作的证据包
3. 面向回查的正文展开接口

---

## 6. 两类精炼流程

系统内明确区分两类任务。

### 6.1 人工发起的领域精炼

特点：

1. 由人在前端明确指定领域或专题
2. 目标清晰，边界明确
3. 允许使用更大参数、更高质量模型
4. 适合做高质量专题编译和重编译

适用场景：

1. 新定义一个重要领域
2. 重做某个高价值专题
3. 对已有专题进行高质量升级
4. 面向重要智能体工作流生产高质量知识包

### 6.2 自动执行的主动领域归纳

特点：

1. 面向已存在的领域定义对象
2. 按计划任务定时运行
3. 重点做增量维护与持续归纳
4. 模型可以与人工流程区分开，优先平衡稳定性与成本

适用场景：

1. 每日吸收新增文档
2. 更新受影响的专题页
3. 识别冲突和失效结论
4. 维持领域知识对象不过期

### 6.3 二者的互补关系

建议规则：

1. 人工流程产出 `authoritative` 级结果
2. 自动流程产出 `draft` 或 `maintained` 级结果
3. 查询与智能体消费时优先级为：

`authoritative > reviewed_draft > maintained > raw evidence`

---

## 7. 人工定义对象

### 7.1 DomainDefinition

建议字段：

1. `id`
2. `name`
3. `description`
4. `goal`
5. `scope_rules`
6. `seed_queries`
7. `include_data_sources`
8. `exclude_data_sources`
9. `priority`
10. `auto_refresh_enabled`
11. `auto_refresh_cron`
12. `active_model_profile`
13. `status`
14. `created_by`
15. `created_at`
16. `updated_at`

含义：

1. 由人定义领域边界
2. 指定自动维护策略
3. 指定默认编译模型配置

### 7.2 TopicDefinition

建议字段：

1. `id`
2. `domain_id`
3. `parent_topic_id`
4. `name`
5. `description`
6. `seed_queries`
7. `scope_rules`
8. `priority`
9. `status`
10. `created_at`
11. `updated_at`

含义：

1. 用树状结构维护领域内专题
2. 支撑人工和自动编译的最小单元

### 7.3 ScopeRule

建议包含：

1. 关键词
2. 排除词
3. 文档类型限制
4. 来源限制
5. 时间范围限制
6. 标签条件
7. 结构化字段条件

---

## 8. 编译产物对象

### 8.1 DomainBrief

作用：

1. 为智能体快速建立领域整体认知
2. 定义领域边界、关键概念、核心问题、主要结论

建议字段：

1. `id`
2. `domain_id`
3. `summary`
4. `domain_boundary`
5. `core_concepts`
6. `core_topics`
7. `key_claims`
8. `key_metrics`
9. `major_conflicts`
10. `timeline_summary`
11. `evidence_refs`
12. `llm_context_summary`
13. `compiled_from_job_id`
14. `version`
15. `status`
16. `compiled_at`

### 8.2 TopicDossier

作用：

1. 对某专题形成稳定的知识页面
2. 是智能体回答某一专题问题时的主消费对象

建议字段：

1. `id`
2. `domain_id`
3. `topic_id`
4. `title`
5. `summary`
6. `scope`
7. `key_points`
8. `claims`
9. `exceptions`
10. `conflicts`
11. `timeline`
12. `keywords`
13. `evidence_refs`
14. `recommended_queries`
15. `llm_context_summary`
16. `source_coverage`
17. `compiled_from_job_id`
18. `version`
19. `status`
20. `compiled_at`

### 8.3 ClaimSet

作用：

1. 把专题页中的结论结构化
2. 允许智能体以“结论集合”而不是长文摘要的方式读取知识

建议字段：

1. `id`
2. `topic_dossier_id`
3. `claim_text`
4. `claim_type`
5. `confidence`
6. `valid_scope`
7. `effective_time_range`
8. `evidence_refs`
9. `conflict_refs`
10. `priority`

### 8.4 ConflictSet

作用：

1. 显式表达冲突信息
2. 避免智能体把互相矛盾的结论混成一个答案

建议字段：

1. `id`
2. `topic_dossier_id`
3. `conflict_type`
4. `summary`
5. `side_a_claims`
6. `side_b_claims`
7. `possible_reason`
8. `resolution_hint`
9. `evidence_refs`

### 8.5 EvidencePack

作用：

1. 把精炼结论与原始证据绑定
2. 在智能体写作时提供可追溯正文

建议字段：

1. `id`
2. `owner_type`
3. `owner_id`
4. `primary_refs`
5. `support_refs`
6. `quote_spans`
7. `context_window_refs`
8. `section_refs`
9. `citation_ready_refs`
10. `compiled_at`

---

## 9. 证据回溯设计

### 9.1 回溯不是简单引用 id

精炼对象不能只保存：

1. `doc_id`
2. `chunk_id`
3. `knowledge_unit_id`

还必须保存可以直接服务写作与核验的上下文信息。

### 9.2 建议支持三种回溯粒度

#### 9.2.1 quote

用于精确引用。

内容包括：

1. 支持该结论的精确句段
2. 原文定位
3. 页码、span、文件名

#### 9.2.2 context

用于语义理解。

内容包括：

1. 命中句段上下文
2. 临近 chunk
3. 补充解释文本

#### 9.2.3 section

用于长文生成。

内容包括：

1. 该证据所在章节
2. 更大段的完整上下文
3. 同专题的相邻章节信息

### 9.3 结论与证据的绑定方式

每条结论建议绑定：

1. `source_refs`
2. `support_spans`
3. `context_window_refs`
4. `quote_priority`

这样智能体可以先读结论，再主动拉正文。

---

## 10. 智能体消费模式

### 10.1 目标

智能体不应主要依赖原始 chunk 检索，而应优先消费领域知识包。

### 10.2 推荐调用顺序

#### 模式 A：先认知后回查

1. 加载 `DomainBrief`
2. 定位相关 `TopicDossier`
3. 读取 `ClaimSet`
4. 基于 `EvidencePack` 拉正文上下文
5. 生成答案或文稿

#### 模式 B：写作增强

1. 给出写作目标
2. 先读取相关专题的精炼内容
3. 对每个段落主题拉取正文回查结果
4. 以“精炼认知 + 原文支撑”共同供给 LLM

### 10.3 给智能体的最终知识包

建议定义 `DomainMemoryPack`，包含：

1. `domain_brief`
2. `topic_map`
3. `topic_dossiers`
4. `claim_sets`
5. `conflict_sets`
6. `timeline`
7. `retrieval_hooks`
8. `evidence_pack_refs`

---

## 11. 编译流程设计

### 11.1 人工精炼流程

建议步骤：

1. 人工在前端创建或选择领域/专题
2. 人工选择模型配置
3. 系统根据领域定义执行检索
4. 系统聚合原始证据
5. 大模型生成高质量专题编译结果
6. 系统做结构化拆分和证据绑定
7. 生成 `DomainBrief / TopicDossier / ClaimSet / EvidencePack`
8. 标记为 `authoritative` 或 `reviewed`

### 11.2 自动归纳流程

建议步骤：

1. 定时任务扫描最近增量数据
2. 判断受影响的领域和专题
3. 重新拉取该领域相关证据
4. 用自动维护模型生成更新草稿
5. 更新 `maintained` 版本对象
6. 输出变更摘要、冲突提示、缺口提示

### 11.3 增量维护规则

自动流程建议仅对以下情况触发：

1. 新增文档进入领域范围
2. 已有文档内容变化
3. 新证据影响已有结论
4. 关键专题超过设定时效未重编译

---

## 12. 模型配置建议

### 12.1 模型分工

建议至少定义三类模型配置：

1. `online_answer_model`
2. `manual_refine_model`
3. `scheduled_refine_model`

### 12.2 使用原则

1. 在线问答模型只消费知识包，不承担重编译
2. 人工精炼模型可以使用更大参数、更高质量配置
3. 自动归纳模型优先平衡成本与稳定性

### 12.3 结果优先级

查询或智能体消费时，建议优先使用：

1. `manual_refine_model` 生成且审核通过的结果
2. 自动维护生成且未过期的结果
3. 原始检索结果

---

## 13. API 设计方向

### 13.1 人工定义 API

建议新增：

1. `POST /api/v1/domains`
2. `GET /api/v1/domains`
3. `GET /api/v1/domains/{id}`
4. `PATCH /api/v1/domains/{id}`
5. `POST /api/v1/domains/{id}/topics`
6. `PATCH /api/v1/topics/{id}`

### 13.2 编译任务 API

建议新增：

1. `POST /api/v1/domains/{id}/refine`
2. `POST /api/v1/topics/{id}/refine`
3. `POST /api/v1/domains/{id}/rebuild`
4. `GET /api/v1/refine-jobs`
5. `GET /api/v1/refine-jobs/{id}`

### 13.3 智能体消费 API

建议新增：

1. `GET /api/v1/domains/{id}/memory-pack`
2. `GET /api/v1/topics/{id}/dossier`
3. `GET /api/v1/topics/{id}/claims`
4. `GET /api/v1/evidence-packs/{id}`
5. `POST /api/v1/evidence/expand`

### 13.4 回溯 API

建议支持：

1. `quote` 模式
2. `context` 模式
3. `section` 模式

---

## 14. 与当前项目的兼容关系

本方案基于当前已有数据底座，不推翻现有流程。

### 14.1 继续复用的对象

1. `documents`
2. `chunks`
3. `knowledge_units`
4. `embedding_vector`
5. 现有全文检索与向量检索能力

### 14.2 新增层

建议只新增以下领域编译对象表，不修改现有抽取主流程：

1. `domain_definitions`
2. `topic_definitions`
3. `domain_refine_jobs`
4. `domain_briefs`
5. `topic_dossiers`
6. `claim_sets`
7. `conflict_sets`
8. `evidence_packs`

### 14.3 查询层升级方向

现有 `search` 保留。

在其基础上新增：

1. `brief`
2. `dossier`
3. `evidence_pack`
4. `memory_pack`

---

## 15. 推荐实施顺序

建议分四阶段落地。

### 第一阶段：定义层

1. 前端支持人工定义领域与专题
2. 后端保存 `DomainDefinition / TopicDefinition`

### 第二阶段：人工精炼

1. 实现人工触发的领域/专题编译任务
2. 实现 `DomainBrief / TopicDossier / EvidencePack`
3. 先服务高价值专题

### 第三阶段：自动归纳

1. 实现按领域定时任务
2. 实现增量维护
3. 产出 `maintained` 级结果

### 第四阶段：智能体集成

1. 智能体优先消费 `DomainMemoryPack`
2. 支持写作时正文回查
3. 支持领域级长期工作流

---

## 16. 最终结论

当前项目的下一步重点不应只是继续微调检索，而应建设“领域知识编译层”。

推荐结论如下：

1. 领域与专题由人从前端定义
2. 系统围绕这些定义持续做知识归纳和重编译
3. 精炼结果不是普通摘要，而是面向智能体消费的稳定知识对象
4. 所有精炼结果都必须保留到原文的可追溯链路
5. 智能体使用时，优先读取领域知识包，再按需回查正文

这样，系统能力会从：

**能检索文档**

升级为：

**能为智能体持续提供可追溯、可维护、可扩展的领域知识**

