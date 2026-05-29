# 知识图谱底座与可回溯流水线升级方案

配套开发拆解见 `docs/knowledge-graph-evolution-implementation-tasks.md`。

本文档重新定义知识图谱底座、分步流水线、断点续跑、可回滚和质量门禁。它替代早期“从 `entity.definition` 生成 `EntityState`”的设计表达。

## 1. 当前数据暴露的问题

当前 Neo4j 样本显示，系统已经具备 `Entity / Fact / Evidence / Neo4j` 的基础能力，但存在会在大规模数据下放大的风险：

1. `EntityState` 来源错误：当前实现把 LLM 输出的 `entity.definition` 写成 `EntityState.definition`，导致“一个具体的项目名称”“一所高等院校”等描述被当成状态。
2. 实体缺少稳定描述槽位：`清华大学` 这类实体的“高等院校”“中国顶尖大学之一”应该是实体描述或描述事实，不应该是状态。
3. 属性值实体化：`2018-12`、`万元`、`联系电话`、`项目名称` 等 Date/Number/Text 值被建成实体节点。
4. 短语误抽实体：如“致清华大学”“刘延东副总理在国务院学位委员会”进入主图，说明候选清洗不足。
5. 事实归属存在噪声：部分 subject/object 错挂，说明 LLM 输出不能直接成为全局定论。
6. 重复关系较多：同一关系在多个状态或多个批次中重复写入，需要事实级去重和证据聚合。

因此新设计的核心不是“让 LLM 更聪明地输出状态”，而是：

```text
LLM 只输出候选；
系统持久化候选账本；
规范实体和规范事实独立构建；
状态和演化只作为可删除重建的派生层；
Neo4j 主图只是当前投影，不是唯一真相。
```

## 2. 设计原则

### 2.1 低风险步骤合并，高风险步骤拆开

可以合并的步骤：

1. 文档解析、chunk 生成、证据 span 准备。
2. 候选格式校验、明显噪声标记、候选账本入库。
3. 图谱投影、查询索引刷新。

必须拆开的步骤：

1. LLM 候选抽取。
2. 规范实体和规范事实构建。
3. 实体融合和事实融合。
4. 状态派生。
5. 演化链派生。

拆分标准：

```text
确定性步骤可以合并；
判断性步骤必须拆开；
LLM 步骤必须可断点续跑；
融合步骤必须可撤销；
派生步骤必须可删除重建。
```

### 2.2 原始与候选不可变，规范与派生可版本化

数据分三层：

1. 证据账本层：原始文档、chunks、LLM 原始响应、候选实体、候选事实。默认不可变，只能新增新版本或标记 superseded。
2. 规范知识层：CanonicalEntity、CanonicalFact、EntityDescription、Alias、EvidenceLink。可版本化，可按批次作废和重建。
3. 派生投影层：EntityState、EVOLVES_TO、前端图谱、查询索引。必须可以按 derivationBatch 删除重建。

### 2.3 不把候选当定论

LLM 输出的数据必须先进入候选账本：

```text
EntityMentionCandidate
FactCandidate
DescriptionCandidate
IdentityCandidate
TransitionCandidate
Evidence
```

候选可以被验证、拒绝、作废、复用，但不能直接污染全局主图。

### 2.4 EntityState 是派生结果

`EntityState` 不是 LLM 基础输出，也不是实体描述。

它只能由一组已通过规范化和融合的 facts 派生：

```text
同一 CanonicalEntity
+ 同一时间/阶段/版本/上下文
+ 一组 attribute_fact / relation_fact / transition_fact
= EntityState
```

以下内容不生成状态：

1. “清华大学是一所高等院校。”
2. “项目名称之一。”
3. “一个具体的系统名称。”
4. 没有时间、阶段、版本、角色变化或上下文变化的普通定义。

这些内容应进入：

```text
EntityDescription
description_fact
attribute_fact
```

## 3. 总体流水线

正式流水线分 7 个阶段：

