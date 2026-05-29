# 知识图谱可回溯流水线实施计划

本文档把 `docs/knowledge-graph-evolution-upgrade-plan.md` 拆成可执行开发任务。原则是：

```text
低风险步骤合并；
高风险步骤拆开；
解析/抽取阶段按 chunk batch 断点续跑；
系统逻辑修复后，可以从错误步骤继续，不从头重来。
```

## 1. 总体实施顺序

| 阶段 | 名称 | 目标 | 是否必须先做 |
| --- | --- | --- | --- |
| P0 | 当前错误隔离 | 停止继续把 definition 当状态，标记旧状态来源 | 是 |
| P1 | Pipeline 元数据 | 建 step run、artifact、batch 状态机 | 是 |
| P2 | Batch 抽取断点续跑 | LLM 抽取按 chunk batch 可恢复 | 是 |
| P3 | 候选账本 | raw response、candidate、evidence 持久化 | 是 |
| P4 | 候选校验与规范构建 | EntityDescription、CanonicalEntity、CanonicalFact | 是 |
| P5 | 融合批次 | entity/fact fusion 可撤销 | 否 |
| P6 | 状态与演化派生 | EntityState/EVOLVES_TO 从 facts 派生 | 否 |
| P7 | Neo4j 投影与前端 | 主图、详情、质量门禁、重跑入口 | 否 |

## 2. P0：当前错误隔离

### 目标

先阻止旧错误继续扩大：

```text
entity.definition -> EntityState
```

必须废止。旧数据不直接删除，先标记和迁移。

### 任务

1. 在代码级阻断旧错误：`persistLocalGraph`、Neo4j writer、Projection writer 均不得从 `entity.definition` 创建 `EntityState`。
2. 增加写入断言：如果来源是 `entity.definition` 且目标是 `EntityState`，该 stepRun 直接失败，并记录 `stateFromDescriptionCount`。
3. 增加迁移 dry-run：先输出将影响多少旧状态、多少可迁移描述、多少缺 evidence、多少迁移失败候选，人工确认后再执行。
4. 对旧 Neo4j 中由 definition 生成的状态标记：

```text
s.sourceKind = 'legacy_definition_state'
s.status = 'legacy'
s.legacyMarkedByStepRunId
s.legacyMarkedByMigrationBatchId
s.legacyOriginalStatus
s.legacyOriginalSourceKind
s.migrationStatus: pending | migrated | skipped | failed
s.migrationReason
```

5. 将旧 `s.definition` 迁移为 `EntityDescription` 候选，并记录：

```text
sourceType = legacy_entity_state
sourceStateId
sourceEntityId
sourceFactIds
evidenceIds
sourceChunkIds
createdByStepRunId
migrationBatchId
```

6. legacy EntityState 默认不得进入 P6 Derivation 输入，也不得进入 P7 默认投影；只允许在 legacy review/debug 页面查看。
7. 前端对 legacy state 加提示，不再作为真实状态展示。

### 验收

1. `清华大学` 的“一所高等院校”类内容显示为实体描述，而不是状态。
2. 旧状态仍可追溯，但默认不参与新状态派生。
3. 继续运行图谱任务时，不会再产生新的 `legacy_definition_state`。
4. 迁移可 dry-run、可审计，失败项可定位。

## 3. P1：Pipeline 元数据

### 目标

建立所有步骤的可回溯元数据。

### PostgreSQL 表建议

#### `graph_pipeline_runs`

```text
id
graph_batch_id
scope_type: document | source_file | corpus
scope_id
status
created_at
started_at
finished_at
code_version
config_version
created_by
```

#### `graph_pipeline_step_runs`

```text
id
pipeline_run_id
graph_batch_id
step_name
status: pending | running | success | partial_success | failed | cancelled | superseded
code_version
config_version
prompt_version
model
lease_owner
lease_expires_at
heartbeat_at
max_runtime_seconds
started_at
finished_at
input_count
output_count
rejected_count
error_count
error_code
error_class
retryable
error_details_json
failed_phase
retry_of_step_run_id
```

