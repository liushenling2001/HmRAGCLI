# 领域知识编译系统 Java DTO、Controller 与 Service 草案

## 1. 文档目标

本文档用于把领域知识编译系统进一步细化到 Java 后端实现层，重点给出：

1. DTO 草案
2. Controller 路由草案
3. Service 职责划分
4. 编译任务状态机建议

目标是让后续 Java 实现可以直接按对象和职责落地。

---

## 2. 包结构建议

建议在 `java-backend` 中新增以下模块：

1. `com.hmrag.backend.domainknowledge.domain`
2. `com.hmrag.backend.domainknowledge.repository`
3. `com.hmrag.backend.domainknowledge.service`
4. `com.hmrag.backend.domainknowledge.web`
5. `com.hmrag.backend.domainknowledge.web.dto`

如果不想新增顶层模块，也可按当前项目风格放入：

1. `domain`
2. `repository`
3. `service`
4. `web`
5. `web.dto`

但建议通过子包显式隔离“领域知识编译系统”。

---

## 3. DTO 设计

## 3.1 请求 DTO

### 3.1.1 CreateDomainDefinitionRequest

建议字段：

```java
public record CreateDomainDefinitionRequest(
        String name,
        String description,
        String goal,
        Map<String, Object> scopeRules,
        List<String> seedQueries,
        List<UUID> includeDataSourceIds,
        List<UUID> excludeDataSourceIds,
        Integer priority,
        Boolean autoRefreshEnabled,
        String autoRefreshCron,
        String activeModelProfile,
        Map<String, Object> metadata
) {}
```

### 3.1.2 UpdateDomainDefinitionRequest

建议字段与 `CreateDomainDefinitionRequest` 类似，但全部可选。

### 3.1.3 CreateTopicDefinitionRequest

```java
public record CreateTopicDefinitionRequest(
        UUID parentTopicId,
        String name,
        String description,
        Map<String, Object> scopeRules,
        List<String> seedQueries,
        Integer priority,
        Map<String, Object> metadata
) {}
```

### 3.1.4 UpdateTopicDefinitionRequest

与创建类似，但字段都可选。

### 3.1.5 StartDomainRefineRequest

```java
public record StartDomainRefineRequest(
        String jobType,
        String modelProfile,
        String rebuildMode,
        Boolean forceRebuild,
        Map<String, Object> runtimeOptions
) {}
```

### 3.1.6 StartTopicRefineRequest

与领域编译类似，但目标对象是专题。

### 3.1.7 ExpandEvidenceRequest

```java
public record ExpandEvidenceRequest(
        List<EvidenceRefDto> refs,
        String mode,
        Integer windowSize
) {}
```

---

## 3.2 响应 DTO

### 3.2.1 DomainDefinitionDto