| 阶段 | 名称 | 风险 | 是否可合并 | 是否支持断点续跑 | 输出 |
| --- | --- | --- | --- | --- | --- |
| 1 | Evidence Build | 低 | 是 | 文件级 | chunks、evidence spans、inputHash |
| 2 | Candidate Extract | 高 | 否 | chunk batch 级 | raw LLM response、candidate records |
| 3 | Candidate Normalize | 中 | 可与入账合并 | batch 级 | validated / rejected candidates |
| 4 | Canonical Build | 高 | 否 | batch 或 graphBatch 级 | canonical entities、facts、descriptions |
| 5 | Fusion | 高 | 否 | fusionBatch 级 | entity clusters、fact clusters、alias links |
| 6 | Derivation | 高 | 否 | derivationBatch 级 | entity states、evolution edges |
| 7 | Projection | 低 | 是 | projectionBatch 级 | Neo4j business graph、query indexes |

### 3.1 阶段状态机

每个阶段记录 `GraphPipelineStepRun`：

```text
stepRunId
pipelineRunId
graphBatchId
stepName
status: pending | running | success | partial_success | failed | cancelled | superseded
inputArtifactVersion
outputArtifactVersion
codeVersion
configVersion
promptVersion
model
startedAt
finishedAt
inputCount
outputCount
rejectedCount
errorCount
lastError
retryOfStepRunId
```

重新运行时不覆盖旧 step run，而是创建新的 step run，并把旧结果标记为 `superseded` 或保持为历史版本。

### 3.2 Artifact 版本

每一步输出都是 artifact：

```text
raw_chunks_v1
llm_candidates_v1
validated_candidates_v1
canonical_graph_v1
fusion_result_v1
derived_states_v1
graph_projection_v1
```

当系统逻辑修改后，可以判断受影响范围：

```text
promptVersion 变化 -> 从 Candidate Extract 重新跑
normalizeRuleVersion 变化 -> 从 Candidate Normalize 重新跑
canonicalRuleVersion 变化 -> 从 Canonical Build 重新跑
fusionRuleVersion 变化 -> 从 Fusion 重新跑
stateRuleVersion 变化 -> 从 Derivation 重新跑
projectionVersion 变化 -> 只重建 Projection
```

## 4. 断点续跑设计

### 4.1 Candidate Extract 按 chunk batch 续跑

LLM 抽取必须按 chunk batch 记录：

```text
GraphExtractionBatch
- batchId
- docId
- graphBatchId
- batchNo
- chunkIds
- inputHash
- promptVersion
- model
- provider
- status: pending | running | success | failed | partial_success | superseded
- attemptCount
- lastAttemptId
- lastError
- startedAt
- finishedAt
- rawResponseArtifactId
- parsedCandidateCount
```

`inputHash` 由以下内容计算：

```text
docId
chunkIds
chunk text hash
knowledgeUnitIds
promptVersion
profile
model
configVersion
```

续跑规则：

1. `success` 且 inputHash 未变：跳过。
2. `failed`：重试该 batch。
3. `running` 超过租约时间：标记为 failed，再重试。
4. `partial_success`：默认不进入后续 Canonical Build；整批重试成功后旧 partial candidates 全部 superseded，新 attempt candidates 成为 active。
5. prompt/model/config 变化：旧 batch 不覆盖，新建版本重新跑。

### 4.2 幂等 key

候选记录必须有稳定 key：

```text
candidateKey =
  batchKey + candidateType + stableLocalId + payloadHash
```

`stableLocalId` 优先使用同一 attempt 内唯一的 LLM `mentionId/factId`，否则使用 localIndex。候选历史版本另设：

```text
candidateVersionId = candidateKey + attemptId
```

重试同一 batch 时：

1. 同 attempt 内不得重复插入。
2. 新 attempt 成功后，旧 failed attempt 保留历史。
3. 新版本成功后，旧 success 候选标记 `superseded`，不物理删除。
4. 同一 candidateKey 最多只能有一条 active 记录。

### 4.3 网络错误恢复

网络错误、模型超时、HTTP 5xx：

```text
batch.status = failed
attempt.status = failed
rawResponse = empty 或 partial
lastError = 错误摘要
```

下次继续时只运行：

```text
failed + pending + expired running
```

已经成功的 batch 不重新请求 LLM。

## 5. 数据模型边界

### 5.1 Entity

`CanonicalEntity` 表示稳定业务对象：

```text
entityId
canonicalKey
canonicalName
entityType
representativeDescriptionId
fusionClusterId
status: active | suspect | hidden | superseded
createdByStepRunId
updatedByStepRunId
```