#### `graph_pipeline_step_attempts`

```text
id
step_run_id
attempt_no
status: running | success | failed | timeout | cancelled | stale
started_at
finished_at
error_code
error_message
error_details_json
```

#### `graph_artifacts`

```text
id
pipeline_run_id
step_run_id
artifact_type
artifact_version
schema_version
scope_type
scope_id
content_hash
storage_ref
metadata_json
status
producer_step_run_id
producer_code_version
producer_config_version
parent_artifact_ids_json
superseded_by_artifact_id
invalidated_by_step_run_id
created_at
```

#### `graph_step_run_inputs` / `graph_step_run_outputs`

```text
id
step_run_id
artifact_id
role
created_at
```

#### `graph_active_artifacts`

```text
scope_type
scope_id
artifact_type
active_artifact_id
updated_by_step_run_id
updated_at
```

同一 `scope_type + scope_id + artifact_type` 只能有一个 active artifact。active 指针切换必须与 output artifact 写入、旧 artifact supersede 在同一事务内完成。

### 服务任务

1. `GraphPipelineService`：创建 pipeline run、step run、artifact。
2. 所有图谱任务写入 step run 状态。
3. 每一步失败时记录 last_error，不覆盖历史。
4. 识别 stale running：`running` 且 `lease_expires_at < now` 时转为 `failed/retryable` 或 `stale`。
5. 每一步重跑必须生成新 stepRun，不能覆盖旧 stepRun 或旧 artifact。

### 验收

1. 前端能看到每个 graphBatch 的阶段状态。
2. 任一步失败，能看到错误阶段、输入数量、输出数量、错误摘要。
3. 服务中断后，running step 可被 lease 回收。
4. 一个 stepRun 可以有多个输入/输出 artifact。
5. active artifact 切换不会出现两个 active 版本。

## 4. P2：Batch 抽取断点续跑

### 目标

LLM 抽取按 chunk batch 运行。网络错误、模型超时、服务重启后，只重跑失败或未完成 batch。

### PostgreSQL 表建议

#### `graph_extraction_batches`

```text
id
document_id
source_file_id
pipeline_run_id
graph_batch_id
batch_no
chunk_ids_json
chunk_hash
input_hash
prompt_version
extract_schema_version
model_provider
model_name
model_params_hash
status: pending | running | success | failed | partial_success | superseded
attempt_count
latest_attempt_id
parsed_candidate_count
active_candidate_count
rejected_candidate_count
parse_error_count
error_code
error_message
retry_mode: whole_batch | failed_segments
completion_policy: all_segments_success | tolerate_partial
segment_status_json
lease_owner
lease_expires_at
started_at
finished_at
created_at
updated_at
```

#### `graph_extraction_attempts`

```text
id
batch_id
attempt_no
attempt_kind: llm_request | raw_response_reparse
status: running | success | failed | parse_failed | timeout | cancelled | partial_success
parser_version
source_attempt_id
request_payload_hash
raw_request
raw_response
http_status
provider_request_id
token_usage_json
started_at
finished_at
error_code
error_message
created_at
```

#### `graph_extraction_parse_errors`

```text
id
batch_id
attempt_id
parser_version
error_path
error_message
raw_fragment
severity
created_at
```

### 幂等 key

```text
batchKey =
documentId
+ batchNo
+ chunkIds
+ chunkHash
+ promptVersion
+ extractSchemaVersion
+ modelProvider
+ modelName
+ modelParamsHash
```

不要只用 `docId + batchNo`。

### 续跑选择逻辑

继续抽取时只选择：

```text
pending
failed
partial_success
running 且 lease_expires_at < now
```

跳过：

```text
success 且 batchKey 未变化
```

作废：

```text
chunkHash / promptVersion / schemaVersion / modelParamsHash 变化的旧 batch
```