```java
public record DomainDefinitionDto(
        UUID id,
        String name,
        String description,
        String goal,
        Map<String, Object> scopeRules,
        List<String> seedQueries,
        List<UUID> includeDataSourceIds,
        List<UUID> excludeDataSourceIds,
        int priority,
        boolean autoRefreshEnabled,
        String autoRefreshCron,
        String activeModelProfile,
        String status,
        String createdBy,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.2 TopicDefinitionDto

```java
public record TopicDefinitionDto(
        UUID id,
        UUID domainId,
        UUID parentTopicId,
        String name,
        String description,
        Map<String, Object> scopeRules,
        List<String> seedQueries,
        int priority,
        String status,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.3 DomainRefineJobDto

```java
public record DomainRefineJobDto(
        UUID id,
        String jobType,
        UUID domainId,
        UUID topicId,
        String status,
        String triggerSource,
        String modelProfile,
        Map<String, Object> scopeSnapshot,
        Map<String, Object> inputSummary,
        Map<String, Object> outputSummary,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.4 DomainBriefDto

```java
public record DomainBriefDto(
        UUID id,
        UUID domainId,
        UUID refineJobId,
        int versionNo,
        String status,
        String summary,
        String domainBoundary,
        List<Map<String, Object>> coreConcepts,
        List<Map<String, Object>> coreTopics,
        List<Map<String, Object>> keyClaims,
        List<Map<String, Object>> keyMetrics,
        List<Map<String, Object>> conflicts,
        List<Map<String, Object>> timelineSummary,
        UUID evidencePackId,
        String llmContextSummary,
        Map<String, Object> sourceCoverage,
        OffsetDateTime compiledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.5 TopicDossierDto

```java
public record TopicDossierDto(
        UUID id,
        UUID domainId,
        UUID topicId,
        UUID refineJobId,
        int versionNo,
        String status,
        String title,
        String summary,
        String scopeText,
        List<Map<String, Object>> keyPoints,
        List<Map<String, Object>> exceptions,
        List<Map<String, Object>> conflicts,
        List<Map<String, Object>> timeline,
        List<String> keywords,
        UUID claimGroupId,
        UUID evidencePackId,
        String llmContextSummary,
        Map<String, Object> sourceCoverage,
        OffsetDateTime compiledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.6 ClaimSetDto

```java
public record ClaimSetDto(
        UUID id,
        String ownerType,
        UUID ownerId,
        String claimType,
        String claimText,
        Double confidence,
        String validScope,
        String effectiveTimeRange,
        int priority,
        UUID conflictSetId,
        UUID evidencePackId,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.7 ConflictSetDto

```java
public record ConflictSetDto(
        UUID id,
        String ownerType,
        UUID ownerId,
        String conflictType,
        String summary,
        Map<String, Object> sideA,
        Map<String, Object> sideB,
        String possibleReason,
        String resolutionHint,
        UUID evidencePackId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.8 EvidenceRefDto

```java
public record EvidenceRefDto(
        UUID docId,
        UUID chunkId,
        UUID knowledgeUnitId,
        String sourceFile,
        String sourceFilename,
        Integer pageNo,
        String sourceSpan,
        String quoteText,
        String contextMode
) {}
```

### 3.2.9 EvidencePackDto

```java
public record EvidencePackDto(
        UUID id,
        String ownerType,
        UUID ownerId,
        List<EvidenceRefDto> primaryRefs,
        List<EvidenceRefDto> supportRefs,
        List<Map<String, Object>> quoteSpans,
        List<Map<String, Object>> contextWindowRefs,
        List<Map<String, Object>> sectionRefs,
        List<Map<String, Object>> citationReadyRefs,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

### 3.2.10 DomainMemoryPackDto

```java
public record DomainMemoryPackDto(
        DomainDefinitionDto domain,
        DomainBriefDto brief,
        List<TopicDossierDto> topics,
        List<ClaimSetDto> claims,
        List<ConflictSetDto> conflicts,
        Map<String, Object> retrievalHooks
) {}
```

---

## 4. Controller 设计

## 4.1 DomainDefinitionController

建议路由：

1. `POST /api/v1/domains`
2. `GET /api/v1/domains`
3. `GET /api/v1/domains/{id}`
4. `PATCH /api/v1/domains/{id}`

建议职责：

1. 领域定义对象的 CRUD
2. 领域列表查询
3. 领域配置状态切换

## 4.2 TopicDefinitionController

建议路由：

1. `POST /api/v1/domains/{id}/topics`
2. `GET /api/v1/domains/{id}/topics`
3. `PATCH /api/v1/topics/{id}`
4. `GET /api/v1/topics/{id}`

建议职责：

1. 专题树维护
2. 专题详情查询

## 4.3 DomainRefineController

建议路由：

1. `POST /api/v1/domains/{id}/refine`
2. `POST /api/v1/topics/{id}/refine`
3. `GET /api/v1/refine-jobs`
4. `GET /api/v1/refine-jobs/{id}`
5. `POST /api/v1/refine-jobs/{id}/cancel`

建议职责：

1. 人工发起领域编译任务
2. 查询任务状态
3. 取消尚未完成的编译任务

## 4.4 DomainKnowledgeController

建议路由：

1. `GET /api/v1/domains/{id}/brief`
2. `GET /api/v1/domains/{id}/memory-pack`
3. `GET /api/v1/topics/{id}/dossier`
4. `GET /api/v1/topics/{id}/claims`
5. `GET /api/v1/evidence-packs/{id}`
6. `POST /api/v1/evidence/expand`

建议职责：

1. 提供给前端和智能体消费的知识对象
2. 提供正文回查能力

---

## 5. Service 设计

## 5.1 DomainDefinitionService

职责：

1. 创建领域
2. 更新领域
3. 查询领域
4. 校验领域定义合法性

建议方法：

1. `createDomain(CreateDomainDefinitionRequest request)`
2. `updateDomain(UUID id, UpdateDomainDefinitionRequest request)`
3. `listDomains()`
4. `getDomain(UUID id)`

## 5.2 TopicDefinitionService

职责：

1. 创建专题
2. 更新专题
3. 构建专题树
4. 查询专题

建议方法：

1. `createTopic(UUID domainId, CreateTopicDefinitionRequest request)`
2. `updateTopic(UUID topicId, UpdateTopicDefinitionRequest request)`
3. `listTopicsByDomain(UUID domainId)`
4. `getTopic(UUID topicId)`

## 5.3 DomainRefineJobService

职责：

1. 创建编译任务
2. 查询编译任务
3. 更新任务状态
4. 取消任务

建议方法：

1. `startDomainRefine(UUID domainId, StartDomainRefineRequest request)`
2. `startTopicRefine(UUID topicId, StartTopicRefineRequest request)`
3. `listJobs(int page, int pageSize)`
4. `getJob(UUID jobId)`
5. `cancelJob(UUID jobId)`

## 5.4 DomainCompilationService

职责：

1. 执行一次领域编译
2. 执行一次专题编译
3. 组织编译阶段
4. 产出 `DomainBrief / TopicDossier / ClaimSet / ConflictSet / EvidencePack`

建议方法：

1. `compileDomain(UUID refineJobId)`
2. `compileTopic(UUID refineJobId)`
3. `collectEvidence(CompilationScope scope)`
4. `buildDomainBrief(...)`
5. `buildTopicDossier(...)`
6. `buildClaimSets(...)`
7. `buildConflictSets(...)`
8. `buildEvidencePack(...)`

## 5.5 DomainKnowledgeReadService

职责：

1. 查询领域总览
2. 查询专题知识页
3. 查询结论和冲突
4. 组装 `DomainMemoryPack`

建议方法：

1. `getDomainBrief(UUID domainId)`
2. `getTopicDossier(UUID topicId)`
3. `listClaimsByTopic(UUID topicId)`
4. `buildMemoryPack(UUID domainId)`

## 5.6 EvidenceExpansionService

职责：

1. 根据证据引用回查正文
2. 提供 `quote/context/section` 三种展开模式

建议方法：

1. `expandEvidence(ExpandEvidenceRequest request)`
2. `expandQuote(EvidenceRefDto ref)`
3. `expandContext(EvidenceRefDto ref, int windowSize)`
4. `expandSection(EvidenceRefDto ref)`

## 5.7 DomainAutoMaintenanceService

职责：

1. 扫描已定义领域的变化
2. 判断哪些领域和专题需要自动维护
3. 生成自动维护任务

建议方法：

1. `scheduleIncrementalJobs()`
2. `findAffectedDomains()`
3. `findAffectedTopics(UUID domainId)`
4. `enqueueMaintenanceJob(...)`

---

## 6. 编译任务状态机建议

编译任务建议状态：

1. `queued`
2. `running`
3. `needs_review`
4. `success`
5. `failed`
6. `cancelled`

### 6.1 阶段状态建议

可以额外为编译任务维护阶段信息：

1. `scope_resolve`
2. `evidence_collect`
3. `summary_compile`
4. `claims_compile`
5. `conflict_compile`
6. `evidence_bind`
7. `publish`

### 6.2 推荐规则

1. 人工高质量任务可以进入 `needs_review`
2. 自动维护任务默认不进入 `authoritative`
3. 自动维护产物建议作为 `maintained` 或 `draft`

---

## 7. 推荐与当前项目的集成方式

## 7.1 不动当前搜索主入口

现有：

1. `QueryService`
2. `DataSourceService`
3. `EmbeddingService`

都不需要立刻大改。

新系统优先作为独立模块接入。

## 7.2 先复用现有证据检索能力

领域编译任务前期可以先复用：

1. 当前全文检索
2. 当前向量检索
3. 当前 `documents / chunks / knowledge_units`

后续再逐步改成更专门的领域证据收集逻辑。

---

## 8. 推荐最小实现切入点

如果直接开始编码，建议先实现：

1. `DomainDefinitionController`
2. `TopicDefinitionController`
3. `DomainRefineController`
4. `DomainDefinitionService`
5. `TopicDefinitionService`
6. `DomainRefineJobService`

然后再实现：

1. `DomainKnowledgeController`
2. `DomainKnowledgeReadService`
3. `EvidenceExpansionService`

最后再实现：

1. `DomainCompilationService`
2. `DomainAutoMaintenanceService`

---

## 9. 最终结论

当前阶段最重要的是先把对象和职责稳定下来。

建议采用以下明确边界：

1. 定义层由 `DomainDefinitionService / TopicDefinitionService` 管理
2. 编译任务由 `DomainRefineJobService` 管理
3. 编译执行由 `DomainCompilationService` 完成
4. 查询和智能体消费由 `DomainKnowledgeReadService` 提供
5. 正文回查由 `EvidenceExpansionService` 提供

这样后续实现时，不会把领域定义、编译执行、查询消费和正文回查混在一个大服务里。