实体节点可以有一个代表描述指针，但真实描述文本全部来自 `EntityDescription` 聚合，不直接写回实体主字段，也不来自 `EntityState`。

### 5.2 EntityDescription

实体描述单独存储：

```text
descriptionId
entityId
descriptionText
descriptionKind: definition | role_description | source_summary | llm_summary
docId
chunkId
sourceSpan
candidateId
confidence
status: active | rejected | superseded
```

例如：

```text
清华大学 -> EntityDescription: 一所高等院校
清华大学 -> EntityDescription: 中国顶尖大学之一
```

这些可用于详情页，不形成状态。

### 5.3 Fact

`CanonicalFact` 是规范事实：

```text
factId
subjectEntityId
predicate
objectEntityId
value
objectKind: entity | value
objectType
factKind
statement
validFrom
validTo
confidence
status: active | suspect | rejected | superseded
evidenceIds
sourceCandidateIds
createdByStepRunId
```

规则：

1. Date/Number/Text 默认作为 `value`，不建实体。
2. 只有可独立管理、跨文档复现、能继续发生关系的对象才建实体。
3. `description_fact` 或定义类 `attribute_fact` 可生成 EntityDescription。
4. 空 statement、无证据、subject/object 不可解析的 fact 进入 rejected 或 suspect。
5. Date/Number/Money/Phone/Email/Code/FieldName 作为 object_mention 时，转换为 `value/valueType`，不解析为 object entity。
6. subject/object 必须能在 evidence span 中定位，predicate 必须被 evidence 支撑；跨句 fact 默认 suspect，除非有指代链证据。

事实去重使用：

```text
fact_key =
subject_entity_id
+ normalized_predicate
+ object_entity_id/value_normalized
+ fact_kind
+ valid_from/valid_to
+ condition/context_hash
```

完全相同 `fact_key` 合并 evidence；时间、条件、否定、范围不同的事实不得合并，只能标记为 similar/conflict。

### 5.4 EntityState

`EntityState` 是派生层：

```text
stateId
entityId
stateLabel
stateKind: phase | version | role_context | temporal_snapshot
validFrom
validTo
stateSignature
sourceFactIds
evidenceIds
derivationBatchId
confidence
status: active | suspect | superseded
```

状态派生不是简单 OR。必须同时满足：

1. 来源 factKind 不得是 `description_fact` 或 `identity_fact`。
2. 至少有一个 state anchor：`phase`、`version`、`role_context` 或显式 `transition_fact`。
3. 至少有一条非描述事实作为属性或关系支撑。
4. 必须有 evidence。

仅有时间不能生成 EntityState。

禁止：

```text
EntityMention.definition -> EntityState.definition
EntityDescription -> EntityState
description_fact -> EntityState
```

`EntityDescription` 不得作为 EntityState 的 sourceFact；描述只能在详情页展示或作为人工审查背景。`stateFromDescriptionCount` 必须检查直接来源和递归 lineage。

### 5.5 Neo4j 投影

Neo4j 存当前图谱投影：

```text
(:Entity)
(:Fact)
(:EntityDescription)
(:EntityState)
(:EvidenceRef)
```

但 PostgreSQL 中的候选账本、step run、artifact 版本才是可回溯真相。Neo4j 数据可按 projectionBatch 删除重建。

## 6. 每阶段详细设计

### 6.1 Evidence Build

输入：source file、document、chunks、knowledge_units。

输出：

```text
EvidenceSpan
chunkId
contentHash
pageNo
startOffset / endOffset
```

质量门禁：

1. chunk 顺序稳定。
2. chunk hash 可复算。
3. 空 chunk 不进入抽取。
4. document title、source filename 标记为 metadata，不默认进入业务实体候选。

恢复策略：

```text
文件未变且 chunk hash 未变 -> 复用
解析逻辑变更 -> 重建 Evidence Build，后续步骤按 inputHash 判定是否需要重跑
```

### 6.2 Candidate Extract

输入：Evidence batch。

输出：

```text
rawResponse
EntityMentionCandidate
FactCandidate
DescriptionCandidate
```

LLM 不负责最终状态，不直接输出全局实体，不直接合并跨文档实体。