默认只实现 `retry_mode = whole_batch`。`partial_success` 默认不进入后续 Canonical Build；整批重试成功后，旧 partial candidates 全部 superseded，新 attempt candidates 成为 active。`failed_segments` 只能在后续版本引入，必须有 segment 级状态和合并策略后才能启用。

并发领取 batch 必须是原子操作。只有成功拿到 lease 的 worker 才能调用 LLM：

```sql
UPDATE graph_extraction_batches
SET status = 'running', lease_owner = ?, lease_expires_at = ?
WHERE id = ?
  AND status IN ('pending', 'failed', 'partial_success')
RETURNING id
```

也可以使用 `SELECT ... FOR UPDATE SKIP LOCKED` 实现批量领取。

### 失败处理

网络错误、HTTP 5xx、模型超时：

```text
attempt.status = failed 或 timeout
batch.status = failed
raw_response 可为空
```

JSON 解析失败：

```text
attempt.status = parse_failed
batch.status = failed
raw_response 必须保存
```

解析失败后，若 parser 修复，可以从 raw_response 重新解析，不一定重新请求 LLM。重新解析必须创建新的 attempt：

```text
old attempt = parse_failed
new attempt.attempt_kind = raw_response_reparse
new attempt.source_attempt_id = old attempt id
new attempt.parser_version = fixed parser version
```

不得复用旧 attemptId，也不得覆盖旧 raw_response。

Partial success：

```text
attempt.status = partial_success
batch.status = partial_success
可解析 candidate 入账本
不可解析部分记录 parse_error
```

默认：

```text
partial_success 不进入 Canonical Build
```

除非配置 `toleratePartial=true`，并且前端必须明确提示存在缺失。

### 事务要求

一次 batch 成功写入必须在同一事务内完成：

```text
写 attempt success
写 candidates
更新 batch success + candidateCount
```

如果 candidate 写失败，batch 不能标记 success。

`parse_failed` 和 `partial_success` 也必须有事务边界：

```text
parse_failed: attempt parse_failed + raw_response + parse_errors + batch failed
partial_success: attempt partial_success + parsed candidates + parse_errors + batch partial_success
```

lease 过期后的迟到结果不得写 active candidates。attempt 写回时必须校验：

```text
attempt_id == batch.latest_attempt_id
lease_owner == current_worker
lease_expires_at >= now
```

校验失败时，attempt 只能标记为 `stale/cancelled`，不能把 batch 标 success。

### 验收

1. 人为断网后，成功 batch 不重复请求 LLM。
2. 失败 batch 下次继续运行。
3. parse_failed 保存 raw_response，修复 parser 后可重新解析。
4. running 超时后可自动回收。

## 5. P3：候选账本

### 目标

所有 LLM 输出先进入候选账本，不直接写全局主图。

### 表建议

#### `graph_candidates`

```text
id
candidate_identity_key
candidate_version_id
candidate_type: entity | fact | description | identity | transition
batch_id
attempt_id
pipeline_run_id
graph_batch_id
document_id
chunk_ids_json
local_index
source_mention_id
source_fact_id
name
entity_type
subject_mention_id
predicate
object_mention_id
object_text
object_type
value
fact_kind
statement
valid_from
valid_to
confidence
payload_hash
payload_json
evidence_chunk_id
source_span
status: active | rejected | suspect | superseded
reject_reason
created_at
updated_at
```

### Candidate key

候选 key 不得只依赖 LLM 的 `mentionId/factId`。最终定义：

```text
stableLocalId = sourceMentionId/sourceFactId 如果同一 attempt 内唯一，否则 localIndex
candidateIdentityKey = batchKey + candidateType + stableLocalId + payloadHash
```

如果同一个候选需要保留历史版本：

```text
candidateVersionId = candidateIdentityKey + attemptId
```

### 入账规则

