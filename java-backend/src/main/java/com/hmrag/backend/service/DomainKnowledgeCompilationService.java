package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.DataSource;
import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.domain.DomainMemoryPack;
import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.domain.TopicDefinition;
import com.hmrag.backend.repository.DataSourceRepository;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.repository.DomainMemoryPackRepository;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import com.hmrag.backend.repository.TopicDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DomainKnowledgeCompilationService {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeCompilationService.class);

    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainDefinitionRepository domainDefinitionRepository;
    private final TopicDefinitionRepository topicDefinitionRepository;
    private final DomainMemoryPackRepository domainMemoryPackRepository;
    private final DataSourceRepository dataSourceRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final DomainKnowledgeRefinementService domainKnowledgeRefinementService;
    private final DomainRefineJobProgressService domainRefineJobProgressService;
    private final EmbeddingService embeddingService;
    private final KnowledgeGraphStoreClient knowledgeGraphStoreClient;

    public DomainKnowledgeCompilationService(
            DomainRefineJobRepository domainRefineJobRepository,
            DomainDefinitionRepository domainDefinitionRepository,
            TopicDefinitionRepository topicDefinitionRepository,
            DomainMemoryPackRepository domainMemoryPackRepository,
            DataSourceRepository dataSourceRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            AppProperties appProperties,
            DomainKnowledgeRefinementService domainKnowledgeRefinementService,
            DomainRefineJobProgressService domainRefineJobProgressService,
            EmbeddingService embeddingService,
            KnowledgeGraphStoreClient knowledgeGraphStoreClient
    ) {
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainDefinitionRepository = domainDefinitionRepository;
        this.topicDefinitionRepository = topicDefinitionRepository;
        this.domainMemoryPackRepository = domainMemoryPackRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.appProperties = appProperties;
        this.domainKnowledgeRefinementService = domainKnowledgeRefinementService;
        this.domainRefineJobProgressService = domainRefineJobProgressService;
        this.embeddingService = embeddingService;
        this.knowledgeGraphStoreClient = knowledgeGraphStoreClient;
    }

    @Transactional
    public void compileJob(UUID jobId) {
        DomainRefineJob job = requireJob(jobId);
        if ("cancelled".equalsIgnoreCase(job.getStatus())) {
            return;
        }
        DomainDefinition domain = requireDomain(job.getDomainId());
        TopicDefinition topic = job.getTopicId() == null ? null : requireTopic(job.getTopicId());
        Map<String, Object> collectingStart = new LinkedHashMap<>();
        collectingStart.put("domainName", domain.getName());
        if (topic != null) {
            collectingStart.put("topicName", topic.getName());
        }
        markJobProgress(job.getId(), "collecting_evidence", collectingStart);
        EvidenceBundle evidence = collectEvidence(job.getId(), domain, topic);
        markJobProgress(job.getId(), "drafting_pack", Map.of(
                "documentCount", evidence.documents().size(),
                "knowledgeUnitCount", evidence.knowledgeUnits().size(),
                "chunkCount", evidence.chunks().size(),
                "retrievalTerms", evidence.terms()
        ));
        String draftSummary = buildSummary(domain, topic, evidence);
        List<String> draftKeyPoints = buildKeyPoints(domain, topic, evidence);
        String draftMarkdown = buildMarkdown(domain, topic, evidence);
        markJobProgress(job.getId(), "llm_refining", Map.of(
                "draftSummary", draftSummary,
                "draftKeyPoints", draftKeyPoints
        ));
        RefinementOutcome refinementOutcome = refine(job, domain, topic, evidence, draftSummary, draftKeyPoints, draftMarkdown);
        markJobProgress(job.getId(), "saving_pack", Map.of(
                "refined", refinementOutcome.refined(),
                "packStatus", refinementOutcome.packStatus(),
                "validation", refinementOutcome.validation(),
                "refinementMetadata", refinementOutcome.metadata()
        ));

        DomainMemoryPack pack = new DomainMemoryPack();
        pack.setDomainId(domain.getId());
        pack.setTopicId(topic == null ? null : topic.getId());
        pack.setRefineJobId(job.getId());
        pack.setArtifactType(refinementOutcome.refined() ? "refined" : "draft");
        pack.setStatus(refinementOutcome.packStatus());
        pack.setTitle(buildTitle(domain, topic));
        pack.setSummary(refinementOutcome.summary());
        pack.setKeyPointsJson(refinementOutcome.keyPoints());
        pack.setEvidenceRefsJson(new ArrayList<>(evidence.evidenceRefs()));
        Map<String, Object> snapshot = buildSourceSnapshot(domain, topic, evidence);
        snapshot.put("refinement", refinementOutcome.metadata());
        pack.setSourceSnapshotJson(snapshot);
        pack.setStructuredContentJson(refinementOutcome.structuredContent());
        pack.setContentMarkdown(refinementOutcome.markdown());
        pack.setModelProfile(job.getModelProfile());
        DomainMemoryPack savedPack = domainMemoryPackRepository.saveAndFlush(pack);
        if (savedPack.getId() == null || !domainMemoryPackRepository.existsById(savedPack.getId())) {
            throw new IllegalStateException("DOMAIN_MEMORY_PACK_NOT_CREATED");
        }

        job.setHeartbeatAt(OffsetDateTime.now());
        job.setStatus("completed");
        job.setFinishedAt(OffsetDateTime.now());
        job.setErrorMessage(null);
        job.setOutputSummaryJson(Map.of(
                "memoryPackId", savedPack.getId().toString(),
                "artifactType", savedPack.getArtifactType(),
                "status", savedPack.getStatus(),
                "validation", refinementOutcome.validation(),
                "documentCount", evidence.documents().size(),
                "knowledgeUnitCount", evidence.knowledgeUnits().size(),
                "chunkCount", evidence.chunks().size(),
                "refined", refinementOutcome.refined(),
                "refinementMetadata", refinementOutcome.metadata()
        ));
        domainRefineJobRepository.save(job);
    }

    private void markJobProgress(UUID jobId, String phase, Map<String, Object> extras) {
        domainRefineJobProgressService.markProgress(jobId, phase, extras);
    }

    private DomainRefineJob requireJob(UUID id) {
        return domainRefineJobRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("精炼任务不存在: " + id));
    }

    private DomainDefinition requireDomain(UUID id) {
        return domainDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("领域不存在: " + id));
    }

    private TopicDefinition requireTopic(UUID id) {
        return topicDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("专题不存在: " + id));
    }

    private String buildTitle(DomainDefinition domain, TopicDefinition topic) {
        return topic == null
                ? domain.getName() + " 领域知识草稿"
                : domain.getName() + " / " + topic.getName() + " 专题知识草稿";
    }

    private String buildSummary(DomainDefinition domain, TopicDefinition topic, EvidenceBundle evidence) {
        String scope = topic == null ? "领域" : "专题";
        return "该草稿由系统根据" + scope + "定义自动生成，已纳入 "
                + evidence.documents().size() + " 篇文档、"
                + evidence.knowledgeUnits().size() + " 条知识单元和 "
                + evidence.chunks().size() + " 个正文片段作为首批证据。当前版本已固化范围、种子问题、数据源约束和主题结构，并提供基础正文回溯入口。";
    }

    private List<String> buildKeyPoints(DomainDefinition domain, TopicDefinition topic, EvidenceBundle evidence) {
        List<String> keyPoints = new ArrayList<>();
        keyPoints.add("目标: " + nullSafe(domain.getGoal(), "未定义"));
        keyPoints.add("领域状态: " + nullSafe(domain.getStatus(), "draft"));
        keyPoints.add("自动维护: " + (domain.isAutoRefreshEnabled() ? "开启" : "关闭"));
        if (topic != null) {
            keyPoints.add("专题状态: " + nullSafe(topic.getStatus(), "active"));
        }
        if (!domain.getSeedQueriesJson().isEmpty()) {
            keyPoints.add("领域种子问题数: " + domain.getSeedQueriesJson().size());
        }
        if (topic != null && !topic.getSeedQueriesJson().isEmpty()) {
            keyPoints.add("专题种子问题数: " + topic.getSeedQueriesJson().size());
        }
        if (!domain.getIncludeDataSourcesJson().isEmpty()) {
            keyPoints.add("纳入数据源数: " + domain.getIncludeDataSourcesJson().size());
        }
        keyPoints.add("证据文档数: " + evidence.documents().size());
        keyPoints.add("证据知识单元数: " + evidence.knowledgeUnits().size());
        keyPoints.add("正文片段数: " + evidence.chunks().size());
        return keyPoints;
    }

    private Map<String, Object> buildSourceSnapshot(DomainDefinition domain, TopicDefinition topic, EvidenceBundle evidence) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<String> excludedTerms = collectExcludedTerms(domain, topic);
        snapshot.put("domainId", domain.getId());
        snapshot.put("domainName", domain.getName());
        snapshot.put("domainScopeRules", new HashMap<>(domain.getScopeRulesJson()));
        snapshot.put("domainSeedQueries", new ArrayList<>(domain.getSeedQueriesJson()));
        snapshot.put("excludedTerms", excludedTerms);
        snapshot.put("domainBuildSpec", buildDomainBuildSpec(domain, topic, evidence, excludedTerms));
        snapshot.put("includeDataSources", resolveDataSources(domain.getIncludeDataSourcesJson()));
        snapshot.put("excludeDataSources", new ArrayList<>(domain.getExcludeDataSourcesJson()));
        snapshot.put("retrievalTerms", evidence.terms());
        snapshot.put("evidenceWarnings", evidence.warnings());
        snapshot.put("topicSubgraph", evidence.topicSubgraph());
        snapshot.put("evidencePack", evidence.evidencePack());
        snapshot.put("documents", evidence.documents());
        snapshot.put("knowledgeUnits", evidence.knowledgeUnits());
        snapshot.put("chunks", evidence.chunks());
        if (topic != null) {
            snapshot.put("topicId", topic.getId());
            snapshot.put("topicName", topic.getName());
            snapshot.put("topicScopeRules", new HashMap<>(topic.getScopeRulesJson()));
            snapshot.put("topicSeedQueries", new ArrayList<>(topic.getSeedQueriesJson()));
        }
        return snapshot;
    }

    private Map<String, Object> buildDomainBuildSpec(
            DomainDefinition domain,
            TopicDefinition topic,
            EvidenceBundle evidence,
            List<String> excludedTerms
    ) {
        Map<String, Object> scopeRules = domain.getScopeRulesJson() == null
                ? Map.of()
                : new LinkedHashMap<>(domain.getScopeRulesJson());
        Map<String, Object> metadata = domain.getMetadataJson() == null
                ? Map.of()
                : new LinkedHashMap<>(domain.getMetadataJson());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v1");
        spec.put("domainName", domain.getName());
        spec.put("topicName", topic == null ? null : topic.getName());
        spec.put("goal", nullSafe(domain.getGoal(), ""));
        spec.put("description", nullSafe(topic == null ? domain.getDescription() : topic.getDescription(), ""));
        spec.put("scope", Map.of(
                "includeTerms", toStringList(scopeRules.get("includeTerms")),
                "excludeTerms", excludedTerms == null ? List.of() : excludedTerms,
                "includeDataSources", new ArrayList<>(domain.getIncludeDataSourcesJson()),
                "excludeDataSources", new ArrayList<>(domain.getExcludeDataSourcesJson())
        ));
        spec.put("agentUseCases", firstNonEmptyStringList(
                toStringList(scopeRules.get("agentUseCases")),
                toStringList(metadata.get("agentUseCases"))
        ));
        spec.put("knowledgeDimensions", firstNonEmptyStringList(
                toStringList(scopeRules.get("knowledgeDimensions")),
                toStringList(scopeRules.get("dimensions")),
                toStringList(metadata.get("setupAssistantCoveredDimensions")),
                List.of("制度政策", "业务流程", "主体关系", "历史演进", "评价指标", "风险约束")
        ));
        spec.put("catalogRules", Map.of(
                "maxDepth", 3,
                "mustBindEvidence", true,
                "avoidFileTitleAsCatalog", true,
                "avoidMixedLevels", true
        ));
        spec.put("seedQueries", new ArrayList<>(domain.getSeedQueriesJson()));
        spec.put("retrievalTerms", evidence == null ? List.of() : evidence.terms());
        spec.put("evidenceWarnings", evidence == null ? List.of() : evidence.warnings());
        Map<String, Object> socraticSetup = new LinkedHashMap<>();
        socraticSetup.put("history", nonNull(metadata.get("setupHistory"), List.of()));
        socraticSetup.put("currentDimension", nonNull(metadata.get("setupAssistantCurrentDimension"), ""));
        socraticSetup.put("coveredDimensions", nonNull(metadata.get("setupAssistantCoveredDimensions"), List.of()));
        socraticSetup.put("nextDimension", nonNull(metadata.get("setupAssistantNextDimension"), ""));
        socraticSetup.put("reason", nonNull(metadata.get("setupAssistantReason"), ""));
        spec.put("socraticSetup", socraticSetup);
        return spec;
    }

    private Object nonNull(Object value, Object fallback) {
        return value == null ? fallback : value;
    }

    @SafeVarargs
    private final List<String> firstNonEmptyStringList(List<String>... candidates) {
        for (List<String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> resolveDataSources(List<String> ids) {
        List<UUID> uuids = new ArrayList<>();
        for (String id : ids) {
            try {
                uuids.add(UUID.fromString(id));
            } catch (Exception ignored) {
                // Keep invalid identifiers out of snapshot resolution.
            }
        }
        if (uuids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (DataSource dataSource : dataSourceRepository.findAllById(uuids)) {
            items.add(Map.of(
                    "id", dataSource.getId().toString(),
                    "sourceName", dataSource.getSourceName(),
                    "rootPath", dataSource.getRootPath()
            ));
        }
        return items;
    }

    private String buildMarkdown(DomainDefinition domain, TopicDefinition topic, EvidenceBundle evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(buildTitle(domain, topic)).append("\n\n");
        builder.append("## 目标\n");
        builder.append(nullSafe(domain.getGoal(), "未定义")).append("\n\n");
        builder.append("## 范围说明\n");
        builder.append(nullSafe(topic == null ? domain.getDescription() : topic.getDescription(), "未定义")).append("\n\n");
        builder.append("## 领域种子问题\n");
        appendList(builder, domain.getSeedQueriesJson());
        if (topic != null) {
            builder.append("\n## 专题种子问题\n");
            appendList(builder, topic.getSeedQueriesJson());
        }
        builder.append("\n## 检索词\n");
        appendList(builder, evidence.terms());
        builder.append("\n## 证据文档\n");
        appendDocumentSection(builder, evidence.documents());
        builder.append("\n## 关键知识单元\n");
        appendKnowledgeUnitSection(builder, evidence.knowledgeUnits());
        builder.append("\n## 正文片段\n");
        appendChunkSection(builder, evidence.chunks());
        builder.append("\n## 当前说明\n");
        builder.append("- 这是系统自动生成的知识草稿。\n");
        builder.append("- 当前已接入基础证据收集和正文回溯窗口。\n");
        builder.append("- 下一阶段将把这些证据送入大模型做专题熔炼和冲突归并。\n");
        return builder.toString();
    }

    private void appendList(StringBuilder builder, List<String> items) {
        if (items == null || items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        for (String item : items) {
            builder.append("- ").append(item).append("\n");
        }
    }

    private void appendDocumentSection(StringBuilder builder, List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        for (Map<String, Object> item : items) {
            builder.append("- [doc] ")
                    .append(item.get("title"))
                    .append(" | id=").append(item.get("docId"))
                    .append(" | source=").append(item.get("sourceFile"))
                    .append("\n");
        }
    }

    private void appendKnowledgeUnitSection(StringBuilder builder, List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        for (Map<String, Object> item : items) {
            builder.append("- [ku] ")
                    .append(nullSafe((String) item.get("title"), "未命名"))
                    .append(" | subject=").append(nullSafe((String) item.get("subject"), "-"))
                    .append(" | indicator=").append(nullSafe((String) item.get("indicator"), "-"))
                    .append(" | ref=").append(item.get("knowledgeUnitId"))
                    .append("\n");
        }
    }

    private void appendChunkSection(StringBuilder builder, List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        for (Map<String, Object> item : items) {
            builder.append("- [chunk] doc=").append(item.get("docId"))
                    .append(" | chunk=").append(item.get("chunkId"))
                    .append(" | page=").append(item.get("pageNo"))
                    .append(" | snippet=").append(item.get("snippet"))
                    .append("\n");
        }
    }

    private EvidenceBundle collectEvidence(UUID jobId, DomainDefinition domain, TopicDefinition topic) {
        List<String> excludedTerms = collectExcludedTerms(domain, topic);
        markJobProgress(jobId, "planning_retrieval_plan", Map.of(
                "domainName", domain.getName(),
                "topicName", topic == null ? "" : topic.getName(),
                "excludedTerms", excludedTerms
        ));
        DomainKnowledgeRefinementService.RetrievalPlanResult llmPlan = optimizeRetrievalPlan(domain, topic);
        List<String> terms = buildTerms(domain, topic, excludedTerms, llmPlan.terms());
        RetrievalPlan retrievalPlan = buildRetrievalPlan(domain, topic, terms, llmPlan);
        List<UUID> includeDataSourceIds = parseUuids(domain.getIncludeDataSourcesJson());
        List<UUID> excludeDataSourceIds = parseUuids(domain.getExcludeDataSourcesJson());

        ScoreAccumulator docs = new ScoreAccumulator();
        ScoreAccumulator knowledgeUnits = new ScoreAccumulator();
        ScoreAccumulator chunks = new ScoreAccumulator();
        Map<String, Integer> excludedStats = new HashMap<>();
        int processedTerms = 0;

        markJobProgress(jobId, "planning_retrieval", Map.of(
                "retrievalPlan", retrievalPlan.toProgressMap(),
                "excludedTerms", excludedTerms
        ));

        for (int passIndex = 0; passIndex < retrievalPlan.passes().size(); passIndex++) {
            RetrievalPass pass = retrievalPlan.passes().get(passIndex);
            int termIndex = 0;
            for (String term : pass.terms()) {
                if (domainRefineJobProgressService.isCancelled(jobId)) {
                    throw new TaskCancelledException("DOMAIN_REFINE_CANCELLED");
                }
                double termWeight = pass.weight() + (1.0 / (termIndex + 2.0));
                Map<String, Object> beforeQueryProgress = new LinkedHashMap<>();
                beforeQueryProgress.put("retrievalPass", pass.name());
                beforeQueryProgress.put("dimension", pass.dimensionName());
                beforeQueryProgress.put("retrievalPassIndex", passIndex + 1);
                beforeQueryProgress.put("retrievalPassCount", retrievalPlan.passes().size());
                beforeQueryProgress.put("processedTerms", processedTerms);
                beforeQueryProgress.put("totalTerms", retrievalPlan.totalTerms());
                beforeQueryProgress.put("activeTerm", term);
                beforeQueryProgress.put("activeAction", "document_recall");
                beforeQueryProgress.put("documentCount", docs.size());
                beforeQueryProgress.put("knowledgeUnitCount", knowledgeUnits.size());
                beforeQueryProgress.put("chunkCount", chunks.size());
                markJobProgress(jobId, "collecting_candidate_documents", beforeQueryProgress);
                for (Map<String, Object> item : filterExcludedItems(
                        collectDocuments(term, includeDataSourceIds, excludeDataSourceIds),
                        excludedTerms,
                        excludedStats
                )) {
                    docs.add((String) item.get("docId"), item, termWeight * retrievalScore(item));
                }
                processedTerms++;
                termIndex++;
                if (processedTerms % 2 == 0 || processedTerms == retrievalPlan.totalTerms()) {
                    Map<String, Object> progress = new LinkedHashMap<>();
                    progress.put("retrievalPass", pass.name());
                    progress.put("dimension", pass.dimensionName());
                    progress.put("retrievalPassIndex", passIndex + 1);
                    progress.put("retrievalPassCount", retrievalPlan.passes().size());
                    progress.put("processedTerms", processedTerms);
                    progress.put("totalTerms", retrievalPlan.totalTerms());
                    progress.put("excludedTerms", excludedTerms);
                    progress.put("excludedEvidenceCount", excludedStats.getOrDefault("excluded", 0));
                    progress.put("documentCount", docs.size());
                    progress.put("knowledgeUnitCount", knowledgeUnits.size());
                    progress.put("chunkCount", chunks.size());
                    markJobProgress(jobId, "collecting_candidate_documents", progress);
                }
            }
        }

        collectVectorEvidence(
                jobId,
                buildVectorRetrievalQuery(domain, topic, retrievalPlan),
                excludedTerms,
                excludedStats,
                includeDataSourceIds,
                excludeDataSourceIds,
                knowledgeUnits,
                chunks
        );
        List<Map<String, Object>> graphFacts = collectGraphEvidence(
                jobId,
                retrievalPlan,
                excludedTerms,
                excludedStats,
                includeDataSourceIds,
                excludeDataSourceIds,
                docs,
                chunks
        );
        backfillDocumentsFromEvidence(includeDataSourceIds, excludeDataSourceIds, docs, knowledgeUnits, chunks);
        List<UUID> candidateDocIds = docs.topIds(candidateDocumentRecallLimit()).stream().map(UUID::fromString).toList();
        collectDimensionEvidence(
                jobId,
                retrievalPlan,
                excludedTerms,
                excludedStats,
                includeDataSourceIds,
                excludeDataSourceIds,
                candidateDocIds,
                knowledgeUnits,
                chunks
        );
        applyCoverageBackfill(
                jobId,
                domain,
                topic,
                retrievalPlan,
                excludedTerms,
                excludedStats,
                includeDataSourceIds,
                excludeDataSourceIds,
                candidateDocIds,
                docs,
                knowledgeUnits,
                chunks
        );

        List<Map<String, Object>> topDocuments = docs.topN(maxDocumentsForCompilation(terms.size()));
        List<Map<String, Object>> candidateKnowledgeUnits = knowledgeUnits.topN(maxKnowledgeUnitsForCompilation(terms.size()));
        List<Map<String, Object>> candidateChunks = chunks.topN(maxChunksForCompilation(terms.size()));

        List<String> evidenceWarnings = new ArrayList<>();
        if (topDocuments.isEmpty() && candidateKnowledgeUnits.isEmpty() && candidateChunks.isEmpty()) {
            markJobProgress(jobId, "insufficient_evidence", Map.of(
                    "documentCount", topDocuments.size(),
                    "knowledgeUnitCount", candidateKnowledgeUnits.size(),
                    "chunkCount", candidateChunks.size(),
                    "requiredDocumentCount", minDocumentsForCompilation(),
                    "requiredKnowledgeUnitCount", minKnowledgeUnitsForCompilation(),
                    "requiredChunkCount", minChunksForCompilation(),
                    "reason", "NO_EVIDENCE_MATCHED"
            ));
            throw new IllegalStateException("DOMAIN_EVIDENCE_EMPTY");
        }
        if (topDocuments.size() < minDocumentsForCompilation()
                || candidateKnowledgeUnits.size() < minKnowledgeUnitsForCompilation()
                || candidateChunks.size() < minChunksForCompilation()) {
            evidenceWarnings.add("证据覆盖低于推荐阈值，知识包将标记为需复核");
            markJobProgress(jobId, "insufficient_evidence", Map.of(
                    "documentCount", topDocuments.size(),
                    "knowledgeUnitCount", candidateKnowledgeUnits.size(),
                    "chunkCount", candidateChunks.size(),
                    "requiredDocumentCount", minDocumentsForCompilation(),
                    "requiredKnowledgeUnitCount", minKnowledgeUnitsForCompilation(),
                    "requiredChunkCount", minChunksForCompilation(),
                    "continued", true
            ));
        }

        int evidenceCandidateLimit = evidenceCandidateLimit();
        int evidenceFinalLimit = evidenceFinalLimit();
        int perDocSoftCap = evidencePerDocumentSoftCap();
        int perDocHardCap = evidencePerDocumentHardCap();
        List<EvidenceCandidate> selectedEvidence = selectFinalEvidence(
                candidateKnowledgeUnits,
                candidateChunks,
                evidenceCandidateLimit,
                evidenceFinalLimit,
                perDocSoftCap,
                perDocHardCap
        );
        List<Map<String, Object>> topKnowledgeUnits = new ArrayList<>();
        List<Map<String, Object>> topChunks = new ArrayList<>();
        for (EvidenceCandidate item : selectedEvidence) {
            if ("knowledge_unit".equals(item.type())) {
                topKnowledgeUnits.add(item.payload());
            } else if ("chunk".equals(item.type())) {
                topChunks.add(item.payload());
            }
        }
        sanitizeInternalFields(topKnowledgeUnits);
        sanitizeInternalFields(topChunks);
        markJobProgress(jobId, "evidence_selected", Map.of(
                "candidateDocumentCount", docs.size(),
                "candidateKnowledgeUnitCount", candidateKnowledgeUnits.size(),
                "candidateChunkCount", candidateChunks.size(),
                "selectedKnowledgeUnitCount", topKnowledgeUnits.size(),
                "selectedChunkCount", topChunks.size(),
                "selectedEvidenceCount", selectedEvidence.size(),
                "excludedEvidenceCount", excludedStats.getOrDefault("excluded", 0),
                "retrievalPlan", retrievalPlan.toProgressMap()
        ));

        List<String> evidenceRefs = new ArrayList<>();
        for (Map<String, Object> item : topDocuments) {
            evidenceRefs.add("document:" + item.get("docId"));
        }
        for (Map<String, Object> item : topKnowledgeUnits) {
            evidenceRefs.add("knowledge_unit:" + item.get("knowledgeUnitId"));
        }
        for (Map<String, Object> item : topChunks) {
            evidenceRefs.add("chunk:" + item.get("chunkId"));
        }

        List<Map<String, Object>> selectedGraphFacts = selectGraphFactsForEvidence(graphFacts, topDocuments, topKnowledgeUnits, topChunks);
        Map<String, Object> topicSubgraph = buildTopicSubgraph(retrievalPlan, selectedGraphFacts, topDocuments, topKnowledgeUnits, topChunks);
        Map<String, Object> evidencePack = buildEvidencePack(topDocuments, topKnowledgeUnits, topChunks, selectedGraphFacts, evidenceRefs, evidenceWarnings);

        return new EvidenceBundle(
                retrievalPlan.allTerms(),
                topDocuments,
                topKnowledgeUnits,
                topChunks,
                selectedGraphFacts,
                topicSubgraph,
                evidencePack,
                evidenceRefs,
                evidenceWarnings
        );
    }

    private List<EvidenceCandidate> selectFinalEvidence(
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks,
            int candidateLimit,
            int finalLimit,
            int perDocSoftCap,
            int perDocHardCap
    ) {
        List<EvidenceCandidate> candidates = new ArrayList<>();
        int kuLimit = Math.max(1, Math.min(candidateLimit, knowledgeUnits.size()));
        int chunkLimit = Math.max(1, Math.min(candidateLimit, chunks.size()));
        for (int i = 0; i < kuLimit; i++) {
            Map<String, Object> item = knowledgeUnits.get(i);
            candidates.add(new EvidenceCandidate(
                    "knowledge_unit",
                    String.valueOf(item.get("knowledgeUnitId")),
                    String.valueOf(item.get("docId")),
                    retrievalScore(item) + 2.0 / (i + 2.0),
                    item
            ));
        }
        for (int i = 0; i < chunkLimit; i++) {
            Map<String, Object> item = chunks.get(i);
            candidates.add(new EvidenceCandidate(
                    "chunk",
                    String.valueOf(item.get("chunkId")),
                    String.valueOf(item.get("docId")),
                    retrievalScore(item) + 1.7 / (i + 2.0),
                    item
            ));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble(EvidenceCandidate::score).reversed());

        Map<String, Double> docStrength = new HashMap<>();
        double strongest = 0.0;
        for (EvidenceCandidate candidate : candidates) {
            double score = docStrength.merge(candidate.docId(), candidate.score(), Math::max);
            if (score > strongest) {
                strongest = score;
            }
        }
        Map<String, Integer> dynamicCapByDoc = new HashMap<>();
        double baseline = strongest <= 0.0 ? 1.0 : strongest;
        for (Map.Entry<String, Double> entry : docStrength.entrySet()) {
            double normalized = Math.max(0.0, Math.min(1.0, entry.getValue() / baseline));
            int dynamicCap = (int) Math.ceil(perDocSoftCap + (perDocHardCap - perDocSoftCap) * normalized * 0.6);
            dynamicCapByDoc.put(entry.getKey(), Math.max(perDocSoftCap, Math.min(perDocHardCap, dynamicCap)));
        }

        int effectiveFinalLimit = Math.max(1, finalLimit);
        List<EvidenceCandidate> selected = new ArrayList<>();
        Map<String, Integer> selectedByDoc = new HashMap<>();
        Set<String> selectedKeys = new HashSet<>();
        fillEvidenceByMmr(
                candidates,
                selected,
                selectedByDoc,
                selectedKeys,
                dynamicCapByDoc,
                Math.max(1, perDocSoftCap),
                effectiveFinalLimit
        );
        if (selected.size() < effectiveFinalLimit) {
            fillEvidenceByDynamicCap(candidates, selected, selectedByDoc, selectedKeys, dynamicCapByDoc, effectiveFinalLimit);
        }
        if (selected.size() < effectiveFinalLimit) {
            fillEvidenceByCap(candidates, selected, selectedByDoc, selectedKeys, Math.max(1, perDocHardCap), effectiveFinalLimit);
        }
        return selected;
    }

    private void fillEvidenceByMmr(
            List<EvidenceCandidate> candidates,
            List<EvidenceCandidate> selected,
            Map<String, Integer> selectedByDoc,
            Set<String> selectedKeys,
            Map<String, Integer> dynamicCapByDoc,
            int perDocSoftCap,
            int finalLimit
    ) {
        while (selected.size() < finalLimit) {
            EvidenceCandidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (EvidenceCandidate candidate : candidates) {
                String dedupeKey = candidate.type() + ":" + candidate.id();
                if (selectedKeys.contains(dedupeKey)) {
                    continue;
                }
                int current = selectedByDoc.getOrDefault(candidate.docId(), 0);
                int dynamicCap = dynamicCapByDoc.getOrDefault(candidate.docId(), perDocSoftCap);
                if (current >= Math.max(perDocSoftCap, dynamicCap)) {
                    continue;
                }
                double score = mmrScore(candidate, selected, current);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                return;
            }
            String dedupeKey = best.type() + ":" + best.id();
            selectedKeys.add(dedupeKey);
            selected.add(best);
            selectedByDoc.put(best.docId(), selectedByDoc.getOrDefault(best.docId(), 0) + 1);
        }
    }

    private double mmrScore(EvidenceCandidate candidate, List<EvidenceCandidate> selected, int sameDocCount) {
        if (selected.isEmpty()) {
            return candidate.score();
        }
        double maxSimilarity = 0.0d;
        String candidateText = normalizeForMatch(evidenceText(candidate.payload()));
        for (EvidenceCandidate existing : selected) {
            double similarity = candidate.docId().equals(existing.docId()) ? 0.28d : 0.0d;
            similarity += lexicalOverlap(candidateText, normalizeForMatch(evidenceText(existing.payload()))) * 0.72d;
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
            }
        }
        double docPenalty = sameDocCount * 0.35d;
        return candidate.score() * 0.78d - maxSimilarity * 1.25d - docPenalty;
    }

    private double lexicalOverlap(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0.0d;
        }
        int sample = Math.min(Math.min(left.length(), right.length()), 180);
        if (sample < 12) {
            return left.equals(right) ? 1.0d : 0.0d;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 4 <= sample; i += 4) {
            String token = left.substring(i, i + 4);
            if (!token.isBlank()) {
                total++;
                if (right.contains(token)) {
                    hit++;
                }
            }
        }
        return total == 0 ? 0.0d : (double) hit / (double) total;
    }

    private void fillEvidenceByCap(
            List<EvidenceCandidate> candidates,
            List<EvidenceCandidate> selected,
            Map<String, Integer> selectedByDoc,
            Set<String> selectedKeys,
            int perDocCap,
            int finalLimit
    ) {
        for (EvidenceCandidate candidate : candidates) {
            if (selected.size() >= finalLimit) {
                return;
            }
            String dedupeKey = candidate.type() + ":" + candidate.id();
            if (!selectedKeys.add(dedupeKey)) {
                continue;
            }
            int current = selectedByDoc.getOrDefault(candidate.docId(), 0);
            if (current >= perDocCap) {
                selectedKeys.remove(dedupeKey);
                continue;
            }
            selected.add(candidate);
            selectedByDoc.put(candidate.docId(), current + 1);
        }
    }

    private void fillEvidenceByDynamicCap(
            List<EvidenceCandidate> candidates,
            List<EvidenceCandidate> selected,
            Map<String, Integer> selectedByDoc,
            Set<String> selectedKeys,
            Map<String, Integer> dynamicCapByDoc,
            int finalLimit
    ) {
        for (EvidenceCandidate candidate : candidates) {
            if (selected.size() >= finalLimit) {
                return;
            }
            String dedupeKey = candidate.type() + ":" + candidate.id();
            if (!selectedKeys.add(dedupeKey)) {
                continue;
            }
            int cap = dynamicCapByDoc.getOrDefault(candidate.docId(), 1);
            int current = selectedByDoc.getOrDefault(candidate.docId(), 0);
            if (current >= cap) {
                selectedKeys.remove(dedupeKey);
                continue;
            }
            selected.add(candidate);
            selectedByDoc.put(candidate.docId(), current + 1);
        }
    }

    private RetrievalPlan buildRetrievalPlan(
            DomainDefinition domain,
            TopicDefinition topic,
            List<String> allTerms,
            DomainKnowledgeRefinementService.RetrievalPlanResult llmPlan
    ) {
        LinkedHashSet<String> core = new LinkedHashSet<>();
        addTerm(core, domain.getName());
        addAllTerms(core, domain.getSeedQueriesJson());
        if (topic != null) {
            addTerm(core, topic.getName());
            addAllTerms(core, topic.getSeedQueriesJson());
        }

        LinkedHashSet<String> expanded = new LinkedHashSet<>(allTerms);
        expanded.removeAll(core);
        List<RetrievalDimension> dimensions = buildRetrievalDimensions(domain, topic, llmPlan, allTerms);
        for (RetrievalDimension dimension : dimensions) {
            expanded.removeAll(dimension.terms());
        }

        LinkedHashSet<String> recall = new LinkedHashSet<>();
        for (String term : allTerms) {
            for (String item : buildRecallTerms(term)) {
                addTerm(recall, item);
            }
        }
        recall.removeAll(core);
        recall.removeAll(expanded);

        int maxTerms = clampConfigured(appProperties.domainKnowledge().maxTerms(), 1, 64);
        List<RetrievalPass> passes = new ArrayList<>();
        List<String> coreTerms = limitTerms(core, Math.max(1, Math.min(12, Math.max(1, maxTerms / 2))));
        if (!coreTerms.isEmpty()) {
            passes.add(new RetrievalPass("core", "候选文档", 3.0, coreTerms));
        }
        for (RetrievalDimension dimension : dimensions) {
            List<String> dimensionTerms = limitTerms(dimension.terms(), Math.max(3, Math.min(10, maxTerms / 3)));
            if (!dimensionTerms.isEmpty()) {
                passes.add(new RetrievalPass("dimension", dimension.name(), 2.4, dimensionTerms));
            }
        }
        int remainingAfterCore = Math.max(0, maxTerms - coreTerms.size());
        List<String> expandedTerms = limitTerms(expanded, Math.max(0, Math.min(remainingAfterCore, Math.max(1, (maxTerms * 3) / 5))));
        if (!expandedTerms.isEmpty()) {
            passes.add(new RetrievalPass("expanded", "扩展召回", 2.0, expandedTerms));
        }
        int used = passes.stream().mapToInt(pass -> pass.terms().size()).sum();
        int remainingForRecall = Math.max(0, maxTerms - used);
        List<String> recallTerms = limitTerms(recall, remainingForRecall);
        if (!recallTerms.isEmpty()) {
            passes.add(new RetrievalPass("recall", "宽召回", 1.2, recallTerms));
        }
        if (passes.isEmpty()) {
            passes.add(new RetrievalPass("core", "候选文档", 2.0, limitTerms(allTerms, maxTerms)));
        }

        List<String> merged = new ArrayList<>();
        int totalTerms = 0;
        for (RetrievalPass pass : passes) {
            merged.addAll(pass.terms());
            totalTerms += pass.terms().size();
        }
        return new RetrievalPlan(passes, dimensions, merged, totalTerms, llmPlan == null ? Map.of() : llmPlan.raw());
    }

    private List<RetrievalDimension> buildRetrievalDimensions(
            DomainDefinition domain,
            TopicDefinition topic,
            DomainKnowledgeRefinementService.RetrievalPlanResult llmPlan,
            List<String> allTerms
    ) {
        List<RetrievalDimension> result = new ArrayList<>();
        if (llmPlan != null) {
            for (DomainKnowledgeRefinementService.RetrievalDimensionPlan item : llmPlan.dimensions()) {
                LinkedHashSet<String> terms = new LinkedHashSet<>();
                addTerm(terms, item.name());
                addAllTerms(terms, item.queries());
                addAllTerms(terms, item.synonyms());
                addAllTerms(terms, item.requiredQuestions());
                result.add(new RetrievalDimension(
                        item.name(),
                        limitTerms(terms, 16),
                        item.requiredQuestions() == null ? List.of() : item.requiredQuestions(),
                        normalizeEvidenceTypes(item.evidenceTypes()),
                        Math.max(3, item.minEvidence())
                ));
            }
        }
        if (result.isEmpty()) {
            for (String dimension : extractKnowledgeDimensions(domain, topic).stream().limit(6).toList()) {
                LinkedHashSet<String> terms = new LinkedHashSet<>();
                addTerm(terms, dimension);
                for (String keyword : dimensionKeywords(dimension)) {
                    addTerm(terms, keyword);
                }
                for (String term : allTerms) {
                    if (groupScore(normalizeForMatch(term), dimension) > 0) {
                        addTerm(terms, term);
                    }
                }
                result.add(new RetrievalDimension(dimension, limitTerms(terms, 12), List.of(), List.of("knowledge_unit", "chunk"), 8));
            }
        }
        return result.stream().limit(8).toList();
    }

    private List<String> normalizeEvidenceTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of("knowledge_unit", "chunk");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if ("document".equals(normalized) || "knowledge_unit".equals(normalized) || "chunk".equals(normalized)) {
                result.add(normalized);
            }
        }
        return result.isEmpty() ? List.of("knowledge_unit", "chunk") : new ArrayList<>(result);
    }

    private List<String> limitTerms(LinkedHashSet<String> terms, int limit) {
        if (limit <= 0 || terms.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(limit);
        for (String term : terms) {
            if (result.size() >= limit) {
                break;
            }
            result.add(term);
        }
        return result;
    }

    private List<String> limitTerms(List<String> terms, int limit) {
        if (limit <= 0 || terms.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(limit);
        for (String term : terms) {
            if (result.size() >= limit) {
                break;
            }
            result.add(term);
        }
        return result;
    }

    private List<String> buildRecallTerms(String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = cleanupFragment(term);
        if (normalized.length() <= 10) {
            terms.add(normalized);
            return new ArrayList<>(terms);
        }
        for (String token : normalized.split("\\s+")) {
            String cleaned = cleanupFragment(token);
            if (cleaned.length() >= 2 && cleaned.length() <= 24 && !isGenericFragment(cleaned)) {
                terms.add(cleaned);
            }
        }
        if (isLikelyChineseText(normalized)) {
            for (String piece : sliceByLength(normalized, 4, 16)) {
                if (!isGenericFragment(piece)) {
                    terms.add(piece);
                }
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean isLikelyChineseText(String value) {
        int chinese = 0;
        for (char c : value.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
            }
        }
        return chinese >= Math.max(2, value.length() / 3);
    }

    private List<String> sliceByLength(String text, int min, int max) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int length = text.length();
        for (int size = Math.max(2, min); size <= Math.max(min, max); size += 2) {
            if (size > length) {
                break;
            }
            result.add(text.substring(0, size));
            result.add(text.substring(length - size));
            if (length > size * 2) {
                int mid = length / 2;
                int start = Math.max(0, mid - size / 2);
                int end = Math.min(length, start + size);
                result.add(text.substring(start, end));
            }
        }
        return new ArrayList<>(result);
    }

    private int maxDocumentsForCompilation(int termCount) {
        int base = Math.max(1, appProperties.domainKnowledge().documentLimitPerTerm());
        return Math.max(base, Math.min(evidenceCandidateLimit(), base * Math.max(1, Math.min(termCount, 12))));
    }

    private int maxKnowledgeUnitsForCompilation(int termCount) {
        int base = Math.max(1, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm());
        return Math.max(base, Math.min(evidenceCandidateLimit(), base * Math.max(1, Math.min(termCount, 14))));
    }

    private int maxChunksForCompilation(int termCount) {
        int base = Math.max(1, appProperties.domainKnowledge().chunkLimitPerTerm());
        return Math.max(base, Math.min(evidenceCandidateLimit(), base * Math.max(1, Math.min(termCount, 16))));
    }

    private int evidenceCandidateLimit() {
        return Math.max(40, appProperties.domainKnowledge().evidenceCandidateLimit());
    }

    private int evidenceFinalLimit() {
        return Math.max(20, appProperties.domainKnowledge().evidenceFinalLimit());
    }

    private int evidencePerDocumentSoftCap() {
        return Math.max(4, appProperties.domainKnowledge().evidencePerDocumentSoftCap());
    }

    private int evidencePerDocumentHardCap() {
        return Math.max(evidencePerDocumentSoftCap(), appProperties.domainKnowledge().evidencePerDocumentHardCap());
    }

    private int candidateDocumentRecallLimit() {
        return clampConfigured(maxDocumentsForCompilation(appProperties.domainKnowledge().maxTerms()) * 3, 100, 300);
    }

    private int clampConfigured(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int minDocumentsForCompilation() {
        return Math.max(1, appProperties.domainKnowledge().documentLimitPerTerm());
    }

    private int minKnowledgeUnitsForCompilation() {
        return Math.max(1, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm());
    }

    private int minChunksForCompilation() {
        return Math.max(1, appProperties.domainKnowledge().chunkLimitPerTerm());
    }

    private void applyCoverageBackfill(
            UUID jobId,
            DomainDefinition domain,
            TopicDefinition topic,
            RetrievalPlan retrievalPlan,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            List<UUID> candidateDocIds,
            ScoreAccumulator docs,
            ScoreAccumulator knowledgeUnits,
            ScoreAccumulator chunks
    ) {
        if (docs.size() >= minDocumentsForCompilation()
                && knowledgeUnits.size() >= minKnowledgeUnitsForCompilation()
                && chunks.size() >= minChunksForCompilation()) {
            return;
        }
        markJobProgress(jobId, "collecting_evidence_backfill", Map.of(
                "documentCount", docs.size(),
                "knowledgeUnitCount", knowledgeUnits.size(),
                "chunkCount", chunks.size(),
                "requiredDocumentCount", minDocumentsForCompilation(),
                "requiredKnowledgeUnitCount", minKnowledgeUnitsForCompilation(),
                "requiredChunkCount", minChunksForCompilation()
        ));

        int docBackfillLimit = Math.max(minDocumentsForCompilation() * 2, 120);
        int kuBackfillLimit = Math.max(minKnowledgeUnitsForCompilation() * 2, 280);
        int chunkBackfillLimit = Math.max(minChunksForCompilation() * 2, 420);

        for (String dimensionTerm : coverageBackfillTerms(domain, topic, retrievalPlan)) {
            if (docs.size() >= minDocumentsForCompilation()
                    && knowledgeUnits.size() >= minKnowledgeUnitsForCompilation()
                    && chunks.size() >= minChunksForCompilation()) {
                break;
            }
            for (Map<String, Object> item : filterExcludedItems(collectDocuments(dimensionTerm, includeIds, excludeIds), excludedTerms, excludedStats)) {
                docs.add((String) item.get("docId"), item, 0.85 * retrievalScore(item));
            }
            for (Map<String, Object> item : filterExcludedItems(collectKnowledgeUnits(dimensionTerm, includeIds, excludeIds, candidateDocIds, Math.max(4, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm())), excludedTerms, excludedStats)) {
                knowledgeUnits.add((String) item.get("knowledgeUnitId"), item, 0.80 * retrievalScore(item));
            }
            for (Map<String, Object> item : filterExcludedItems(collectChunks(dimensionTerm, includeIds, excludeIds, candidateDocIds, Math.max(6, appProperties.domainKnowledge().chunkLimitPerTerm())), excludedTerms, excludedStats)) {
                chunks.add((String) item.get("chunkId"), item, 0.72 * retrievalScore(item));
            }
        }
        if (docs.size() >= minDocumentsForCompilation()
                && knowledgeUnits.size() >= minKnowledgeUnitsForCompilation()
                && chunks.size() >= minChunksForCompilation()) {
            return;
        }

        for (Map<String, Object> item : filterExcludedItems(
                collectRecentDocuments(includeIds, excludeIds, docBackfillLimit),
                excludedTerms,
                excludedStats
        )) {
            docs.add((String) item.get("docId"), item, 0.35);
        }
        for (Map<String, Object> item : filterExcludedItems(
                collectRecentKnowledgeUnits(includeIds, excludeIds, kuBackfillLimit),
                excludedTerms,
                excludedStats
        )) {
            knowledgeUnits.add((String) item.get("knowledgeUnitId"), item, 0.30);
        }
        for (Map<String, Object> item : filterExcludedItems(
                collectRecentChunks(includeIds, excludeIds, chunkBackfillLimit),
                excludedTerms,
                excludedStats
        )) {
            chunks.add((String) item.get("chunkId"), item, 0.25);
        }
    }

    private void collectVectorEvidence(
            UUID jobId,
            String queryText,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            ScoreAccumulator knowledgeUnits,
            ScoreAccumulator chunks
    ) {
        if (queryText == null || queryText.isBlank()) {
            return;
        }
        try {
            markJobProgress(jobId, "collecting_vector_evidence", Map.of(
                    "queryChars", queryText.length(),
                    "knowledgeUnitCount", knowledgeUnits.size(),
                    "chunkCount", chunks.size()
            ));
            List<Double> vector = embeddingService.embedQuery(queryText);
            if (vector == null || vector.isEmpty()) {
                return;
            }
            String literal = vectorLiteral(vector);
            int dimensions = vector.size();
            int vectorLimit = Math.max(24, Math.min(evidenceCandidateLimit() / 2, 120));
            for (Map<String, Object> item : filterExcludedItems(
                    collectVectorKnowledgeUnits(literal, dimensions, includeIds, excludeIds, vectorLimit),
                    excludedTerms,
                    excludedStats
            )) {
                knowledgeUnits.add((String) item.get("knowledgeUnitId"), item, 1.35 * retrievalScore(item));
            }
            for (Map<String, Object> item : filterExcludedItems(
                    collectVectorChunks(literal, dimensions, includeIds, excludeIds, vectorLimit),
                    excludedTerms,
                    excludedStats
            )) {
                chunks.add((String) item.get("chunkId"), item, 1.15 * retrievalScore(item));
            }
            markJobProgress(jobId, "collecting_vector_evidence", Map.of(
                    "queryChars", queryText.length(),
                    "knowledgeUnitCount", knowledgeUnits.size(),
                    "chunkCount", chunks.size(),
                    "dimensions", dimensions,
                    "vectorLimit", vectorLimit
            ));
        } catch (Exception ex) {
            log.warn("Domain knowledge vector evidence skipped: jobId={}, error={}", jobId, ex.getMessage());
            markJobProgress(jobId, "collecting_vector_evidence_skipped", Map.of(
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }
    }

    private List<Map<String, Object>> collectGraphEvidence(
            UUID jobId,
            RetrievalPlan retrievalPlan,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            ScoreAccumulator docs,
            ScoreAccumulator chunks
    ) {
        if (!knowledgeGraphStoreClient.isConfigured() || retrievalPlan == null || retrievalPlan.allTerms().isEmpty()) {
            return List.of();
        }
        String queryText = String.join(" ", retrievalPlan.allTerms());
        try {
            int graphLimit = Math.max(30, Math.min(evidenceCandidateLimit() * 2, 160));
            markJobProgress(jobId, "collecting_graph_evidence", Map.of(
                    "queryChars", queryText.length(),
                    "graphLimit", graphLimit,
                    "chunkCount", chunks.size()
            ));
            List<Map<String, Object>> facts = knowledgeGraphStoreClient.searchFacts(queryText, graphLimit);
            if (facts.isEmpty()) {
                markJobProgress(jobId, "collecting_graph_evidence", Map.of(
                        "graphFactCount", 0,
                        "chunkCount", chunks.size()
                ));
                return List.of();
            }
            List<Map<String, Object>> graphChunks = filterExcludedItems(
                    collectGraphFactChunks(facts, includeIds, excludeIds),
                    excludedTerms,
                    excludedStats
            );
            for (Map<String, Object> item : graphChunks) {
                chunks.add((String) item.get("chunkId"), item, 1.45 * retrievalScore(item));
            }
            List<UUID> graphDocIds = graphChunks.stream()
                    .map(item -> parseUuid(String.valueOf(item.get("docId"))))
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            for (Map<String, Object> item : collectDocumentsByIds(graphDocIds, includeIds, excludeIds)) {
                docs.add((String) item.get("docId"), item, 0.9 * retrievalScore(item));
            }
            Set<String> acceptedChunkIds = graphChunks.stream()
                    .map(item -> trimToNull(item.get("chunkId")))
                    .filter(id -> id != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Map<String, Object>> acceptedFacts = facts.stream()
                    .filter(fact -> acceptedChunkIds.contains(trimToNull(fact.get("chunkId"))))
                    .map(this::slimGraphFact)
                    .limit(80)
                    .toList();
            markJobProgress(jobId, "collecting_graph_evidence", Map.of(
                    "graphFactCount", facts.size(),
                    "acceptedGraphFactCount", acceptedFacts.size(),
                    "graphEvidenceChunkCount", graphChunks.size(),
                    "documentCount", docs.size(),
                    "chunkCount", chunks.size()
            ));
            return acceptedFacts;
        } catch (Exception ex) {
            log.warn("Domain knowledge graph evidence skipped: jobId={}, error={}", jobId, ex.getMessage());
            markJobProgress(jobId, "collecting_graph_evidence_skipped", Map.of(
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
            return List.of();
        }
    }

    private List<Map<String, Object>> collectGraphFactChunks(
            List<Map<String, Object>> facts,
            List<UUID> includeIds,
            List<UUID> excludeIds
    ) {
        List<UUID> chunkIds = facts.stream()
                .map(item -> parseUuid(trimToNull(item.get("chunkId"))))
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> chunkById = jdbcTemplate.query("""
                SELECT c.id AS chunk_id,
                       c.doc_id,
                       c.chunk_no,
                       c.title,
                       c.page_no,
                       c.content,
                       d.source_file,
                       d.source_filename
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                WHERE c.id IN (:chunkIds)
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                """,
                new MapSqlParameterSource()
                        .addValue("chunkIds", chunkIds)
                        .addValue("includeAll", includeIds.isEmpty())
                        .addValue("includeIds", includeIds.isEmpty() ? List.of(UUID.randomUUID()) : includeIds)
                        .addValue("excludeEmpty", excludeIds.isEmpty())
                        .addValue("excludeIds", excludeIds.isEmpty() ? List.of(UUID.randomUUID()) : excludeIds),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", rs.getObject("chunk_id", UUID.class).toString());
                    item.put("docId", rs.getObject("doc_id", UUID.class).toString());
                    item.put("chunkNo", rs.getInt("chunk_no"));
                    item.put("title", rs.getString("title"));
                    item.put("pageNo", rs.getObject("page_no"));
                    item.put("_content_raw", rs.getString("content"));
                    item.put("sourceFile", rs.getString("source_file"));
                    item.put("sourceFilename", rs.getString("source_filename"));
                    return item;
                }).stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("chunkId")),
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
        ));

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> fact : facts) {
            String chunkId = trimToNull(fact.get("chunkId"));
            if (chunkId == null || !seen.add(chunkId)) {
                continue;
            }
            Map<String, Object> chunk = chunkById.get(chunkId);
            if (chunk == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(chunk);
            String relationType = firstNonBlank(trimToNull(fact.get("relationType")), "related_to");
            String statement = trimToNull(fact.get("statement"));
            String graphStatement = "图谱事实: "
                    + firstNonBlank(trimToNull(fact.get("subject")), "未知实体")
                    + " - " + relationType + " - "
                    + firstNonBlank(trimToNull(fact.get("object")), "未知实体")
                    + (statement == null ? "" : "。" + statement);
            item.put("title", firstNonBlank(trimToNull(chunk.get("title")), "图谱事实"));
            item.put("snippet", graphStatement);
            item.put("_content_raw", graphStatement + "\n来源片段: " + firstNonBlank(trimToNull(chunk.get("_content_raw")), ""));
            item.put("_score", 2.4d + retrievalScore(fact));
            item.put("_retrieval", "graph_fact");
            item.put("graphFactKey", fact.get("factKey"));
            item.put("graphRelationType", relationType);
            result.add(item);
        }
        return result;
    }

    private void collectDimensionEvidence(
            UUID jobId,
            RetrievalPlan retrievalPlan,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            List<UUID> candidateDocIds,
            ScoreAccumulator knowledgeUnits,
            ScoreAccumulator chunks
    ) {
        int dimensionIndex = 0;
        for (RetrievalDimension dimension : retrievalPlan.dimensions()) {
            if (domainRefineJobProgressService.isCancelled(jobId)) {
                throw new TaskCancelledException("DOMAIN_REFINE_CANCELLED");
            }
            dimensionIndex++;
            int beforeKu = knowledgeUnits.size();
            int beforeChunks = chunks.size();
            int termIndex = 0;
            for (String term : dimension.terms()) {
                double termWeight = 2.2 + (1.0 / (termIndex + 2.0));
                int unitLimit = Math.max(4, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm());
                int chunkLimit = Math.max(6, appProperties.domainKnowledge().chunkLimitPerTerm());
                Map<String, Object> beforeQueryProgress = new LinkedHashMap<>();
                beforeQueryProgress.put("dimension", dimension.name());
                beforeQueryProgress.put("dimensionIndex", dimensionIndex);
                beforeQueryProgress.put("dimensionCount", retrievalPlan.dimensions().size());
                beforeQueryProgress.put("dimensionTerms", dimension.terms());
                beforeQueryProgress.put("activeTerm", term);
                beforeQueryProgress.put("activeAction", "dimension_evidence_recall");
                beforeQueryProgress.put("candidateDocumentCount", candidateDocIds == null ? 0 : candidateDocIds.size());
                beforeQueryProgress.put("knowledgeUnitCount", knowledgeUnits.size());
                beforeQueryProgress.put("chunkCount", chunks.size());
                markJobProgress(jobId, "collecting_dimension_evidence", beforeQueryProgress);
                for (Map<String, Object> item : filterExcludedItems(
                        collectKnowledgeUnits(term, includeIds, excludeIds, candidateDocIds, unitLimit),
                        excludedTerms,
                        excludedStats
                )) {
                    knowledgeUnits.add((String) item.get("knowledgeUnitId"), item, termWeight * 1.25 * retrievalScore(item));
                }
                for (Map<String, Object> item : filterExcludedItems(
                        collectChunks(term, includeIds, excludeIds, candidateDocIds, chunkLimit),
                        excludedTerms,
                        excludedStats
                )) {
                    chunks.add((String) item.get("chunkId"), item, termWeight * retrievalScore(item));
                }
                termIndex++;
            }
            int addedKu = Math.max(0, knowledgeUnits.size() - beforeKu);
            int addedChunks = Math.max(0, chunks.size() - beforeChunks);
            int addedEvidence = addedKu + addedChunks;
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("dimension", dimension.name());
            progress.put("dimensionIndex", dimensionIndex);
            progress.put("dimensionCount", retrievalPlan.dimensions().size());
            progress.put("dimensionTerms", dimension.terms());
            progress.put("requiredQuestions", dimension.requiredQuestions());
            progress.put("evidenceTypes", dimension.evidenceTypes());
            progress.put("candidateDocumentCount", candidateDocIds == null ? 0 : candidateDocIds.size());
            progress.put("dimensionKnowledgeUnitAdded", addedKu);
            progress.put("dimensionChunkAdded", addedChunks);
            progress.put("knowledgeUnitCount", knowledgeUnits.size());
            progress.put("chunkCount", chunks.size());
            progress.put("excludedEvidenceCount", excludedStats.getOrDefault("excluded", 0));
            progress.put("minEvidence", dimension.minEvidence());
            markJobProgress(jobId, "collecting_dimension_evidence", progress);
            if (addedEvidence < dimension.minEvidence()) {
                backfillDimensionEvidence(
                        jobId,
                        dimension,
                        dimensionIndex,
                        retrievalPlan.dimensions().size(),
                        excludedTerms,
                        excludedStats,
                        includeIds,
                        excludeIds,
                        knowledgeUnits,
                        chunks,
                        addedEvidence
                );
            }
        }
    }

    private void backfillDimensionEvidence(
            UUID jobId,
            RetrievalDimension dimension,
            int dimensionIndex,
            int dimensionCount,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            ScoreAccumulator knowledgeUnits,
            ScoreAccumulator chunks,
            int evidenceBeforeBackfill
    ) {
        if (domainRefineJobProgressService.isCancelled(jobId)) {
            throw new TaskCancelledException("DOMAIN_REFINE_CANCELLED");
        }
        int beforeKu = knowledgeUnits.size();
        int beforeChunks = chunks.size();
        int unitLimit = Math.max(8, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm() * 2);
        int chunkLimit = Math.max(12, appProperties.domainKnowledge().chunkLimitPerTerm() * 2);
        for (String term : dimension.terms().stream().limit(8).toList()) {
            for (Map<String, Object> item : filterExcludedItems(
                    collectKnowledgeUnits(term, includeIds, excludeIds, List.of(), unitLimit),
                    excludedTerms,
                    excludedStats
            )) {
                knowledgeUnits.add((String) item.get("knowledgeUnitId"), item, 0.95 * retrievalScore(item));
            }
            for (Map<String, Object> item : filterExcludedItems(
                    collectChunks(term, includeIds, excludeIds, List.of(), chunkLimit),
                    excludedTerms,
                    excludedStats
            )) {
                chunks.add((String) item.get("chunkId"), item, 0.88 * retrievalScore(item));
            }
            int addedEvidence = evidenceBeforeBackfill
                    + Math.max(0, knowledgeUnits.size() - beforeKu)
                    + Math.max(0, chunks.size() - beforeChunks);
            if (addedEvidence >= dimension.minEvidence()) {
                break;
            }
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("dimension", dimension.name());
        progress.put("dimensionIndex", dimensionIndex);
        progress.put("dimensionCount", dimensionCount);
        progress.put("dimensionTerms", dimension.terms());
        progress.put("requiredQuestions", dimension.requiredQuestions());
        progress.put("minEvidence", dimension.minEvidence());
        progress.put("dimensionKnowledgeUnitAdded", Math.max(0, knowledgeUnits.size() - beforeKu));
        progress.put("dimensionChunkAdded", Math.max(0, chunks.size() - beforeChunks));
        progress.put("knowledgeUnitCount", knowledgeUnits.size());
        progress.put("chunkCount", chunks.size());
        progress.put("excludedEvidenceCount", excludedStats.getOrDefault("excluded", 0));
        markJobProgress(jobId, "collecting_dimension_backfill", progress);
    }

    private String buildVectorRetrievalQuery(DomainDefinition domain, TopicDefinition topic, RetrievalPlan retrievalPlan) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addTerm(parts, domain.getName());
        addTerm(parts, domain.getGoal());
        addTerm(parts, domain.getDescription());
        if (topic != null) {
            addTerm(parts, topic.getName());
            addTerm(parts, topic.getDescription());
        }
        addAllTerms(parts, retrievalPlan.allTerms().stream().limit(16).toList());
        for (String dimension : extractKnowledgeDimensions(domain, topic)) {
            addTerm(parts, dimension);
        }
        return String.join("\n", parts).trim();
    }

    private void backfillDocumentsFromEvidence(
            List<UUID> includeIds,
            List<UUID> excludeIds,
            ScoreAccumulator docs,
            ScoreAccumulator knowledgeUnits,
            ScoreAccumulator chunks
    ) {
        LinkedHashSet<UUID> docIds = new LinkedHashSet<>();
        for (Map<String, Object> item : knowledgeUnits.items()) {
            addUuid(docIds, item.get("docId"));
        }
        for (Map<String, Object> item : chunks.items()) {
            addUuid(docIds, item.get("docId"));
        }
        docIds.removeIf(id -> docs.contains(id.toString()));
        if (docIds.isEmpty()) {
            return;
        }
        for (Map<String, Object> item : collectDocumentsByIds(new ArrayList<>(docIds), includeIds, excludeIds)) {
            docs.add((String) item.get("docId"), item, 0.95);
        }
    }

    private List<String> coverageBackfillTerms(DomainDefinition domain, TopicDefinition topic, RetrievalPlan retrievalPlan) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String dimension : extractKnowledgeDimensions(domain, topic)) {
            addTerm(terms, dimension);
            for (String keyword : dimensionKeywords(dimension)) {
                addTerm(terms, keyword);
            }
        }
        addAllTerms(terms, retrievalPlan.allTerms());
        addTerm(terms, domain.getName());
        if (topic != null) {
            addTerm(terms, topic.getName());
        }
        return terms.stream().limit(18).toList();
    }

    private void addUuid(Set<UUID> ids, Object value) {
        if (value == null) {
            return;
        }
        try {
            ids.add(UUID.fromString(String.valueOf(value)));
        } catch (Exception ignored) {
            // Ignore malformed ids from defensive snapshots.
        }
    }

    private List<String> buildTerms(
            DomainDefinition domain,
            TopicDefinition topic,
            List<String> excludedTerms,
            List<String> optimizedTerms
    ) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addAllTerms(terms, optimizedTerms);
        addTerm(terms, domain.getName());
        addAllTerms(terms, domain.getSeedQueriesJson());
        addFragments(terms, domain.getGoal());
        addFragments(terms, domain.getDescription());
        addFragments(terms, domain.getName());
        addAllFragments(terms, extractSetupHistory(domain.getMetadataJson()));
        if (topic != null) {
            addTerm(terms, topic.getName());
            addAllTerms(terms, topic.getSeedQueriesJson());
            addFragments(terms, topic.getDescription());
            addFragments(terms, topic.getName());
        }
        int maxTerms = clampConfigured(appProperties.domainKnowledge().maxTerms(), 1, 64);
        return terms.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !matchesExcludedTerm(value, excludedTerms))
                .limit(maxTerms)
                .toList();
    }

    private DomainKnowledgeRefinementService.RetrievalPlanResult optimizeRetrievalPlan(DomainDefinition domain, TopicDefinition topic) {
        List<String> setupHistory = extractSetupHistory(domain.getMetadataJson());
        List<String> excludedTerms = collectExcludedTerms(domain, topic);
        return domainKnowledgeRefinementService.planRetrievalPlan(
                domain.getName(),
                domain.getGoal(),
                domain.getDescription(),
                domain.getSeedQueriesJson(),
                excludedTerms,
                setupHistory,
                topic == null ? null : topic.getName(),
                topic == null ? null : topic.getDescription(),
                topic == null ? List.of() : topic.getSeedQueriesJson()
        );
    }

    private List<String> extractSetupHistory(Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object value = metadata.get("setupHistory");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String role = map.get("role") == null ? "" : String.valueOf(map.get("role")).trim();
            String content = map.get("content") == null ? "" : String.valueOf(map.get("content")).trim();
            if (!content.isBlank()) {
                result.add((role.isBlank() ? "" : role + ": ") + content);
            }
        }
        return result;
    }

    private void addAllTerms(LinkedHashSet<String> terms, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addTerm(terms, value);
            addFragments(terms, value);
        }
    }

    private void addAllFragments(LinkedHashSet<String> terms, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addFragments(terms, value);
        }
    }

    private void addTerm(LinkedHashSet<String> terms, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.length() <= 40) {
            terms.add(trimmed);
        }
    }

    private void addFragments(LinkedHashSet<String> terms, String value) {
        if (value == null) {
            return;
        }
        String normalized = value
                .replace('：', '\n')
                .replace(':', '\n')
                .replace('；', '\n')
                .replace(';', '\n')
                .replace('。', '\n')
                .replace('，', '\n')
                .replace(',', '\n')
                .replace('、', '\n')
                .replace('？', '\n')
                .replace('?', '\n')
                .replace('（', ' ')
                .replace('）', ' ')
                .replace('(', ' ')
                .replace(')', ' ');
        for (String line : normalized.split("\\R+")) {
            String trimmed = line.trim();
            if (trimmed.length() < 2) {
                continue;
            }
            for (String fragment : splitByConnector(trimmed)) {
                String cleaned = cleanupFragment(fragment);
                if (cleaned.length() >= 2 && cleaned.length() <= 64 && !isGenericFragment(cleaned)) {
                    terms.add(cleaned);
                    if (cleaned.length() > 20 && isLikelyChineseText(cleaned)) {
                        for (String piece : sliceByLength(cleaned, 6, 20)) {
                            if (!isGenericFragment(piece)) {
                                terms.add(piece);
                            }
                        }
                    }
                }
            }
        }
    }

    private List<String> splitByConnector(String value) {
        List<String> items = new ArrayList<>();
        String[] firstPass = value.split("(以及|及其|以及其|与|和|及|或|并|并且|相关|包括|涉及|围绕|关于|中的|中|的)");
        for (String item : firstPass) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                items.add(trimmed);
            }
        }
        if (items.isEmpty()) {
            items.add(value);
        }
        return items;
    }

    private String cleanupFragment(String value) {
        return value
                .replaceAll("^(请问|如何|怎么|哪些|什么是|什么|用于|为了|围绕|关于)", "")
                .replaceAll("(有哪些|是什么|怎么做|如何做|吗|呢)$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isGenericFragment(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return List.of(
                "领域", "专题", "知识库", "相关", "内容",
                "资料", "文件", "问题", "要求", "情况", "信息"
        ).contains(normalized);
    }

    private List<Map<String, Object>> collectDocuments(String term, List<UUID> includeIds, List<UUID> excludeIds) {
        MapSqlParameterSource params = dataSourceParams(term, includeIds, excludeIds)
                .addValue("limit", Math.max(1, appProperties.domainKnowledge().documentLimitPerTerm()));
        return jdbcTemplate.query("""
                WITH query AS (
                    SELECT plainto_tsquery('simple', :tsQuery) AS tsq
                )
                SELECT d.id, d.title, d.doc_type, d.source_file, d.source_filename, d.updated_at,
                       (
                           ts_rank_cd(COALESCE(d.search_tsv, ''::tsvector), query.tsq) * 2.4 +
                           similarity(lower(COALESCE(d.title, '')), :termLower) * 1.6 +
                           similarity(lower(COALESCE(d.source_filename, '')), :termLower) * 1.2 +
                           CASE WHEN lower(COALESCE(d.title, '')) LIKE :pattern THEN 1.0 ELSE 0.0 END +
                           CASE WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern THEN 0.7 ELSE 0.0 END
                       ) AS score
                FROM documents d, query
                WHERE (
                    COALESCE(d.search_tsv, ''::tsvector) @@ query.tsq
                    OR lower(COALESCE(d.title, '')) LIKE :pattern
                    OR lower(COALESCE(d.source_filename, '')) LIKE :pattern
                    OR lower(COALESCE(d.source_file, '')) LIKE :pattern
                )
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY score DESC, d.updated_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", rs.getObject("id", UUID.class).toString());
            item.put("title", rs.getString("title"));
            item.put("docType", rs.getString("doc_type"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("updatedAt", rs.getObject("updated_at"));
            item.put("_score", rs.getDouble("score"));
            item.put("_retrieval", "lexical");
            return item;
        });
    }

    private List<Map<String, Object>> collectKnowledgeUnits(String term, List<UUID> includeIds, List<UUID> excludeIds) {
        return collectKnowledgeUnits(term, includeIds, excludeIds, List.of(), Math.max(1, appProperties.domainKnowledge().knowledgeUnitLimitPerTerm()));
    }

    private List<Map<String, Object>> collectKnowledgeUnits(
            String term,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            List<UUID> candidateDocIds,
            int limit
    ) {
        MapSqlParameterSource params = dataSourceParams(term, includeIds, excludeIds)
                .addValue("candidateDocEmpty", candidateDocIds == null || candidateDocIds.isEmpty())
                .addValue("candidateDocIds", candidateDocIds == null || candidateDocIds.isEmpty() ? List.of(UUID.randomUUID()) : candidateDocIds)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                WITH query AS (
                    SELECT plainto_tsquery('simple', :tsQuery) AS tsq
                )
                SELECT ku.id, ku.doc_id, ku.chunk_id, ku.title, ku.subject, ku.indicator, ku.content, ku.source_page,
                       d.source_file, d.source_filename,
                       (
                           ts_rank_cd(COALESCE(ku.search_tsv, ''::tsvector), query.tsq) * 3.0 +
                           similarity(lower(COALESCE(ku.title, '')), :termLower) * 1.5 +
                           similarity(lower(COALESCE(ku.subject, '')), :termLower) * 1.4 +
                           similarity(lower(COALESCE(ku.indicator, '')), :termLower) * 1.2 +
                           similarity(lower(COALESCE(ku.content, '')), :termLower) * 0.7 +
                           CASE WHEN lower(COALESCE(ku.title, '')) LIKE :pattern THEN 1.2 ELSE 0.0 END +
                           CASE WHEN lower(COALESCE(ku.subject, '')) LIKE :pattern THEN 1.0 ELSE 0.0 END +
                           CASE WHEN lower(COALESCE(ku.indicator, '')) LIKE :pattern THEN 0.9 ELSE 0.0 END +
                           CASE WHEN lower(COALESCE(ku.content, '')) LIKE :pattern THEN 0.7 ELSE 0.0 END
                       ) AS score
                FROM knowledge_units ku
                JOIN documents d ON d.id = ku.doc_id
                CROSS JOIN query
                WHERE (
                    COALESCE(ku.search_tsv, ''::tsvector) @@ query.tsq
                    OR lower(COALESCE(ku.title, '')) LIKE :pattern
                    OR lower(COALESCE(ku.subject, '')) LIKE :pattern
                    OR lower(COALESCE(ku.indicator, '')) LIKE :pattern
                    OR lower(COALESCE(ku.content, '')) LIKE :pattern
                    OR lower(COALESCE(ku.normalized_text, '')) LIKE :pattern
                )
                AND (:candidateDocEmpty = true OR d.id IN (:candidateDocIds))
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY score DESC, ku.updated_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeUnitId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkId", rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class).toString());
            item.put("title", rs.getString("title"));
            item.put("subject", rs.getString("subject"));
            item.put("indicator", rs.getString("indicator"));
            item.put("content", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourcePage", rs.getObject("source_page"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("_score", rs.getDouble("score"));
            item.put("_retrieval", "lexical");
            return item;
        });
    }

    private List<Map<String, Object>> collectChunks(String term, List<UUID> includeIds, List<UUID> excludeIds) {
        return collectChunks(term, includeIds, excludeIds, List.of(), Math.max(1, appProperties.domainKnowledge().chunkLimitPerTerm()));
    }

    private List<Map<String, Object>> collectChunks(
            String term,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            List<UUID> candidateDocIds,
            int limit
    ) {
        MapSqlParameterSource params = dataSourceParams(term, includeIds, excludeIds)
                .addValue("candidateDocEmpty", candidateDocIds == null || candidateDocIds.isEmpty())
                .addValue("candidateDocIds", candidateDocIds == null || candidateDocIds.isEmpty() ? List.of(UUID.randomUUID()) : candidateDocIds)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                WITH query AS (
                    SELECT plainto_tsquery('simple', :tsQuery) AS tsq
                )
                SELECT c.id, c.doc_id, c.chunk_no, c.title, c.page_no, c.content,
                       d.source_file, d.source_filename,
                       (
                           ts_rank_cd(COALESCE(c.search_tsv, ''::tsvector), query.tsq) * 2.6 +
                           similarity(lower(COALESCE(c.title, '')), :termLower) * 1.3 +
                           similarity(lower(COALESCE(c.content, '')), :termLower) * 0.7 +
                           CASE WHEN lower(COALESCE(c.title, '')) LIKE :pattern THEN 1.0 ELSE 0.0 END +
                           CASE WHEN lower(COALESCE(c.content, '')) LIKE :pattern THEN 0.7 ELSE 0.0 END
                       ) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                CROSS JOIN query
                WHERE (
                    COALESCE(c.search_tsv, ''::tsvector) @@ query.tsq
                    OR lower(COALESCE(c.title, '')) LIKE :pattern
                    OR lower(COALESCE(c.content, '')) LIKE :pattern
                )
                AND (:candidateDocEmpty = true OR d.id IN (:candidateDocIds))
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY score DESC, c.created_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkNo", rs.getInt("chunk_no"));
            item.put("title", rs.getString("title"));
            item.put("pageNo", rs.getObject("page_no"));
            item.put("snippet", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("_score", rs.getDouble("score"));
            item.put("_retrieval", "lexical");
            return item;
        });
    }

    private List<Map<String, Object>> collectVectorKnowledgeUnits(
            String queryVectorLiteral,
            int dimensions,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            int limit
    ) {
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("queryVector", queryVectorLiteral)
                .addValue("dimensions", dimensions)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                SELECT ku.id, ku.doc_id, ku.chunk_id, ku.title, ku.subject, ku.indicator, ku.content, ku.source_page,
                       d.source_file, d.source_filename,
                       (1 - (kue.embedding_vector <=> CAST(:queryVector AS vector))) AS score
                FROM knowledge_unit_embeddings kue
                JOIN knowledge_units ku ON ku.id = kue.knowledge_unit_id
                JOIN documents d ON d.id = ku.doc_id
                WHERE kue.embedding_vector IS NOT NULL
                AND kue.dimensions = :dimensions
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY kue.embedding_vector <=> CAST(:queryVector AS vector)
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeUnitId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkId", rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class).toString());
            item.put("title", rs.getString("title"));
            item.put("subject", rs.getString("subject"));
            item.put("indicator", rs.getString("indicator"));
            item.put("content", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourcePage", rs.getObject("source_page"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("_score", rs.getDouble("score"));
            item.put("_retrieval", "vector");
            return item;
        });
    }

    private List<Map<String, Object>> collectVectorChunks(
            String queryVectorLiteral,
            int dimensions,
            List<UUID> includeIds,
            List<UUID> excludeIds,
            int limit
    ) {
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("queryVector", queryVectorLiteral)
                .addValue("dimensions", dimensions)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                SELECT c.id, c.doc_id, c.chunk_no, c.title, c.page_no, c.content,
                       d.source_file, d.source_filename,
                       (1 - (ce.embedding_vector <=> CAST(:queryVector AS vector))) AS score
                FROM chunk_embeddings ce
                JOIN chunks c ON c.id = ce.chunk_id
                JOIN documents d ON d.id = c.doc_id
                WHERE ce.embedding_vector IS NOT NULL
                AND ce.dimensions = :dimensions
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY ce.embedding_vector <=> CAST(:queryVector AS vector)
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkNo", rs.getInt("chunk_no"));
            item.put("title", rs.getString("title"));
            item.put("pageNo", rs.getObject("page_no"));
            item.put("snippet", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("_score", rs.getDouble("score"));
            item.put("_retrieval", "vector");
            return item;
        });
    }

    private List<Map<String, Object>> collectDocumentsByIds(
            List<UUID> docIds,
            List<UUID> includeIds,
            List<UUID> excludeIds
    ) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("docIds", docIds)
                .addValue("limit", Math.min(docIds.size(), Math.max(1, evidenceCandidateLimit())));
        return jdbcTemplate.query("""
                SELECT d.id, d.title, d.doc_type, d.source_file, d.source_filename, d.updated_at
                FROM documents d
                WHERE d.id IN (:docIds)
                AND (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY d.updated_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", rs.getObject("id", UUID.class).toString());
            item.put("title", rs.getString("title"));
            item.put("docType", rs.getString("doc_type"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            item.put("updatedAt", rs.getObject("updated_at"));
            item.put("_score", 1.0d);
            item.put("_retrieval", "evidence_doc");
            return item;
        });
    }

    private List<Map<String, Object>> collectRecentDocuments(List<UUID> includeIds, List<UUID> excludeIds, int limit) {
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                SELECT d.id, d.title, d.doc_type, d.source_file, d.source_filename, d.updated_at
                FROM documents d
                WHERE (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY d.updated_at DESC NULLS LAST, d.created_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> Map.<String, Object>of(
                "docId", rs.getObject("id", UUID.class).toString(),
                "title", rs.getString("title"),
                "docType", rs.getString("doc_type"),
                "sourceFile", rs.getString("source_file"),
                "sourceFilename", rs.getString("source_filename"),
                "updatedAt", rs.getObject("updated_at")
        ));
    }

    private List<Map<String, Object>> collectRecentKnowledgeUnits(List<UUID> includeIds, List<UUID> excludeIds, int limit) {
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                SELECT ku.id, ku.doc_id, ku.chunk_id, ku.title, ku.subject, ku.indicator, ku.content, ku.source_page,
                       d.source_file, d.source_filename
                FROM knowledge_units ku
                JOIN documents d ON d.id = ku.doc_id
                WHERE (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY ku.updated_at DESC NULLS LAST, ku.created_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeUnitId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkId", rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class).toString());
            item.put("title", rs.getString("title"));
            item.put("subject", rs.getString("subject"));
            item.put("indicator", rs.getString("indicator"));
            item.put("content", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourcePage", rs.getObject("source_page"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            return item;
        });
    }

    private List<Map<String, Object>> collectRecentChunks(List<UUID> includeIds, List<UUID> excludeIds, int limit) {
        MapSqlParameterSource params = dataSourceParams(" ", includeIds, excludeIds)
                .addValue("limit", Math.max(1, limit));
        return jdbcTemplate.query("""
                SELECT c.id, c.doc_id, c.chunk_no, c.title, c.page_no, c.content,
                       d.source_file, d.source_filename
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                WHERE (
                    :includeAll = true OR EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:includeIds)
                    )
                )
                AND (
                    :excludeEmpty = true OR NOT EXISTS (
                        SELECT 1
                        FROM source_files sf
                        WHERE sf.file_path = d.source_file
                          AND sf.data_source_id IN (:excludeIds)
                    )
                )
                ORDER BY c.created_at DESC NULLS LAST
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", rs.getObject("id", UUID.class).toString());
            item.put("docId", rs.getObject("doc_id", UUID.class).toString());
            item.put("chunkNo", rs.getInt("chunk_no"));
            item.put("title", rs.getString("title"));
            item.put("pageNo", rs.getObject("page_no"));
            item.put("snippet", abbreviate(rs.getString("content")));
            item.put("_content_raw", rs.getString("content"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("sourceFilename", rs.getString("source_filename"));
            return item;
        });
    }

    private MapSqlParameterSource dataSourceParams(String term, List<UUID> includeIds, List<UUID> excludeIds) {
        String normalizedTerm = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        return new MapSqlParameterSource()
                .addValue("pattern", "%" + normalizedTerm + "%")
                .addValue("termLower", normalizedTerm)
                .addValue("tsQuery", normalizedTerm.isBlank() ? " " : normalizedTerm)
                .addValue("includeAll", includeIds.isEmpty())
                .addValue("includeIds", includeIds.isEmpty() ? List.of(UUID.randomUUID()) : includeIds)
                .addValue("excludeEmpty", excludeIds.isEmpty())
                .addValue("excludeIds", excludeIds.isEmpty() ? List.of(UUID.randomUUID()) : excludeIds);
    }

    private double retrievalScore(Map<String, Object> item) {
        if (item == null) {
            return 1.0d;
        }
        Object raw = item.get("_score");
        double score = 1.0d;
        if (raw instanceof Number number) {
            score = number.doubleValue();
        } else if (raw != null) {
            try {
                score = Double.parseDouble(String.valueOf(raw));
            } catch (Exception ignored) {
                score = 1.0d;
            }
        }
        if (!Double.isFinite(score) || score <= 0.0d) {
            return 0.35d;
        }
        return Math.max(0.35d, Math.min(4.0d, score));
    }

    private String vectorLiteral(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Double value = vector.get(i);
            builder.append(value == null || !Double.isFinite(value) ? 0.0d : value);
        }
        builder.append(']');
        return builder.toString();
    }

    private List<UUID> parseUuids(List<String> values) {
        List<UUID> ids = new ArrayList<>();
        if (values == null) {
            return ids;
        }
        for (String value : values) {
            try {
                ids.add(UUID.fromString(value));
            } catch (Exception ignored) {
                // Ignore invalid identifiers in user-defined scope config.
            }
        }
        return ids;
    }

    private UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() || "null".equalsIgnoreCase(value) || "unknown".equalsIgnoreCase(value)
                    ? null
                    : UUID.fromString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int limit = Math.max(80, appProperties.domainKnowledge().snippetChars());
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private List<String> collectExcludedTerms(DomainDefinition domain, TopicDefinition topic) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addScopeRuleTerms(terms, domain.getScopeRulesJson(), "excludeTerms", "excludedTerms", "excludeTopics", "excludedTopics");
        addExcludedTermsFromSetupHistory(terms, extractSetupHistory(domain.getMetadataJson()));
        addFragments(terms, extractNegativeScopeFromText(domain.getGoal()));
        addFragments(terms, extractNegativeScopeFromText(domain.getDescription()));
        if (topic != null) {
            addScopeRuleTerms(terms, topic.getScopeRulesJson(), "excludeTerms", "excludedTerms", "excludeTopics", "excludedTopics");
            addFragments(terms, extractNegativeScopeFromText(topic.getDescription()));
        }
        return terms.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(24)
                .toList();
    }

    private void addExcludedTermsFromSetupHistory(LinkedHashSet<String> terms, List<String> setupHistory) {
        if (setupHistory == null || setupHistory.isEmpty()) {
            return;
        }
        for (String line : setupHistory) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            if (!(normalized.contains("排除") || normalized.contains("不包含") || normalized.contains("不包括") || normalized.contains("不纳入"))) {
                continue;
            }
            String extracted = extractNegativeScopeFromText(line);
            addFragments(terms, extracted);
        }
    }

    private String extractNegativeScopeFromText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replace('；', '\n')
                .replace(';', '\n')
                .replace('。', '\n')
                .replace('，', '\n')
                .replace(',', '\n');
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\\R+")) {
            String item = line.trim();
            if (item.isBlank()) {
                continue;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            if (lower.contains("排除") || lower.contains("不包含") || lower.contains("不包括") || lower.contains("不纳入")) {
                String extracted = item
                        .replaceAll(".*?(排除|不包含|不包括|不纳入|不纳入范围)", "")
                        .replaceAll("^(：|:|，|,|。|；|;)+", "")
                        .trim();
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(extracted.isBlank() ? item : extracted);
            }
        }
        return builder.toString();
    }

    private void addScopeRuleTerms(LinkedHashSet<String> terms, Map<String, Object> scopeRules, String... keys) {
        if (scopeRules == null || scopeRules.isEmpty()) {
            return;
        }
        for (String key : keys) {
            Object value = scopeRules.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        addTerm(terms, String.valueOf(item));
                        addFragments(terms, String.valueOf(item));
                    }
                }
            } else if (value instanceof String text) {
                addTerm(terms, text);
                addFragments(terms, text);
            }
        }
    }

    private List<Map<String, Object>> filterExcludedItems(List<Map<String, Object>> items, List<String> excludedTerms) {
        return filterExcludedItems(items, excludedTerms, null);
    }

    private List<Map<String, Object>> filterExcludedItems(
            List<Map<String, Object>> items,
            List<String> excludedTerms,
            Map<String, Integer> excludedStats
    ) {
        if (items == null || items.isEmpty() || excludedTerms == null || excludedTerms.isEmpty()) {
            return items == null ? List.of() : items;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!matchesExcludedContent(item, excludedTerms)) {
                filtered.add(item);
            } else if (excludedStats != null) {
                excludedStats.merge("excluded", 1, Integer::sum);
            }
        }
        return filtered;
    }

    private boolean matchesExcludedContent(Map<String, Object> item, List<String> excludedTerms) {
        if (item == null || item.isEmpty() || excludedTerms == null || excludedTerms.isEmpty()) {
            return false;
        }
        StringBuilder haystack = new StringBuilder();
        appendValue(haystack, item.get("title"));
        appendValue(haystack, item.get("subject"));
        appendValue(haystack, item.get("indicator"));
        appendValue(haystack, item.get("content"));
        appendValue(haystack, item.get("_content_raw"));
        appendValue(haystack, item.get("snippet"));
        appendValue(haystack, item.get("sourceFile"));
        appendValue(haystack, item.get("sourceFilename"));
        String normalizedHaystack = normalizeForMatch(haystack.toString());
        if (normalizedHaystack.isBlank()) {
            return false;
        }
        for (String excludedTerm : excludedTerms) {
            if (normalizedHaystack.contains(normalizeForMatch(excludedTerm))) {
                return true;
            }
        }
        return false;
    }

    private void sanitizeInternalFields(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (Map<String, Object> item : items) {
            if (item != null) {
                item.remove("_content_raw");
                item.remove("_score");
                item.remove("_retrieval");
            }
        }
    }

    private boolean matchesExcludedTerm(String value, List<String> excludedTerms) {
        if (value == null || value.isBlank() || excludedTerms == null || excludedTerms.isEmpty()) {
            return false;
        }
        String normalizedValue = normalizeForMatch(value);
        for (String excludedTerm : excludedTerms) {
            String normalizedExcluded = normalizeForMatch(excludedTerm);
            if (!normalizedExcluded.isBlank()
                    && (normalizedValue.contains(normalizedExcluded) || normalizedExcluded.contains(normalizedValue))) {
                return true;
            }
        }
        return false;
    }

    private void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(text);
        }
    }

    private String normalizeForMatch(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> normalizeStructuredContent(
            Map<String, Object> raw,
            DomainDefinition domain,
            TopicDefinition topic,
            EvidenceBundle evidence,
            String summary,
            List<String> keyPoints,
            List<String> excludedTerms
    ) {
        Map<String, Object> source = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
        if (toRecordList(source.get("catalog")).isEmpty() || toRecordList(source.get("cards")).isEmpty()) {
            return buildFallbackStructuredContent(domain, topic, evidence, summary, keyPoints, excludedTerms,
                    "LLM 未返回完整结构化目录，系统按证据生成降级目录");
        }

        LinkedHashSet<String> validEvidenceRefs = new LinkedHashSet<>(evidence.evidenceRefs());
        List<String> warnings = new ArrayList<>();
        int excludedHitCount = 0;

        List<Map<String, Object>> catalog = new ArrayList<>();
        Set<String> catalogIds = new LinkedHashSet<>();
        int catalogIndex = 1;
        for (Map<String, Object> item : toRecordList(source.get("catalog"))) {
            String title = trimToNull(item.get("title"));
            if (title == null) {
                warnings.add("已删除无标题目录节点");
                continue;
            }
            if (matchesExcludedTerm(title, excludedTerms) || matchesExcludedTerm(trimToNull(item.get("summary")), excludedTerms)) {
                excludedHitCount++;
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>(item);
            String id = trimToNull(node.get("id"));
            if (id == null || catalogIds.contains(id)) {
                id = "cat_" + String.format("%03d", catalogIndex);
            }
            catalogIndex++;
            int level = clampInt(node.get("level"), 1, 3, 1);
            List<String> refs = filterEvidenceRefs(toStringList(node.get("evidenceRefs")), validEvidenceRefs);
            Map<String, Object> quality = toRecord(node.get("quality"));
            quality.put("evidenceCount", refs.size());
            quality.put("documentCount", countDocumentsForRefs(refs, evidence));
            if (refs.isEmpty()) {
                quality.put("status", "review_required");
                addWarningList(quality, "目录节点缺少有效证据: " + title);
                warnings.add("目录节点缺少有效证据: " + title);
            } else {
                quality.putIfAbsent("status", "passed");
            }
            node.put("id", id);
            node.put("level", level);
            node.put("title", title.length() > 40 ? title.substring(0, 40) : title);
            node.put("evidenceRefs", refs);
            node.put("quality", quality);
            catalog.add(node);
            catalogIds.add(id);
        }

        if (catalog.isEmpty()) {
            return buildFallbackStructuredContent(domain, topic, evidence, summary, keyPoints, excludedTerms,
                    "LLM 目录经排除项和证据校验后为空，系统生成降级目录");
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        Set<String> cardIds = new LinkedHashSet<>();
        int cardIndex = 1;
        int claimCount = 0;
        int boundClaimCount = 0;
        for (Map<String, Object> item : toRecordList(source.get("cards"))) {
            String title = trimToNull(item.get("title"));
            if (title == null || matchesExcludedTerm(title, excludedTerms) || matchesExcludedTerm(trimToNull(item.get("summary")), excludedTerms)) {
                if (title != null) {
                    excludedHitCount++;
                }
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>(item);
            String id = trimToNull(card.get("id"));
            if (id == null || cardIds.contains(id)) {
                id = "card_" + String.format("%03d", cardIndex);
            }
            cardIndex++;
            String catalogId = trimToNull(card.get("catalogId"));
            if (catalogId == null || !catalogIds.contains(catalogId)) {
                catalogId = String.valueOf(catalog.get(0).get("id"));
                warnings.add("卡片已挂接到默认目录: " + title);
            }
            List<Map<String, Object>> claims = new ArrayList<>();
            for (Map<String, Object> claimItem : toRecordList(card.get("claims"))) {
                String text = trimToNull(claimItem.get("text"));
                if (text == null || matchesExcludedTerm(text, excludedTerms)) {
                    if (text != null) {
                        excludedHitCount++;
                    }
                    continue;
                }
                List<String> refs = filterEvidenceRefs(toStringList(claimItem.get("evidenceRefs")), validEvidenceRefs);
                claimCount++;
                if (refs.isEmpty()) {
                    warnings.add("已删除无有效证据的结论: " + abbreviate(text));
                    continue;
                }
                boundClaimCount++;
                Map<String, Object> claim = new LinkedHashMap<>(claimItem);
                claim.put("text", text);
                claim.put("evidenceRefs", refs);
                claim.putIfAbsent("confidence", "medium");
                claims.add(claim);
            }
            if (claims.isEmpty()) {
                warnings.add("已删除无有效结论的知识卡片: " + title);
                continue;
            }
            card.put("id", id);
            card.put("catalogId", catalogId);
            card.put("type", normalizeCardType(trimToNull(card.get("type"))));
            card.put("title", title.length() > 60 ? title.substring(0, 60) : title);
            card.put("claims", claims);
            cards.add(card);
            cardIds.add(id);
        }

        if (cards.isEmpty()) {
            return buildFallbackStructuredContent(domain, topic, evidence, summary, keyPoints, excludedTerms,
                    "LLM 知识卡片经证据校验后为空，系统生成降级卡片");
        }

        List<Map<String, Object>> evidenceBindings = buildEvidenceBindings(cards);
        Map<String, Object> validation = buildValidation(
                catalog,
                cards,
                evidenceBindings,
                evidence,
                warnings,
                excludedHitCount,
                claimCount,
                boundClaimCount
        );

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("version", trimToNull(source.get("version")) == null ? "v1" : trimToNull(source.get("version")));
        structured.put("catalog", catalog);
        structured.put("cards", cards);
        structured.put("evidenceBindings", evidenceBindings);
        structured.put("validation", validation);
        structured.put("topicSubgraph", evidence.topicSubgraph());
        structured.put("evidencePack", compactEvidencePack(evidence.evidencePack()));
        structured.put("readableSummary", buildReadableSummary(domain, topic, summary, keyPoints, validation));
        structured.put("agentView", buildAgentView(domain, topic, catalog, cards, validation, evidence));
        return structured;
    }

    private Map<String, Object> buildFallbackStructuredContent(
            DomainDefinition domain,
            TopicDefinition topic,
            EvidenceBundle evidence,
            String summary,
            List<String> keyPoints,
            List<String> excludedTerms,
            String warning
    ) {
        LinkedHashSet<String> refs = new LinkedHashSet<>(evidence.evidenceRefs());
        List<String> usableRefs = refs.stream().limit(24).toList();
        List<Map<String, Object>> catalog = new ArrayList<>();
        Map<String, List<String>> groupedRefs = new LinkedHashMap<>();
        for (Map<String, Object> item : evidence.knowledgeUnits()) {
            String title = firstNonBlank(trimToNull(item.get("subject")), trimToNull(item.get("indicator")), trimToNull(item.get("title")));
            if (title != null && !matchesExcludedTerm(title, excludedTerms)) {
                groupedRefs.computeIfAbsent(title, ignored -> new ArrayList<>())
                        .add("knowledge_unit:" + item.get("knowledgeUnitId"));
            }
            if (groupedRefs.size() >= 8) {
                break;
            }
        }
        if (groupedRefs.isEmpty()) {
            groupedRefs.put(topic == null ? domain.getName() : topic.getName(), new ArrayList<>(usableRefs));
        }
        int index = 1;
        for (Map.Entry<String, List<String>> entry : groupedRefs.entrySet()) {
            List<String> nodeRefs = entry.getValue().stream()
                    .filter(refs::contains)
                    .limit(12)
                    .toList();
            if (nodeRefs.isEmpty()) {
                nodeRefs = usableRefs.stream().limit(6).toList();
            }
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("status", nodeRefs.isEmpty() ? "review_required" : "passed");
            quality.put("evidenceCount", nodeRefs.size());
            quality.put("documentCount", countDocumentsForRefs(nodeRefs, evidence));
            quality.put("warnings", nodeRefs.isEmpty() ? List.of("目录节点缺少有效证据") : List.of());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "cat_" + String.format("%03d", index));
            node.put("parentId", null);
            node.put("level", 1);
            node.put("title", limitText(entry.getKey(), 40));
            node.put("summary", "系统根据已召回证据生成的降级目录节点。");
            node.put("keywords", List.of(entry.getKey()));
            node.put("evidenceRefs", nodeRefs);
            node.put("quality", quality);
            catalog.add(node);
            index++;
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> points = keyPoints == null || keyPoints.isEmpty() ? List.of(summary) : keyPoints;
        int cardIndex = 1;
        for (String point : points.stream().filter(item -> item != null && !item.isBlank()).limit(8).toList()) {
            if (matchesExcludedTerm(point, excludedTerms)) {
                continue;
            }
            String catalogId = String.valueOf(catalog.get(Math.min(catalog.size() - 1, cardIndex - 1)).get("id"));
            List<String> claimRefs = usableRefs.stream().skip(cardIndex - 1L).limit(3).toList();
            if (claimRefs.isEmpty()) {
                claimRefs = usableRefs.stream().limit(3).toList();
            }
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("text", point);
            claim.put("confidence", "medium");
            claim.put("evidenceRefs", claimRefs);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", "card_" + String.format("%03d", cardIndex));
            card.put("catalogId", catalogId);
            card.put("type", "concept");
            card.put("title", limitText(point, 50));
            card.put("summary", point);
            card.put("claims", List.of(claim));
            cards.add(card);
            cardIndex++;
        }

        List<Map<String, Object>> bindings = buildEvidenceBindings(cards);
        List<String> warnings = new ArrayList<>();
        warnings.add(warning);
        if (usableRefs.isEmpty()) {
            warnings.add("没有可绑定证据，知识包不可作为 ready 使用");
        }
        Map<String, Object> validation = buildValidation(
                catalog,
                cards,
                bindings,
                evidence,
                warnings,
                0,
                cards.size(),
                usableRefs.isEmpty() ? 0 : cards.size()
        );
        validation.put("status", usableRefs.isEmpty() ? "failed" : "review_required");

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("version", "v1");
        structured.put("catalog", catalog);
        structured.put("cards", cards);
        structured.put("evidenceBindings", bindings);
        structured.put("validation", validation);
        structured.put("topicSubgraph", evidence.topicSubgraph());
        structured.put("evidencePack", compactEvidencePack(evidence.evidencePack()));
        structured.put("readableSummary", buildReadableSummary(domain, topic, summary, keyPoints, validation));
        structured.put("agentView", buildAgentView(domain, topic, catalog, cards, validation, evidence));
        return structured;
    }

    private Map<String, Object> buildValidation(
            List<Map<String, Object>> catalog,
            List<Map<String, Object>> cards,
            List<Map<String, Object>> evidenceBindings,
            EvidenceBundle evidence,
            List<String> warnings,
            int excludedHitCount,
            int claimCount,
            int boundClaimCount
    ) {
        List<String> mergedWarnings = new ArrayList<>(warnings == null ? List.of() : warnings);
        if (evidence != null && evidence.warnings() != null) {
            mergedWarnings.addAll(evidence.warnings());
        }
        int maxDepth = catalog.stream()
                .mapToInt(item -> clampInt(item.get("level"), 1, 3, 1))
                .max()
                .orElse(0);
        int level1Count = (int) catalog.stream()
                .filter(item -> clampInt(item.get("level"), 1, 3, 1) == 1)
                .count();
        double boundRatio = claimCount <= 0 ? 0.0 : (double) boundClaimCount / (double) claimCount;
        long lowEvidenceNodes = catalog.stream()
                .filter(item -> toStringList(toRecord(item.get("quality")).get("warnings")).size() > 0)
                .count();
        String status;
        if (catalog.isEmpty() || cards.isEmpty() || evidence.evidenceRefs().isEmpty() || boundRatio < 0.6) {
            status = "failed";
        } else if (mergedWarnings.isEmpty()
                && excludedHitCount == 0
                && boundRatio >= 0.9
                && maxDepth <= 3
                && level1Count <= 10) {
            status = "ready";
        } else {
            status = "review_required";
        }

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("status", status);
        validation.put("catalogNodeCount", catalog.size());
        validation.put("level1NodeCount", level1Count);
        validation.put("maxDepth", maxDepth);
        validation.put("cardCount", cards.size());
        validation.put("claimCount", claimCount);
        validation.put("boundClaimCount", boundClaimCount);
        validation.put("boundClaimRatio", Math.round(boundRatio * 100.0) / 100.0);
        validation.put("evidenceCount", evidence.evidenceRefs().size());
        validation.put("documentCount", evidence.documents().size());
        validation.put("graphFactCount", evidence.graphFacts().size());
        validation.put("subgraphEntityCount", clampInt(toRecord(evidence.topicSubgraph().get("stats")).get("entityCount"), 0, 100000, 0));
        validation.put("subgraphRelationCount", clampInt(toRecord(evidence.topicSubgraph().get("stats")).get("relationCount"), 0, 100000, 0));
        validation.put("lowEvidenceNodeCount", lowEvidenceNodes);
        validation.put("excludedHitCount", excludedHitCount);
        validation.put("evidenceBindingCount", evidenceBindings.size());
        validation.put("warnings", mergedWarnings.stream().distinct().limit(30).toList());
        return validation;
    }

    private List<Map<String, Object>> buildEvidenceBindings(List<Map<String, Object>> cards) {
        Map<String, Map<String, Object>> byRef = new LinkedHashMap<>();
        for (Map<String, Object> card : cards) {
            String cardId = trimToNull(card.get("id"));
            String catalogId = trimToNull(card.get("catalogId"));
            for (Map<String, Object> claim : toRecordList(card.get("claims"))) {
                String text = trimToNull(claim.get("text"));
                for (String ref : toStringList(claim.get("evidenceRefs"))) {
                    Map<String, Object> binding = byRef.computeIfAbsent(ref, ignored -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("evidenceRef", ref);
                        item.put("catalogIds", new ArrayList<String>());
                        item.put("cardIds", new ArrayList<String>());
                        item.put("claimTexts", new ArrayList<String>());
                        return item;
                    });
                    addUniqueString(binding, "catalogIds", catalogId);
                    addUniqueString(binding, "cardIds", cardId);
                    addUniqueString(binding, "claimTexts", text);
                }
            }
        }
        return new ArrayList<>(byRef.values());
    }

    private Map<String, Object> buildAgentView(
            DomainDefinition domain,
            TopicDefinition topic,
            List<Map<String, Object>> catalog,
            List<Map<String, Object>> cards,
            Map<String, Object> validation,
            EvidenceBundle evidence
    ) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("domainName", domain.getName());
        view.put("topicName", topic == null ? null : topic.getName());
        view.put("catalogTitles", catalog.stream().map(item -> item.get("title")).limit(20).toList());
        view.put("topCards", cards.stream().limit(12).toList());
        view.put("topicSubgraphStats", toRecord(evidence.topicSubgraph().get("stats")));
        view.put("evidencePackStats", toRecord(evidence.evidencePack().get("stats")));
        view.put("graphEdges", toRecordList(evidence.topicSubgraph().get("edges")).stream().limit(24).toList());
        view.put("constraints", Map.of(
                "validationStatus", validation.get("status"),
                "mustUseEvidenceRefs", true,
                "subgraphIsSnapshot", true
        ));
        return view;
    }

    private List<Map<String, Object>> selectGraphFactsForEvidence(
            List<Map<String, Object>> graphFacts,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks
    ) {
        if (graphFacts == null || graphFacts.isEmpty()) {
            return List.of();
        }
        Set<String> docIds = new LinkedHashSet<>();
        Set<String> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> item : documents == null ? List.<Map<String, Object>>of() : documents) {
            addIfPresent(docIds, item.get("docId"));
        }
        for (Map<String, Object> item : knowledgeUnits == null ? List.<Map<String, Object>>of() : knowledgeUnits) {
            addIfPresent(docIds, item.get("docId"));
            addIfPresent(chunkIds, item.get("chunkId"));
        }
        for (Map<String, Object> item : chunks == null ? List.<Map<String, Object>>of() : chunks) {
            addIfPresent(docIds, item.get("docId"));
            addIfPresent(chunkIds, item.get("chunkId"));
        }
        List<Map<String, Object>> selected = graphFacts.stream()
                .filter(fact -> {
                    String chunkId = trimToNull(fact.get("chunkId"));
                    String docId = trimToNull(fact.get("docId"));
                    return (chunkId != null && chunkIds.contains(chunkId))
                            || (docId != null && docIds.contains(docId));
                })
                .limit(80)
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        return graphFacts.stream().limit(40).toList();
    }

    private void addIfPresent(Set<String> values, Object raw) {
        String value = trimToNull(raw);
        if (value != null) {
            values.add(value);
        }
    }

    private Map<String, Object> buildTopicSubgraph(
            RetrievalPlan retrievalPlan,
            List<Map<String, Object>> graphFacts,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks
    ) {
        List<Map<String, Object>> facts = graphFacts == null ? List.of() : graphFacts.stream().limit(80).toList();
        LinkedHashMap<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<String> dimensions = retrievalPlan == null || retrievalPlan.dimensions() == null
                ? List.of()
                : retrievalPlan.dimensions().stream().map(RetrievalDimension::name).toList();
        Map<String, Integer> dimensionFactCounts = new LinkedHashMap<>();
        for (Map<String, Object> fact : facts) {
            String subject = firstNonBlank(trimToNull(fact.get("subject")), "未知主体");
            String object = firstNonBlank(trimToNull(fact.get("object")), "未知客体");
            String subjectType = firstNonBlank(trimToNull(fact.get("subjectType")), "Entity");
            String objectType = firstNonBlank(trimToNull(fact.get("objectType")), "Entity");
            String subjectId = graphNodeId(subjectType, subject);
            String objectId = graphNodeId(objectType, object);
            addGraphNode(nodes, subjectId, subject, subjectType, fact);
            addGraphNode(nodes, objectId, object, objectType, fact);
            String dimension = bestGroupName(graphFactText(fact), dimensions);
            dimensionFactCounts.merge(dimension, 1, Integer::sum);

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", firstNonBlank(trimToNull(fact.get("factKey")), subjectId + "->" + objectId + ":" + edges.size()));
            edge.put("source", subjectId);
            edge.put("target", objectId);
            edge.put("subject", subject);
            edge.put("object", object);
            edge.put("relationType", firstNonBlank(trimToNull(fact.get("relationType")), "related_to"));
            edge.put("statement", limitText(trimToNull(fact.get("statement")), 180));
            edge.put("confidence", fact.get("confidence"));
            edge.put("dimension", dimension);
            edge.put("validFrom", trimToNull(fact.get("validFrom")));
            edge.put("validTo", trimToNull(fact.get("validTo")));
            edge.put("evidenceRef", graphFactEvidenceRef(fact));
            edge.put("docId", trimToNull(fact.get("docId")));
            edge.put("chunkId", trimToNull(fact.get("chunkId")));
            edge.put("sourceSpan", limitText(trimToNull(fact.get("sourceSpan")), 160));
            edges.add(edge);
        }

        List<Map<String, Object>> dimensionItems = new ArrayList<>();
        for (String dimension : dimensions.isEmpty() ? List.of("综合证据") : dimensions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", dimension);
            item.put("factCount", dimensionFactCounts.getOrDefault(dimension, 0));
            item.put("status", dimensionFactCounts.getOrDefault(dimension, 0) > 0 ? "covered" : "no_graph_fact");
            dimensionItems.add(item);
        }
        if (!dimensionFactCounts.containsKey("综合证据") && dimensionItems.stream().noneMatch(item -> "综合证据".equals(item.get("name")))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", "综合证据");
            item.put("factCount", dimensionFactCounts.getOrDefault("综合证据", 0));
            item.put("status", dimensionFactCounts.getOrDefault("综合证据", 0) > 0 ? "covered" : "no_graph_fact");
            dimensionItems.add(item);
        }

        Set<String> docIds = new LinkedHashSet<>();
        Set<String> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> fact : facts) {
            addIfPresent(docIds, fact.get("docId"));
            addIfPresent(chunkIds, fact.get("chunkId"));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entityCount", nodes.size());
        stats.put("relationCount", edges.size());
        stats.put("factCount", facts.size());
        stats.put("documentCount", docIds.size());
        stats.put("chunkCount", chunkIds.size());
        stats.put("retrievedDocumentCount", documents == null ? 0 : documents.size());
        stats.put("retrievedKnowledgeUnitCount", knowledgeUnits == null ? 0 : knowledgeUnits.size());
        stats.put("retrievedChunkCount", chunks == null ? 0 : chunks.size());

        Map<String, Object> subgraph = new LinkedHashMap<>();
        subgraph.put("version", "v1");
        subgraph.put("kind", "topic_subgraph_snapshot");
        subgraph.put("source", "knowledge_graph_search_facts");
        subgraph.put("stats", stats);
        subgraph.put("dimensions", dimensionItems);
        subgraph.put("nodes", new ArrayList<>(nodes.values()));
        subgraph.put("edges", edges);
        subgraph.put("warnings", facts.isEmpty()
                ? List.of("未召回可绑定的图谱事实，知识包将主要依赖索引证据")
                : List.of());
        return subgraph;
    }

    private String graphNodeId(String type, String name) {
        String normalizedType = firstNonBlank(trimToNull(type), "entity").toLowerCase(Locale.ROOT);
        String normalizedName = normalizeForMatch(name);
        if (normalizedName.isBlank()) {
            normalizedName = UUID.nameUUIDFromBytes(String.valueOf(name).getBytes()).toString();
        }
        return normalizedType + ":" + normalizedName;
    }

    private void addGraphNode(LinkedHashMap<String, Map<String, Object>> nodes, String id, String label, String type, Map<String, Object> fact) {
        Map<String, Object> node = nodes.computeIfAbsent(id, ignored -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("label", label);
            item.put("type", type);
            item.put("factCount", 0);
            item.put("evidenceRefs", new ArrayList<String>());
            return item;
        });
        node.put("factCount", clampInt(node.get("factCount"), 0, 100000, 0) + 1);
        addUniqueString(node, "evidenceRefs", graphFactEvidenceRef(fact));
    }

    private String graphFactText(Map<String, Object> fact) {
        StringBuilder builder = new StringBuilder();
        appendValue(builder, fact.get("subject"));
        appendValue(builder, fact.get("subjectType"));
        appendValue(builder, fact.get("relationType"));
        appendValue(builder, fact.get("object"));
        appendValue(builder, fact.get("objectType"));
        appendValue(builder, fact.get("statement"));
        appendValue(builder, fact.get("sourceSpan"));
        return builder.toString();
    }

    private String graphFactEvidenceRef(Map<String, Object> fact) {
        String chunkId = trimToNull(fact.get("chunkId"));
        if (chunkId != null) {
            return "chunk:" + chunkId;
        }
        String knowledgeUnitId = trimToNull(fact.get("knowledgeUnitId"));
        if (knowledgeUnitId != null) {
            return "knowledge_unit:" + knowledgeUnitId;
        }
        String docId = trimToNull(fact.get("docId"));
        return docId == null ? "" : "document:" + docId;
    }

    private Map<String, Object> buildEvidencePack(
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> graphFacts,
            List<String> evidenceRefs,
            List<String> warnings
    ) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documentCount", documents == null ? 0 : documents.size());
        stats.put("knowledgeUnitCount", knowledgeUnits == null ? 0 : knowledgeUnits.size());
        stats.put("chunkCount", chunks == null ? 0 : chunks.size());
        stats.put("graphFactCount", graphFacts == null ? 0 : graphFacts.size());
        stats.put("evidenceRefCount", evidenceRefs == null ? 0 : evidenceRefs.size());
        stats.put("warningCount", warnings == null ? 0 : warnings.size());

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("version", "v1");
        pack.put("kind", "evidence_pack");
        pack.put("stats", stats);
        pack.put("evidenceRefs", evidenceRefs == null ? List.of() : evidenceRefs);
        pack.put("documents", documents == null ? List.of() : documents.stream().map(this::slimDocument).toList());
        pack.put("knowledgeUnits", knowledgeUnits == null ? List.of() : knowledgeUnits.stream().map(this::slimKnowledgeUnit).toList());
        pack.put("chunks", chunks == null ? List.of() : chunks.stream().map(this::slimChunk).toList());
        pack.put("graphFacts", graphFacts == null ? List.of() : graphFacts.stream().map(this::slimGraphFact).toList());
        pack.put("warnings", warnings == null ? List.of() : warnings);
        return pack;
    }

    private Map<String, Object> compactEvidencePack(Map<String, Object> evidencePack) {
        Map<String, Object> source = evidencePack == null ? Map.of() : evidencePack;
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("version", source.getOrDefault("version", "v1"));
        compact.put("kind", source.getOrDefault("kind", "evidence_pack"));
        compact.put("stats", toRecord(source.get("stats")));
        compact.put("evidenceRefs", toStringList(source.get("evidenceRefs")).stream().limit(80).toList());
        compact.put("documents", toRecordList(source.get("documents")).stream().limit(20).toList());
        compact.put("knowledgeUnits", toRecordList(source.get("knowledgeUnits")).stream().limit(40).toList());
        compact.put("chunks", toRecordList(source.get("chunks")).stream().limit(40).toList());
        compact.put("graphFacts", toRecordList(source.get("graphFacts")).stream().limit(60).toList());
        compact.put("warnings", toStringList(source.get("warnings")).stream().limit(20).toList());
        return compact;
    }

    private Map<String, Object> buildReadableSummary(
            DomainDefinition domain,
            TopicDefinition topic,
            String summary,
            List<String> keyPoints,
            Map<String, Object> validation
    ) {
        Map<String, Object> readable = new LinkedHashMap<>();
        readable.put("title", buildTitle(domain, topic));
        readable.put("summary", summary == null ? "" : summary);
        readable.put("keyPoints", keyPoints == null ? List.of() : keyPoints);
        readable.put("validationStatus", validation == null ? "review_required" : validation.getOrDefault("status", "review_required"));
        readable.put("usage", "面向智能体和用户阅读的主题知识总结；事实依据请回溯 evidencePack 和 topicSubgraph");
        return readable;
    }

    private List<Map<String, Object>> buildEvidenceGroups(
            DomainDefinition domain,
            TopicDefinition topic,
            EvidenceBundle evidence,
            List<String> excludedTerms
    ) {
        List<String> dimensions = extractKnowledgeDimensions(domain, topic);
        LinkedHashMap<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (String dimension : dimensions.stream().limit(6).toList()) {
            groups.put(dimension, newEvidenceGroup(dimension));
        }
        groups.putIfAbsent("综合证据", newEvidenceGroup("综合证据"));

        for (Map<String, Object> item : evidence.knowledgeUnits()) {
            String text = evidenceText(item);
            if (matchesExcludedTerm(text, excludedTerms)) {
                continue;
            }
            String groupName = bestGroupName(text, dimensions);
            addKnowledgeUnitToGroup(groups.computeIfAbsent(groupName, this::newEvidenceGroup), item);
        }
        for (Map<String, Object> item : evidence.chunks()) {
            String text = evidenceText(item);
            if (matchesExcludedTerm(text, excludedTerms)) {
                continue;
            }
            String groupName = bestGroupName(text, dimensions);
            addChunkToGroup(groups.computeIfAbsent(groupName, this::newEvidenceGroup), item);
        }
        for (Map<String, Object> item : evidence.graphFacts()) {
            String text = graphFactText(item);
            if (matchesExcludedTerm(text, excludedTerms)) {
                continue;
            }
            String groupName = bestGroupName(text, dimensions);
            addGraphFactToGroup(groups.computeIfAbsent(groupName, this::newEvidenceGroup), item);
        }
        for (Map<String, Object> group : groups.values()) {
            addRelatedDocuments(group, evidence.documents());
        }
        return groups.values().stream()
                .filter(group -> !toStringList(group.get("evidenceRefs")).isEmpty())
                .limit(6)
                .toList();
    }

    private List<String> extractKnowledgeDimensions(DomainDefinition domain, TopicDefinition topic) {
        List<String> fromScope = toStringList(domain.getScopeRulesJson() == null ? null : domain.getScopeRulesJson().get("knowledgeDimensions"));
        if (!fromScope.isEmpty()) {
            return fromScope;
        }
        List<String> fromMetadata = toStringList(domain.getMetadataJson() == null ? null : domain.getMetadataJson().get("setupAssistantCoveredDimensions"));
        if (!fromMetadata.isEmpty()) {
            return fromMetadata;
        }
        if (topic != null && topic.getName() != null && !topic.getName().isBlank()) {
            return List.of(topic.getName(), "制度政策", "业务流程", "主体关系", "历史演进", "评价指标");
        }
        return List.of("制度政策", "业务流程", "主体关系", "历史演进", "评价指标", "风险约束");
    }

    private Map<String, Object> newEvidenceGroup(String name) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", name);
        group.put("purpose", "围绕“" + name + "”形成可回溯的领域知识摘要");
        group.put("evidenceRefs", new ArrayList<String>());
        group.put("documents", new ArrayList<Map<String, Object>>());
        group.put("knowledgeUnits", new ArrayList<Map<String, Object>>());
        group.put("chunks", new ArrayList<Map<String, Object>>());
        group.put("graphFacts", new ArrayList<Map<String, Object>>());
        return group;
    }

    private String bestGroupName(String text, List<String> dimensions) {
        String normalized = normalizeForMatch(text);
        String best = "综合证据";
        int bestScore = 0;
        for (String dimension : dimensions) {
            int score = groupScore(normalized, dimension);
            if (score > bestScore) {
                bestScore = score;
                best = dimension;
            }
        }
        return bestScore <= 0 ? "综合证据" : best;
    }

    private int groupScore(String normalizedText, String dimension) {
        if (normalizedText == null || normalizedText.isBlank() || dimension == null || dimension.isBlank()) {
            return 0;
        }
        int score = normalizedText.contains(normalizeForMatch(dimension)) ? 4 : 0;
        for (String keyword : dimensionKeywords(dimension)) {
            if (normalizedText.contains(normalizeForMatch(keyword))) {
                score += 2;
            }
        }
        return score;
    }

    private List<String> dimensionKeywords(String dimension) {
        String normalized = normalizeForMatch(dimension);
        if (normalized.contains("制度") || normalized.contains("政策") || normalized.contains("法规")) {
            return List.of("制度", "政策", "办法", "规定", "规则", "标准", "条款");
        }
        if (normalized.contains("流程") || normalized.contains("业务")) {
            return List.of("流程", "审核", "申请", "提交", "办理", "环节", "状态");
        }
        if (normalized.contains("主体") || normalized.contains("关系") || normalized.contains("角色")) {
            return List.of("学生", "导师", "学院", "学校", "部门", "角色", "权限");
        }
        if (normalized.contains("历史") || normalized.contains("演进")) {
            return List.of("历史", "演进", "发展", "建设", "背景", "阶段", "时间");
        }
        if (normalized.contains("评价") || normalized.contains("指标")) {
            return List.of("指标", "评价", "评估", "质量", "考核", "参数", "性能");
        }
        if (normalized.contains("风险") || normalized.contains("约束") || normalized.contains("安全")) {
            return List.of("风险", "安全", "合规", "约束", "限制", "异常", "控制");
        }
        return List.of(dimension);
    }

    @SuppressWarnings("unchecked")
    private void addKnowledgeUnitToGroup(Map<String, Object> group, Map<String, Object> item) {
        List<String> refs = (List<String>) group.get("evidenceRefs");
        String ref = "knowledge_unit:" + item.get("knowledgeUnitId");
        if (refs.contains(ref)) {
            return;
        }
        refs.add(ref);
        List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("knowledgeUnits");
        if (items.size() < 8) {
            items.add(slimKnowledgeUnit(item));
        }
    }

    @SuppressWarnings("unchecked")
    private void addChunkToGroup(Map<String, Object> group, Map<String, Object> item) {
        List<String> refs = (List<String>) group.get("evidenceRefs");
        String ref = "chunk:" + item.get("chunkId");
        if (refs.contains(ref)) {
            return;
        }
        refs.add(ref);
        List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("chunks");
        if (items.size() < 8) {
            items.add(slimChunk(item));
        }
    }

    @SuppressWarnings("unchecked")
    private void addGraphFactToGroup(Map<String, Object> group, Map<String, Object> item) {
        String ref = graphFactEvidenceRef(item);
        if (ref == null || ref.isBlank()) {
            return;
        }
        List<String> refs = (List<String>) group.get("evidenceRefs");
        if (!refs.contains(ref)) {
            refs.add(ref);
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("graphFacts");
        if (items.size() < 8) {
            items.add(slimGraphFact(item));
        }
    }

    @SuppressWarnings("unchecked")
    private void addRelatedDocuments(Map<String, Object> group, List<Map<String, Object>> documents) {
        List<Map<String, Object>> groupDocs = (List<Map<String, Object>>) group.get("documents");
        Set<String> docIds = new LinkedHashSet<>();
        for (Map<String, Object> item : toRecordList(group.get("knowledgeUnits"))) {
            docIds.add(String.valueOf(item.get("docId")));
        }
        for (Map<String, Object> item : toRecordList(group.get("chunks"))) {
            docIds.add(String.valueOf(item.get("docId")));
        }
        for (Map<String, Object> item : toRecordList(group.get("graphFacts"))) {
            docIds.add(String.valueOf(item.get("docId")));
        }
        for (Map<String, Object> document : documents) {
            if (groupDocs.size() >= 4) {
                return;
            }
            if (docIds.contains(String.valueOf(document.get("docId")))) {
                groupDocs.add(slimDocument(document));
            }
        }
    }

    private Map<String, Object> slimDocument(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceRef", "document:" + item.get("docId"));
        result.put("docId", item.get("docId"));
        result.put("title", limitText(trimToNull(item.get("title")), 80));
        result.put("sourceFilename", item.get("sourceFilename"));
        return result;
    }

    private Map<String, Object> slimKnowledgeUnit(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceRef", "knowledge_unit:" + item.get("knowledgeUnitId"));
        result.put("knowledgeUnitId", item.get("knowledgeUnitId"));
        result.put("docId", item.get("docId"));
        result.put("title", limitText(trimToNull(item.get("title")), 80));
        result.put("subject", limitText(trimToNull(item.get("subject")), 60));
        result.put("indicator", limitText(trimToNull(item.get("indicator")), 60));
        result.put("contentPreview", limitText(firstNonBlank(trimToNull(item.get("content")), trimToNull(item.get("_content_raw"))), 160));
        return result;
    }

    private Map<String, Object> slimChunk(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceRef", "chunk:" + item.get("chunkId"));
        result.put("chunkId", item.get("chunkId"));
        result.put("docId", item.get("docId"));
        result.put("title", limitText(trimToNull(item.get("title")), 80));
        result.put("pageNo", item.get("pageNo"));
        result.put("snippetPreview", limitText(trimToNull(item.get("snippet")), 160));
        if (trimToNull(item.get("graphFactKey")) != null) {
            result.put("graphFactKey", item.get("graphFactKey"));
            result.put("graphRelationType", item.get("graphRelationType"));
        }
        return result;
    }

    private Map<String, Object> slimGraphFact(Map<String, Object> fact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factKey", fact.get("factKey"));
        result.put("subject", limitText(trimToNull(fact.get("subject")), 80));
        result.put("subjectType", trimToNull(fact.get("subjectType")));
        result.put("relationType", firstNonBlank(trimToNull(fact.get("relationType")), "related_to"));
        result.put("object", limitText(trimToNull(fact.get("object")), 80));
        result.put("objectType", trimToNull(fact.get("objectType")));
        result.put("statement", limitText(trimToNull(fact.get("statement")), 180));
        result.put("confidence", fact.get("confidence"));
        result.put("validFrom", trimToNull(fact.get("validFrom")));
        result.put("validTo", trimToNull(fact.get("validTo")));
        result.put("docId", trimToNull(fact.get("docId")));
        result.put("chunkId", trimToNull(fact.get("chunkId")));
        result.put("knowledgeUnitId", trimToNull(fact.get("knowledgeUnitId")));
        result.put("evidenceRef", graphFactEvidenceRef(fact));
        result.put("sourceSpan", limitText(trimToNull(fact.get("sourceSpan")), 160));
        result.put("tokenScore", fact.get("tokenScore"));
        return result;
    }

    private String evidenceText(Map<String, Object> item) {
        StringBuilder builder = new StringBuilder();
        appendValue(builder, item.get("title"));
        appendValue(builder, item.get("subject"));
        appendValue(builder, item.get("indicator"));
        appendValue(builder, item.get("content"));
        appendValue(builder, item.get("_content_raw"));
        appendValue(builder, item.get("snippet"));
        appendValue(builder, item.get("sourceFilename"));
        return builder.toString();
    }

    private List<String> filterEvidenceRefs(List<String> refs, Set<String> validRefs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream()
                .map(String::trim)
                .filter(ref -> !ref.isBlank())
                .filter(validRefs::contains)
                .distinct()
                .limit(20)
                .toList();
    }

    private List<String> filterKnownRefs(List<String> refs, Set<String> validRefs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream()
                .map(String::trim)
                .filter(ref -> !ref.isBlank())
                .filter(validRefs::contains)
                .distinct()
                .limit(24)
                .toList();
    }

    private int countDocumentsForRefs(List<String> refs, EvidenceBundle evidence) {
        Set<String> docIds = new HashSet<>();
        for (String ref : refs) {
            if (ref == null) {
                continue;
            }
            if (ref.startsWith("document:")) {
                docIds.add(ref.substring("document:".length()));
            } else if (ref.startsWith("knowledge_unit:")) {
                String id = ref.substring("knowledge_unit:".length());
                evidence.knowledgeUnits().stream()
                        .filter(item -> id.equals(String.valueOf(item.get("knowledgeUnitId"))))
                        .map(item -> String.valueOf(item.get("docId")))
                        .findFirst()
                        .ifPresent(docIds::add);
            } else if (ref.startsWith("chunk:")) {
                String id = ref.substring("chunk:".length());
                evidence.chunks().stream()
                        .filter(item -> id.equals(String.valueOf(item.get("chunkId"))))
                        .map(item -> String.valueOf(item.get("docId")))
                        .findFirst()
                        .ifPresent(docIds::add);
            }
        }
        return docIds.size();
    }

    private List<Map<String, Object>> toRecordList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> record = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        record.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                records.add(record);
            }
        }
        return records;
    }

    private Map<String, Object> toRecord(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> record = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                record.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return record;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private void addWarningList(Map<String, Object> target, String warning) {
        List<String> warnings = new ArrayList<>(toStringList(target.get("warnings")));
        warnings.add(warning);
        target.put("warnings", warnings.stream().distinct().toList());
    }

    @SuppressWarnings("unchecked")
    private void addUniqueString(Map<String, Object> target, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Object raw = target.get(key);
        List<String> items = raw instanceof List<?> list
                ? new ArrayList<>(list.stream().map(String::valueOf).toList())
                : new ArrayList<>();
        if (!items.contains(value)) {
            items.add(value);
        }
        target.put(key, items);
    }

    private int clampInt(Object value, int min, int max, int fallback) {
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (Exception ex) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private String normalizeCardType(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        return List.of("concept", "rule", "process", "event", "timeline", "comparison", "risk", "gap").contains(normalized)
                ? normalized
                : "concept";
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String limitText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private RefinementOutcome refine(
            DomainRefineJob job,
            DomainDefinition domain,
            TopicDefinition topic,
            EvidenceBundle evidence,
            String draftSummary,
            List<String> draftKeyPoints,
            String draftMarkdown
    ) {
        try {
            List<String> excludedTerms = collectExcludedTerms(domain, topic);
            Map<String, Object> domainBuildSpec = buildDomainBuildSpec(domain, topic, evidence, excludedTerms);
            List<Map<String, Object>> evidenceGroups = buildEvidenceGroups(domain, topic, evidence, excludedTerms);
            List<Map<String, Object>> refinedGroups = refineEvidenceGroups(job, domain, domainBuildSpec, excludedTerms, evidenceGroups);
            DomainKnowledgeRefinementService.RefinedResult refined = domainKnowledgeRefinementService.synthesize(
                    buildTitle(domain, topic),
                    domain.getGoal(),
                    topic == null ? domain.getDescription() : topic.getDescription(),
                    draftKeyPoints,
                    domainBuildSpec,
                    excludedTerms,
                    evidence.terms(),
                    refinedGroups,
                    evidence.documents(),
                    evidence.knowledgeUnits(),
                    evidence.chunks()
            );
            String summary = refined.summary() == null || refined.summary().isBlank() ? draftSummary : refined.summary();
            List<String> keyPoints = refined.keyPoints() == null || refined.keyPoints().isEmpty() ? draftKeyPoints : refined.keyPoints();
            String markdown = refined.markdown() == null || refined.markdown().isBlank() ? draftMarkdown : refined.markdown();
            return new RefinementOutcome(
                    true,
                    summary,
                    keyPoints,
                    markdown,
                    normalizeStructuredContent(
                            refined.structuredContent(),
                            domain,
                            topic,
                            evidence,
                            summary,
                            keyPoints,
                            excludedTerms
                    ),
                    withRefinementGroups(refined.metadata(), refinedGroups)
            );
        } catch (DomainKnowledgePauseException ex) {
            markJobProgress(job.getId(), "paused", Map.of(
                    "status", "paused",
                    "pauseReason", ex.getMessage(),
                    "pauseMetadata", ex.metadata(),
                    "draftSummary", draftSummary,
                    "draftKeyPoints", draftKeyPoints,
                    "documentCount", evidence.documents().size(),
                    "knowledgeUnitCount", evidence.knowledgeUnits().size(),
                    "chunkCount", evidence.chunks().size()
            ));
            throw ex;
        }
    }

    private List<Map<String, Object>> refineEvidenceGroups(
            DomainRefineJob job,
            DomainDefinition domain,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            List<Map<String, Object>> evidenceGroups
    ) {
        if (evidenceGroups.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> refinedGroups = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> group : evidenceGroups) {
            if (domainRefineJobProgressService.isCancelled(job.getId())) {
                throw new TaskCancelledException("DOMAIN_REFINE_CANCELLED");
            }
            index++;
            markJobProgress(job.getId(), "llm_group_refining", Map.of(
                    "groupIndex", index,
                    "groupCount", evidenceGroups.size(),
                    "groupName", String.valueOf(group.get("name")),
                    "groupEvidenceCount", toStringList(group.get("evidenceRefs")).size()
            ));
            DomainKnowledgeRefinementService.GroupRefinedResult result = domainKnowledgeRefinementService.refineGroup(
                    domain.getName(),
                    domainBuildSpec,
                    excludedTerms,
                    group
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", firstNonBlank(result.name(), String.valueOf(group.get("name"))));
            item.put("summary", nullSafe(result.summary(), ""));
            item.put("keyClaims", result.keyClaims() == null ? List.of() : result.keyClaims());
            item.put("evidenceRefs", filterKnownRefs(
                    result.evidenceRefs() == null || result.evidenceRefs().isEmpty()
                            ? toStringList(group.get("evidenceRefs"))
                            : result.evidenceRefs(),
                    new LinkedHashSet<>(toStringList(group.get("evidenceRefs")))
            ));
            item.put("warnings", result.warnings() == null ? List.of() : result.warnings());
            item.put("metadata", result.metadata() == null ? Map.of() : result.metadata());
            refinedGroups.add(item);
        }
        return refinedGroups;
    }

    private Map<String, Object> withRefinementGroups(Map<String, Object> metadata, List<Map<String, Object>> refinedGroups) {
        Map<String, Object> result = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        result.put("refinementPipeline", "socratic_spec_to_retrieval_plan_to_group_refinement_to_synthesis");
        result.put("groupRefinementCount", refinedGroups == null ? 0 : refinedGroups.size());
        result.put("groupRefinements", refinedGroups == null ? List.of() : refinedGroups);
        return result;
    }

    private static class ScoreAccumulator {
        private final Map<String, Double> scores = new HashMap<>();
        private final Map<String, Map<String, Object>> items = new HashMap<>();

        void add(String id, Map<String, Object> item, double score) {
            if (id == null || id.isBlank() || item == null || item.isEmpty()) {
                return;
            }
            Map<String, Object> existing = items.get(id);
            if (existing == null || prefersIncomingEvidence(existing, item)) {
                items.put(id, item);
            }
            scores.merge(id, score, Double::sum);
        }

        private boolean prefersIncomingEvidence(Map<String, Object> existing, Map<String, Object> incoming) {
            return "graph_fact".equals(String.valueOf(incoming.get("_retrieval")))
                    && !"graph_fact".equals(String.valueOf(existing.get("_retrieval")));
        }

        int size() {
            return items.size();
        }

        boolean contains(String id) {
            return id != null && items.containsKey(id);
        }

        List<Map<String, Object>> items() {
            return new ArrayList<>(items.values());
        }

        List<String> ids() {
            return new ArrayList<>(items.keySet());
        }

        List<String> topIds(int limit) {
            if (items.isEmpty() || limit <= 0) {
                return List.of();
            }
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        List<Map<String, Object>> topN(int limit) {
            if (items.isEmpty() || limit <= 0) {
                return List.of();
            }
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(limit)
                    .map(entry -> items.get(entry.getKey()))
                    .toList();
        }
    }

    private String nullSafe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private record EvidenceBundle(
            List<String> terms,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> graphFacts,
            Map<String, Object> topicSubgraph,
            Map<String, Object> evidencePack,
            List<String> evidenceRefs,
            List<String> warnings
    ) {
    }

    private record RetrievalPlan(
            List<RetrievalPass> passes,
            List<RetrievalDimension> dimensions,
            List<String> allTerms,
            int totalTerms,
            Map<String, Object> raw
    ) {
        Map<String, Object> toProgressMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("passCount", passes == null ? 0 : passes.size());
            map.put("dimensionCount", dimensions == null ? 0 : dimensions.size());
            map.put("totalTerms", totalTerms);
            map.put("passes", passes == null ? List.of() : passes.stream().map(RetrievalPass::toProgressMap).toList());
            map.put("dimensions", dimensions == null ? List.of() : dimensions.stream().map(RetrievalDimension::toProgressMap).toList());
            return map;
        }
    }

    private record RetrievalPass(
            String name,
            String dimensionName,
            double weight,
            List<String> terms
    ) {
        Map<String, Object> toProgressMap() {
            return Map.of(
                    "name", name,
                    "dimension", dimensionName,
                    "weight", weight,
                    "termCount", terms == null ? 0 : terms.size()
            );
        }
    }

    private record RetrievalDimension(
            String name,
            List<String> terms,
            List<String> requiredQuestions,
            List<String> evidenceTypes,
            int minEvidence
    ) {
        Map<String, Object> toProgressMap() {
            return Map.of(
                    "name", name,
                    "termCount", terms == null ? 0 : terms.size(),
                    "requiredQuestions", requiredQuestions == null ? List.of() : requiredQuestions,
                    "evidenceTypes", evidenceTypes == null ? List.of() : evidenceTypes,
                    "minEvidence", minEvidence
            );
        }
    }

    private record RefinementOutcome(
            boolean refined,
            String summary,
            List<String> keyPoints,
            String markdown,
            Map<String, Object> structuredContent,
            Map<String, Object> metadata
    ) {
        Map<String, Object> validation() {
            Object raw = structuredContent == null ? null : structuredContent.get("validation");
            if (!(raw instanceof Map<?, ?> map)) {
                return new LinkedHashMap<>(Map.of("status", "review_required"));
            }
            Map<String, Object> validation = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    validation.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return validation;
        }

        String packStatus() {
            String status = String.valueOf(validation().getOrDefault("status", "review_required")).trim().toLowerCase(Locale.ROOT);
            if (List.of("ready", "review_required", "failed").contains(status)) {
                return status;
            }
            return "review_required";
        }
    }

    private record EvidenceCandidate(
            String type,
            String id,
            String docId,
            double score,
            Map<String, Object> payload
    ) {
    }
}