抽取提示词应要求：

1. 抽取忠于原文的候选。
2. 区分实体、属性值、描述、关系事实。
3. 时间、数字、编号、金额、普通文本默认作为 value。
4. 只在正文明确为业务对象时才抽成 entity。
5. 任何候选必须带 chunkId/sourceSpan。

恢复策略：按 4.1 的 batch 状态续跑。

### 6.3 Candidate Normalize

输入：raw candidates。

输出：

```text
validated candidates
rejected candidates
suspect candidates
```

低风险校验可以合并到候选入账：

1. JSON schema 校验。
2. 必填字段校验。
3. entity name 规范化。
4. 文件名、标题、导入编号、纯单位、纯数字值过滤。
5. Date/Number/Text 实体候选转成 value candidate。
6. 长短语实体候选降级为 suspect。

这一步不能做跨文档融合。

### 6.4 Canonical Build

输入：validated candidates。

输出：

```text
CanonicalEntity
CanonicalFact
EntityDescription
EvidenceLink
```

主要职责：

1. 把实体 mention 归入文档内 canonical entity。
2. 把描述候选写入 EntityDescription。
3. 把事实候选规范成 attribute/relation/identity/transition/description facts。
4. 把属性值保留为 value，而不是建 Date/Number/Text 实体。
5. 对 subject/object 无法确定的 fact 标记 suspect，不进入主图。

恢复策略：

```text
canonicalRuleVersion 变化 -> 作废当前 graphBatch 的 Canonical Build 输出，复用候选账本重跑
```

### 6.5 Fusion

输入：CanonicalEntity、CanonicalFact。

输出：

```text
EntityFusionCluster
FactFusionCluster
AliasLink
ConflictRecord
```

融合原则：

1. 同名同类型不能单独作为自动 confirmed 条件。必须同时满足 normalized name 完全一致、entity_type 一致、scope/context 不冲突、至少有共同 alias/identity_fact/来源证据、没有时间/组织/项目归属冲突。
2. 缩写、别名、项目简称、文件名派生名称只能形成 candidate cluster。
3. 跨类型融合默认 suspect。
4. 同名但不同上下文的实体不得直接合并。
5. 宁可少合并，不可错合并。

每次融合写 `fusionBatchId`，融合关系可撤销：

```text
active -> superseded
rollback graph_fusion_applied_ops
mark affected Derivation/Projection/SearchIndex expired
```

不物理删除候选和证据。

### 6.6 Derivation

输入：active CanonicalFacts + Fusion result。

输出：

```text
EntityState
EVOLVES_TO
StateConflict
```

子步骤：

1. 状态派生：只从 active `attribute_fact/relation_fact/transition_fact` 中，按阶段、版本、角色上下文聚合 facts。
2. 状态去重：同一实体同一状态签名聚合多来源 facts。
3. 演化构建：显式 transition_fact 优先；推断演化默认关闭，启用时必须带 inferred=true、规则原因和置信度。

禁止从 description 派生状态。`EntityDescription` 和 `description_fact` 只能用于详情展示或人工审查背景。

推断演化只有同时满足以下条件才允许生成：

1. 同一 canonical entity。
2. 两个 state 都是 confirmed active。
3. state_kind 是 phase 或 version。
4. 时间区间不冲突。
5. 有明确升级、修订、替代、二期、版本顺序证据。
6. confidence 达阈值。

仅按时间排序不能生成演化边，只能进入 `evolution_candidate`。

恢复策略：

```text
stateRuleVersion 变化 -> 删除该 derivationBatch 生成的 EntityState，重跑状态派生
evolutionRuleVersion 变化 -> 删除该 derivationBatch 生成的 EVOLVES_TO，重跑演化链
```

删除或重跑 derivationBatch 时必须同步作废 Neo4j 状态节点、EVOLVES_TO 边、状态时间线缓存、演化子图缓存和查询索引中的状态/演化条目。

### 6.7 Projection

输入：Canonical graph + Derived graph。

输出：

```text
Neo4j business graph
frontend graph view
query index
```

投影规则：