1. 同一 attempt 内按 `candidateVersionId` 幂等 upsert。
2. 新版本成功后旧版本标记 superseded，不物理删除。
3. raw payload 永远保留。
4. 无 evidenceChunkId 的候选标记 suspect 或 rejected。
5. 同一 `candidateIdentityKey` 最多只能有一条 active 记录。

数据库约束建议：

```text
unique(candidate_version_id)
partial unique(candidate_identity_key) where status = 'active'
```

### 验收

1. 重试同一 batch 不产生重复候选。
2. promptVersion 变化后，新旧候选能区分。
3. 可从候选记录回到 raw response 和 chunk。

## 6. P4：候选校验与规范构建

### 目标

把候选转为规范实体、事实和描述；不要把属性值建成实体；不要把描述建成状态。

### 表建议

#### `graph_canonical_entities`

```text
id
canonical_key
canonical_name
entity_type
normalized_key
representative_description_id
status: active | suspect | rejected | superseded
build_version
created_by_step_run_id
source_candidate_ids_json
created_at
updated_at
```

#### `graph_entity_descriptions`

```text
id
entity_id
description_text
description_type: definition | summary | role_description | source_phrase
source_candidate_id
description_key
evidence_chunk_id
source_span
confidence
status: active | rejected | superseded
build_version
created_at
updated_at
```

#### `graph_canonical_facts`

```text
id
fact_key
subject_entity_id
predicate
object_entity_id
value
object_kind: entity | value
object_type
fact_kind
statement
valid_from
valid_to
confidence
status: active | suspect | rejected | superseded
build_version
source_candidate_ids_json
evidence_ids_json
created_by_step_run_id
created_at
updated_at
```

`CanonicalEntity` 不直接保存描述文本。真实描述全部进入 `graph_entity_descriptions`；代表描述只是 `representative_description_id` 指向的派生选择结果，可重算、可替换。

### 校验规则

必须拒绝或降级：

1. 文件名、导入编号、文档标题。
2. Date/Number/Text 默认实体候选。
3. 过长短语实体，如“刘延东副总理在国务院学位委员会”。
4. 无 chunkId/sourceSpan 的 fact。
5. 空 statement 且无 predicate/value 的 fact。
6. subject/object mention 解析失败的 relation_fact。
7. subject/object 虽可解析，但无法在 evidence span 中定位或与 predicate 距离过远的 fact。
8. 跨句 fact 默认 suspect，除非有明确指代链证据。
9. 标题、文件名、来源机构不能自动作为 fact subject。
10. statement 无法从 evidence 还原原文含义的 fact。

必须转换：

1. `entity.definition` -> `EntityDescription`。
2. 定义类事实 -> `description_fact` 或定义类 `attribute_fact`。
3. 时间、金额、编号、数量 -> `CanonicalFact.value`。
4. object_mention 是 Date/Number/Money/Phone/Email/Code/FieldName 时，不解析 `object_entity_id`，写入 `value/value_type`，并设置 `object_kind=value`。
5. 字段名候选只能作为 `predicate/attribute_key`，不允许进入 `CanonicalEntity`。

### 事实 key 与证据聚合

```text
fact_key =
subject_entity_id
+ normalized_predicate
+ object_entity_id/value_normalized
+ fact_kind
+ valid_from/valid_to
+ condition/context_hash
```

完全相同 `fact_key` 合并为一个 `CanonicalFact`，聚合 evidence。时间、条件、否定、范围不同的事实不得合并，只能标记为 `similar_fact_candidate` 或进入 `ConflictRecord`。

### 描述去重

```text
description_key = entity_id + normalized_description + description_type
```

相同 `description_key` 合并 evidence；近似描述可以聚类，但不得删除原始描述记录。

### 验收

1. `清华大学` 描述进入 `graph_entity_descriptions`。
2. `2018-12`、`万元` 不进入 active canonical entity。
3. `definition` 不生成 EntityState。
4. rejected/suspect 记录可查询。

## 7. P5：融合批次

### 目标

实体融合和事实融合独立批次化，可撤销、可重跑。

### 表建议

#### `graph_fusion_batches`

```text
id
fusion_type: entity | fact
input_version
input_canonical_entity_version
input_canonical_fact_version
fusion_rule_version
llm_config_version
status
summary_json
started_at
finished_at
created_at
```

#### `graph_fusion_clusters`

```text
id
fusion_batch_id
cluster_type: entity | fact
representative_id
member_ids_json
decision: candidate | confirmed | rejected
reason
confidence
evidence_ids_json
status: active | superseded
created_at
updated_at
```

#### `graph_fusion_applied_ops`

```text
id
fusion_batch_id
target_type
target_id
before_json
after_json
projection_batch_id
status: active | reverted
created_at
reverted_at
```

#### `graph_fusion_decision_evidence`

```text
id
fusion_batch_id
cluster_id
decision_source: rule | llm | manual
prompt_version
raw_request
raw_response
reason
confidence
created_at
```

#### `graph_conflict_records`

```text
id
conflict_type: value_conflict | time_conflict | type_conflict
subject_entity_id
predicate
candidate_fact_ids_json
resolution_status
created_at
updated_at
```

### 融合门禁

1. 同名同类型不能单独作为 confirmed 条件。自动 confirmed 必须同时满足：normalized name 完全一致、entity_type 一致、scope/context 不冲突、至少有共同 alias/identity_fact/来源证据、没有时间/组织/项目归属冲突。
2. 跨类型融合默认 candidate 或 suspect。
3. 别名、简称、标题派生名称不能直接 confirmed。
4. 缺少共同证据的相似名称不自动合并。
5. 错误融合撤销时 supersede cluster，不删除原实体/事实，并回滚 `graph_fusion_applied_ops`。
6. P4 只做 graphBatch/document 内规范化；P5 才做跨文档融合。
7. 只有 active + confirmed 的 CanonicalEntity/CanonicalFact 可以进入 Projection；suspect/rejected/candidate cluster 不进入默认 Neo4j 主图。
8. 融合撤销后，受影响的 Projection、Derivation、SearchIndex 必须标记过期并触发重建。

### 验收

1. 可以只撤销某个 fusionBatch。
2. 撤销后原 canonical entity/fact 仍存在。
3. 新融合批次可以复用已有规范实体和事实。

## 8. P6：状态与演化派生

### 目标

从 active canonical facts 和 fusion result 派生状态与演化，而不是从 entity definition 生成。

### 表建议

#### `graph_derivation_batches`

```text
id
derivation_type: state | evolution
input_canonical_entity_version
input_canonical_fact_version
input_entity_fusion_batch_id
input_fact_fusion_batch_id
state_rule_version
evolution_rule_version
inferred_evolution_enabled
status
summary_json
started_at
finished_at
created_at
```

#### `graph_entity_states`

```text
id
entity_id
state_label
state_kind: phase | version | role_context | temporal_snapshot
valid_from
valid_to
state_signature
source_fact_ids_json
evidence_ids_json
derivation_batch_id
confidence
status: active | suspect | superseded
created_at
updated_at
```

大数据版本应优先使用关联表而不是 JSON 存引用：

```text
graph_entity_state_facts
graph_entity_state_evidence
graph_evolution_edge_facts
graph_evolution_edge_evidence
```

#### `graph_evolution_edges`

```text
id
from_state_id
to_state_id
entity_id
transition_type
reason
inferred
source_fact_ids_json
evidence_ids_json
derivation_batch_id
confidence
status: active | suspect | superseded
created_at
updated_at
```

### 状态生成条件

状态生成不是简单 OR。必须同时满足：

1. 来源 factKind 不得是 `description_fact` 或 `identity_fact`。
2. 至少有一个 state anchor：`phase`、`version`、`role_context` 或显式 `transition_fact`。
3. 至少有一条非描述事实作为属性或关系支撑。
4. 必须有 evidence。