1. 默认主图只显示 confirmed active `Entity --relation_fact--> Entity`。
2. EntityDescription 在右侧详情展示。
3. confirmed active EntityState 在实体详情的状态/时间线区域展示；suspect state 只在质量审查视图展示。
4. EVOLVES_TO 在演化子图展示，不混入默认关系图。
5. rejected/suspect 默认不展示，只在调试/审核视图展示。

默认主图必须满足：

```text
CanonicalFact.status = active
factKind = relation_fact
fusionDecision = confirmed 或无需融合确认
subject/object 都是 active CanonicalEntity
subject/object 不是 Date/Number/Text/FieldName/Filename
source candidates 不含 rejected/suspect
evidence 存在
```

`EntityState`、`EntityDescription`、`EVOLVES_TO` 绝不进入默认主图。

恢复策略：

```text
projectionVersion 变化 -> 删除 projectionBatch 投影，按规范图重建
```

## 7. 质量门禁

每个 graphBatch 必须输出质量指标：

```text
chunkCount
extractedChunkCount
batchCount
failedBatchCount
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
```

必须提供抽样审查入口：

1. Top N 高度数实体。
2. Top N 状态数异常实体。
3. Top N 重复关系。
4. Date/Number/Text 实体候选拒绝列表。
5. 文件名/标题污染候选。
6. subject/object 解析失败 facts。
7. 每类 rejected 抽样。
8. 每类 suspect 抽样。
9. 每个融合批次抽样实体簇和事实簇。
10. 每个派生批次抽样 EntityState 和 EVOLVES_TO。

抽样界面必须展示：

```text
原文证据
LLM 原始输出
规范化结果
拒绝原因
置信度
所属 batch / stepRun
是否进入主图
```

进入主图前的硬门禁：

1. 无 evidence 的 fact 不进入 active。
2. Date/Number/Text 默认不成为 Entity。
3. 文件名/标题候选默认 rejected 或 source_metadata。
4. `definition` 不得生成 EntityState。
5. 状态数异常增长时，Derivation 阶段进入 suspect，需要人工确认或调低置信度。

必须长期监控污染指标：

```text
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

其中：

```text
stateFromDescriptionCount = 0
```

是硬约束。如果该指标大于 0，说明系统又回到了错误设计。

质量策略：

```text
宁可让数据停在 suspect，也不能让错误进入 confirmed 主图。
```

## 8. 错误恢复场景

### 8.1 网络错误

只重试失败的 extraction batch，不重跑已成功 batch。

### 8.2 Prompt 错误

保留旧 raw response 和候选，提升 promptVersion 后从 Candidate Extract 重跑受影响 batch。

### 8.3 清洗规则错误

不重新调用 LLM，从 Candidate Normalize 重跑。

### 8.4 EntityState 规则错误

不重新抽取、不重新融合，删除旧 derivationBatch 的状态和演化边，从 Derivation 重跑。

### 8.5 融合错误

撤销 fusionBatch，规范实体和事实仍保留，重新执行 Fusion 和后续 Derivation/Projection。

### 8.6 投影错误

删除 projectionBatch，按当前规范图和派生图重建 Neo4j 投影。

## 9. 当前数据迁移策略

针对已经写入 Neo4j 的旧数据：

1. 不直接删除旧图。
2. 标记旧 `EntityState` 来源为 `legacy_definition_state`。
3. 把旧 state.definition 迁移为 `EntityDescription` 候选。
4. 将 Date/Number/Text 实体作为 suspect，等待 Canonical Build 重算。
5. 保留旧 Fact/Evidence，用于迁移验证。
6. 新流水线生成 `projection_v2` 后，前端默认读取新投影。

## 10. 验收标准

1. 同一个文档 LLM 抽取中断后，可以从失败 chunk batch 续跑。
2. 修复 Candidate Normalize 规则后，可以不重新调用 LLM 直接重跑后续步骤。
3. 修复 EntityState 规则后，可以只删除派生状态和演化边重建。
4. `清华大学` 的“一所高等院校”类内容进入 EntityDescription，不再进入 EntityState。
5. Date/Number/Text 默认不出现在主图实体列表。
6. 主图只展示业务实体关系；状态和演化在实体详情子视图展示。
7. 每个 active fact、state、evolution edge 都能回溯到 candidate、chunk 和 stepRun。
8. 每个 fusionBatch、derivationBatch、projectionBatch 都能撤销或重建。