仅有时间，不能生成 EntityState。

禁止：

```text
entity.definition -> EntityState
EntityDescription -> EntityState
description_fact -> EntityState
```

`EntityDescription` 不得作为 EntityState 的 sourceFact；description 只能在详情页展示，最多作为人工审查背景。`stateFromDescriptionCount` 必须检查直接来源和递归 lineage。

P6 输入过滤：

```text
included factKind: attribute_fact, relation_fact, transition_fact
excluded factKind: description_fact, identity_fact
```

### 演化推断规则

`inferred EVOLVES_TO` 默认关闭。只有同时满足以下条件才允许生成：

1. 同一 canonical entity。
2. 两个 state 都是 confirmed active。
3. state_kind 是 phase 或 version。
4. 时间区间不冲突。
5. 有明确升级、修订、替代、二期、版本顺序证据。
6. confidence 达阈值。

仅按时间排序不能生成演化边，只能进入 `evolution_candidate`。

### 派生回滚范围

撤销或重跑 derivationBatch 时必须同步作废：

```text
graph_entity_states
graph_evolution_edges
StateConflict
Neo4j EntityState 节点
Neo4j EVOLVES_TO 边
实体详情状态时间线缓存
演化子图缓存
查询索引中的状态/演化条目
```

如果状态被 superseded，所有引用该 stateId 的 evolution edge 必须同步 superseded。

### 验收

1. 修复 stateRuleVersion 后，只重跑 P6，不重跑 LLM。
2. 旧 derivationBatch 生成的状态可标记 superseded 或删除投影。
3. 每个状态能列出 source facts 和 evidence。
4. 演化边区分 explicit 和 inferred。

## 9. P7：Neo4j 投影与前端

### 目标

Neo4j 是当前投影，不是唯一真相。前端按业务语义展示。

### 投影规则

1. 主图：只显示 confirmed active `Entity --relation_fact--> Entity`。
2. 实体详情：显示 EntityDescription、属性事实、相关关系、confirmed active 状态时间线。
3. 演化图：只在实体详情子视图显示。
4. rejected/suspect 默认不展示，只在质量审查视图展示。
5. Neo4j 节点和关系都写入：

```text
projectionBatchId
sourceCanonicalIds
sourceFactIds
sourceCandidateIds
createdByStepRunId
status
```

默认主图过滤条件：

```text
CanonicalFact.status = active
factKind = relation_fact
fusionDecision = confirmed 或无需融合确认
subject/object 都是 active CanonicalEntity
subject/object 不是 Date/Number/Text/FieldName/Filename
source candidates 不含 rejected/suspect
evidence 存在
```

`EntityState`、`EntityDescription`、`EVOLVES_TO` 绝不进入默认主图。suspect state 只能在质量审查视图展示。

### 前端入口

1. 图谱流水线总览。
2. 按阶段查看：抽取、候选、规范、融合、派生、投影。
3. 失败 batch 继续运行。
4. 从指定步骤重跑。
5. 查看 rejected/suspect。
6. 查看质量指标。

从指定步骤重跑必须有安全机制：

```text
影响预览：会作废哪些 batch/projection/cache
范围选择：文档/项目/批次/实体
二次确认
新建 stepRun，不覆盖旧 stepRun
运行锁：同一 graphBatch 同阶段不能并发重跑
回滚按钮：恢复上一 active projection
```

### 验收

1. 可以从失败 extraction batch 继续。
2. 可以只重建 Neo4j 投影。
3. 主图不显示 Date/Number/Text 伪实体。
4. 实体状态不和普通关系混在一张图里。

## 10. 质量指标与审查任务

每个 graphBatch 输出：

```text
chunkCount
extractedChunkCount
batchCount
failedBatchCount
parseFailedBatchCount
partialSuccessBatchCount
candidateEntityCount
candidateFactCount
validatedCandidateCount
rejectedCandidateCount
suspectCandidateCount
canonicalEntityCount
canonicalFactCount
descriptionCount
relationFactCount
attributeFactCount
identityFactCount
transitionFactCount
stateCount
evolutionEdgeCount
projectionNodeCount
projectionEdgeCount
dateAsEntityCount
numberAsEntityCount
textAsEntityCount
fieldNameAsEntityCount
filenameAsEntityCount
longPhraseEntityCount
noEvidenceFactCount
lowConfidenceFactCount
ambiguousSubjectFactCount
duplicateRelationCount
stateFromDescriptionCount
```

必须提供审查列表：

1. 状态数异常实体。
2. 高重复关系。
3. Date/Number/Text 实体候选。
4. 文件名/标题污染候选。
5. 长短语误抽实体。
6. subject/object 解析失败事实。
7. 无 evidence 的事实。
8. 每类 rejected 抽样。
9. 每类 suspect 抽样。
10. 融合簇抽样。
11. 状态派生抽样。
12. 显式/推断演化抽样。

审查界面必须显示：

```text
原文证据
LLM 原始输出
规范化结果
拒绝或 suspect 原因
置信度
所属 batch / attempt / stepRun
是否进入主图
```

硬性指标：

```text
stateFromDescriptionCount = 0
```

如果大于 0，该批次不能进入 Projection。

## 11. 开发风险红线

以下设计禁止进入实现：

1. `EntityState` 由 `entity.definition` 直接生成。
2. Neo4j 当前图作为唯一真相。
3. 成功 extraction batch 被重复请求 LLM。
4. parse_failed 丢弃 raw response。
5. candidate 写入没有 candidateKey 幂等。
6. promptVersion 变化但 batchKey 不变。
7. Date/Number/Text 默认进入 active Entity。
8. partial_success 被当作 success 进入后续流程。
9. 融合直接覆盖实体，没有 fusionBatch。
10. 派生状态不能按 derivationBatch 删除或重建。
11. suspect 数据进入默认主图。
12. 无 evidence 的 fact 进入 active canonical fact。

## 12. 最小可用开发切片

第一轮不要一次做完整大系统，先做能止血且支撑后续的最小切片：

1. P1 step run 元数据。
2. P2 extraction batch + attempt + 断点续跑。
3. P3 candidate ledger。
4. P4 EntityDescription + CanonicalFact，停止 definition 生成状态。
5. P7 前端显示阶段状态和失败 batch 续跑。

第二轮：

1. P5 fusion batch。
2. P6 state/evolution derivation。
3. P7 质量审查视图和投影重建。

## 13. 关键自动化验收与发布阻断条件

每一步的设计已经在文档阶段完成独立审查。实现阶段不再设置沉重的逐步人工审查流程，避免拖慢开发。

实现阶段只保留三类轻量但硬性的验证：

```text
自动化测试
数据不变量检查
必要的小样本抽查
```

每个阶段完成后，只要关键验收通过即可进入下一阶段；不再要求“实现者自测 -> 独立代码审查 -> 数据审查 -> 回滚演练 -> 人工抽查 -> 才能继续”的重流程。

### 13.1 通用自动化不变量

所有阶段都必须满足：

1. 重跑必须生成新 stepRun，不覆盖旧 stepRun。
2. active artifact 同一 scope/type 只能有一个。
3. 失败 stepRun 必须记录 error_code、retryable、failed_phase。
4. 同一输入重复执行不得产生重复 active 数据。
5. suspect/rejected/superseded 不得进入默认主图。
6. 所有 active fact/state/evolution/projection 都能回溯到 batch、candidate、evidence 和 stepRun。

### 13.2 P1 自动化验收

必须测试：

```text
创建任务 -> stepRun/artifact 正常落库
任务中途异常 -> stepRun failed 且错误可见
running 超 lease -> 可回收
重跑某一步 -> 新 stepRun，新 artifact，不覆盖旧历史
active artifact 切换 -> 事务一致
```

发布阻断：

```text
running step 无法回收
active artifact 出现多个版本
stepRun success 但 output artifact 缺失
```

### 13.3 P2-P3 自动化验收

必须测试：

```text
10 个 batch，第 3 个网络失败 -> 只重跑第 3 个
success batch 重复点击继续 -> 不再次请求 LLM
parse_failed -> raw_response 保存，可用 raw_response_reparse 新 attempt 重解析
candidate 写入异常 -> batch 不得 success
lease 过期后的迟到结果 -> 不得写 active candidates
同一 candidateIdentityKey -> 最多一条 active candidate
```

发布阻断：

```text
success batch 重复请求 LLM
parse_failed raw_response 丢失
candidate active 重复
迟到 attempt 写入 active candidates
partial_success 被当作 success 进入 Canonical Build
```

### 13.4 P4 自动化验收

必须测试：

```text
entity.definition -> EntityDescription
entity.definition 不生成 EntityState
Date/Number/Text/FieldName -> value 或 predicate，不进入 active Entity
object_mention 是 Date/Number/Money/FieldName -> object_kind=value
subject/object 无 evidence 定位 -> suspect/rejected
相同 fact_key -> 合并 evidence
条件/时间/否定不同 -> 不合并
```

发布阻断：

```text
stateFromDescriptionCount > 0
dateAsEntityCount > 0 且不是 suspect/rejected
numberAsEntityCount > 0 且不是 suspect/rejected
fieldNameAsEntityCount > 0 且不是 suspect/rejected
active CanonicalFact 无 evidence
```

### 13.5 P5 自动化验收

必须测试：

```text
同名同类型但缺共同证据 -> candidate，不 confirmed
同名同类型且上下文不冲突、有 alias/identity/evidence 支撑 -> confirmed
跨类型相似名称 -> candidate/suspect
撤销 fusionBatch -> 回滚 graph_fusion_applied_ops
撤销融合 -> 标记受影响 Derivation/Projection/SearchIndex 过期
```

发布阻断：

```text
仅靠同名同类型自动 confirmed
撤销 fusionBatch 后下游投影仍 active
confirmed 融合缺 reason/confidence/evidence
```

### 13.6 P6 自动化验收

必须测试：

```text
只有“清华大学是一所高等院校” -> 不生成状态
仅有时间 -> 不生成状态
description_fact / identity_fact -> 不触发状态
有 phase/version/role_context/transition anchor + 非描述事实 + evidence -> 可生成状态
inferred EVOLVES_TO 默认关闭
仅时间排序 -> 不生成 evolution，只进入 evolution_candidate
启用 inferred 且有版本/阶段顺序证据 -> inferred evolution
撤销 derivationBatch -> 状态、演化边、缓存、索引同步作废
```

发布阻断：

```text
stateFromDescriptionCount > 0
仅有时间生成 EntityState
EntityDescription 进入 EntityState lineage
inferred EVOLVES_TO 默认开启
active EVOLVES_TO 引用 superseded state
```

### 13.7 P7 自动化验收

必须测试：

```text
默认主图只展示 confirmed active relation_fact
EntityState / EntityDescription / EVOLVES_TO 不进入默认主图
suspect/rejected/superseded 不进入默认主图
删除 projectionBatch -> 可重建投影
从指定步骤重跑 -> 有影响预览、二次确认、运行锁、回滚入口
```

发布阻断：

```text
suspect/rejected 进入默认主图
EntityState 进入默认主图
Neo4j 投影无法从账本重建
重跑覆盖旧 projection 历史
```

### 13.8 最小发布门禁

第一轮最小可用切片发布前，只需要通过以下硬门禁：

```text
stateFromDescriptionCount = 0
success batch 不重复请求 LLM
parse_failed raw_response 不丢
candidate active 不重复
active fact 必须有 evidence
suspect/rejected 不进默认主图
Neo4j 投影可重建
重跑不覆盖历史
```

这些门禁不通过，不允许接入正式数据。