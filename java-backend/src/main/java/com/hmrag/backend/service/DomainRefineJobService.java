package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.domain.TopicDefinition;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.repository.DomainMemoryPackRepository;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import com.hmrag.backend.repository.TopicDefinitionRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DomainRefineJobService {

    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainDefinitionRepository domainDefinitionRepository;
    private final TopicDefinitionRepository topicDefinitionRepository;
    private final DomainMemoryPackRepository domainMemoryPackRepository;
    private final KnowledgeGraphBuildService knowledgeGraphBuildService;

    public DomainRefineJobService(
            DomainRefineJobRepository domainRefineJobRepository,
            DomainDefinitionRepository domainDefinitionRepository,
            TopicDefinitionRepository topicDefinitionRepository,
            DomainMemoryPackRepository domainMemoryPackRepository,
            KnowledgeGraphBuildService knowledgeGraphBuildService
    ) {
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainDefinitionRepository = domainDefinitionRepository;
        this.topicDefinitionRepository = topicDefinitionRepository;
        this.domainMemoryPackRepository = domainMemoryPackRepository;
        this.knowledgeGraphBuildService = knowledgeGraphBuildService;
    }

    @Transactional
    public ApiDtos.DomainRefineJobItem startDomainRefine(UUID domainId, ApiDtos.StartDomainRefineRequest request) {
        DomainDefinition domain = requireDomain(domainId);
        DomainRefineJob job = new DomainRefineJob();
        Map<String, Object> scope = buildDomainScope(domain, request == null ? null : request.scopeSnapshot());
        Map<String, Object> inputSummary = copyMap(request == null ? null : request.inputSummary());
        Map<String, Object> graphOptimization = prepareGraphOptimization(domain, null, inputSummary);
        job.setJobType(defaultValue(request == null ? null : request.jobType(), "domain_refine"));
        job.setDomainId(domain.getId());
        job.setStatus(shouldWaitForGraphOptimization(graphOptimization) ? "waiting_graph_optimization" : "queued");
        job.setTriggerSource(defaultValue(request == null ? null : request.triggerSource(), "user"));
        job.setModelProfile(trimToNull(request == null ? null : request.modelProfile()));
        scope.put("graphOptimization", graphOptimization);
        inputSummary.put("graphOptimization", graphOptimization);
        job.setScopeSnapshotJson(scope);
        job.setInputSummaryJson(inputSummary);
        job.setOutputSummaryJson(new HashMap<>());
        DomainRefineJob saved = domainRefineJobRepository.save(job);
        return toItem(saved, false);
    }

    @Transactional
    public ApiDtos.DomainRefineJobItem startTopicRefine(UUID topicId, ApiDtos.StartTopicRefineRequest request) {
        TopicDefinition topic = requireTopic(topicId);
        DomainDefinition domain = requireDomain(topic.getDomainId());
        DomainRefineJob job = new DomainRefineJob();
        Map<String, Object> scope = buildTopicScope(topic, request == null ? null : request.scopeSnapshot());
        Map<String, Object> inputSummary = copyMap(request == null ? null : request.inputSummary());
        Map<String, Object> graphOptimization = prepareGraphOptimization(domain, topic, inputSummary);
        job.setJobType(defaultValue(request == null ? null : request.jobType(), "topic_refine"));
        job.setDomainId(topic.getDomainId());
        job.setTopicId(topic.getId());
        job.setStatus(shouldWaitForGraphOptimization(graphOptimization) ? "waiting_graph_optimization" : "queued");
        job.setTriggerSource(defaultValue(request == null ? null : request.triggerSource(), "user"));
        job.setModelProfile(trimToNull(request == null ? null : request.modelProfile()));
        scope.put("graphOptimization", graphOptimization);
        inputSummary.put("graphOptimization", graphOptimization);
        job.setScopeSnapshotJson(scope);
        job.setInputSummaryJson(inputSummary);
        job.setOutputSummaryJson(new HashMap<>());
        DomainRefineJob saved = domainRefineJobRepository.save(job);
        return toItem(saved, false);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainRefineJobItem> list(UUID domainId, UUID topicId, String triggerSource) {
        List<DomainRefineJob> jobs;
        if (topicId != null) {
            requireTopic(topicId);
            jobs = domainRefineJobRepository.findByTopicIdOrderByCreatedAtDesc(topicId);
        } else if (domainId != null) {
            requireDomain(domainId);
            jobs = domainRefineJobRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
        } else {
            jobs = domainRefineJobRepository.findAllByOrderByCreatedAtDesc();
        }
        String normalizedTriggerSource = trimToNull(triggerSource);
        if (normalizedTriggerSource != null) {
            jobs = jobs.stream()
                    .filter(job -> normalizedTriggerSource.equalsIgnoreCase(job.getTriggerSource()))
                    .toList();
        }
        Set<UUID> jobsWithPack = loadJobsWithPack(jobs);
        return jobs.stream().map(job -> toItem(job, jobsWithPack.contains(job.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.DomainRefineJobItem get(UUID id) {
        DomainRefineJob job = requireJob(id);
        return toItem(job, domainMemoryPackRepository.existsByRefineJobId(job.getId()));
    }

    @Transactional
    public ApiDtos.DomainRefineJobItem cancel(UUID id) {
        DomainRefineJob job = requireJob(id);
        if ("completed".equalsIgnoreCase(job.getStatus())
                || "failed".equalsIgnoreCase(job.getStatus())
                || "cancelled".equalsIgnoreCase(job.getStatus())) {
            return toItem(job, domainMemoryPackRepository.existsByRefineJobId(job.getId()));
        }
        job.setStatus("cancelled");
        job.setFinishedAt(job.getFinishedAt() == null ? OffsetDateTime.now() : job.getFinishedAt());
        job.setErrorMessage(job.getErrorMessage() == null ? "Cancelled by user" : job.getErrorMessage());
        DomainRefineJob saved = domainRefineJobRepository.save(job);
        return toItem(saved, domainMemoryPackRepository.existsByRefineJobId(saved.getId()));
    }

    @Transactional
    public ApiDtos.DomainRefineJobItem resume(UUID id) {
        DomainRefineJob job = requireJob(id);
        if (!"paused".equalsIgnoreCase(job.getStatus())) {
            throw new IllegalArgumentException("只有 paused 状态的精炼任务可以继续: " + id);
        }
        job.setStatus("queued");
        job.setErrorMessage(null);
        job.setHeartbeatAt(OffsetDateTime.now());
        job.setFinishedAt(null);
        DomainRefineJob saved = domainRefineJobRepository.save(job);
        return toItem(saved, domainMemoryPackRepository.existsByRefineJobId(saved.getId()));
    }

    private DomainDefinition requireDomain(UUID id) {
        return domainDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("领域不存在: " + id));
    }

    private TopicDefinition requireTopic(UUID id) {
        return topicDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("专题不存在: " + id));
    }

    private DomainRefineJob requireJob(UUID id) {
        return domainRefineJobRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("精炼任务不存在: " + id));
    }

    private Map<String, Object> buildDomainScope(DomainDefinition domain, Map<String, Object> override) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("domainId", domain.getId());
        scope.put("domainName", domain.getName());
        scope.put("scopeRules", copyMap(domain.getScopeRulesJson()));
        scope.put("seedQueries", domain.getSeedQueriesJson());
        if (override != null && !override.isEmpty()) {
            scope.putAll(override);
        }
        return scope;
    }

    private Map<String, Object> buildTopicScope(TopicDefinition topic, Map<String, Object> override) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("domainId", topic.getDomainId());
        scope.put("topicId", topic.getId());
        scope.put("topicName", topic.getName());
        scope.put("scopeRules", copyMap(topic.getScopeRulesJson()));
        scope.put("seedQueries", topic.getSeedQueriesJson());
        if (override != null && !override.isEmpty()) {
            scope.putAll(override);
        }
        return scope;
    }

    private Map<String, Object> prepareGraphOptimization(DomainDefinition domain, TopicDefinition topic, Map<String, Object> inputSummary) {
        List<String> scopeKeys = graphOptimizationScopeKeys(domain, topic);
        String scopeKey = String.join("；", scopeKeys);
        int limit = graphOptimizationLimit(inputSummary);
        try {
            Map<String, Object> result = knowledgeGraphBuildService.enqueueQueryEnrichmentScopes(
                    scopeKeys,
                    limit,
                    topic == null ? "knowledge_pack_domain_enrichment" : "knowledge_pack_topic_enrichment"
            );
            Map<String, Object> optimization = new HashMap<>(result);
            optimization.put("enabled", true);
            optimization.put("status", shouldWaitForGraphOptimization(optimization) ? "waiting" : "skipped");
            optimization.put("scopeType", "query");
            optimization.put("scopeKey", scopeKey);
            optimization.put("scopeKeys", scopeKeys);
            optimization.put("reason", topic == null ? "domain_memory_pack_prebuild" : "topic_memory_pack_prebuild");
            return optimization;
        } catch (Exception ex) {
            Map<String, Object> optimization = new HashMap<>();
            optimization.put("enabled", false);
            optimization.put("status", "failed_to_enqueue");
            optimization.put("scopeType", "query");
            optimization.put("scopeKey", scopeKey);
            optimization.put("scopeKeys", scopeKeys);
            optimization.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return optimization;
        }
    }

    private boolean shouldWaitForGraphOptimization(Map<String, Object> graphOptimization) {
        return intValue(graphOptimization.get("queued"), 0) > 0;
    }

    private List<String> graphOptimizationScopeKeys(DomainDefinition domain, TopicDefinition topic) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (domain != null) {
            addGraphOptimizationScope(parts, domain.getName());
            if (domain.getSeedQueriesJson() != null) {
                domain.getSeedQueriesJson().forEach(value -> addGraphOptimizationScope(parts, value));
            }
        }
        if (topic != null) {
            addGraphOptimizationScope(parts, topic.getName());
            if (topic.getSeedQueriesJson() != null) {
                topic.getSeedQueriesJson().forEach(value -> addGraphOptimizationScope(parts, value));
            }
        }
        return new ArrayList<>(parts).stream().limit(12).toList();
    }

    private void addGraphOptimizationScope(LinkedHashSet<String> scopes, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            scopes.add(normalized.length() > 120 ? normalized.substring(0, 120) : normalized);
        }
    }

    private int graphOptimizationLimit(Map<String, Object> inputSummary) {
        Object raw = inputSummary == null ? null : inputSummary.get("graphOptimizationLimit");
        return Math.max(1, Math.min(intValue(raw, 100), 500));
    }

    private ApiDtos.DomainRefineJobItem toItem(DomainRefineJob job, boolean hasMemoryPack) {
        String status = job.getStatus();
        String errorMessage = job.getErrorMessage();
        if ("completed".equalsIgnoreCase(status) && !hasMemoryPack) {
            status = "failed";
            if (trimToNull(errorMessage) == null) {
                errorMessage = "DOMAIN_MEMORY_PACK_MISSING";
            }
        }
        return new ApiDtos.DomainRefineJobItem(
                job.getId(),
                job.getJobType(),
                job.getDomainId(),
                job.getTopicId(),
                status,
                job.getTriggerSource(),
                job.getModelProfile(),
                hasMemoryPack,
                copyMap(job.getScopeSnapshotJson()),
                copyMap(job.getInputSummaryJson()),
                copyMap(job.getOutputSummaryJson()),
                errorMessage,
                job.getHeartbeatAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }

    private Set<UUID> loadJobsWithPack(List<DomainRefineJob> jobs) {
        List<UUID> jobIds = jobs.stream()
                .map(DomainRefineJob::getId)
                .toList();
        if (jobIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        domainMemoryPackRepository.findByRefineJobIdIn(jobIds)
                .forEach(pack -> {
                    if (pack.getRefineJobId() != null) {
                        result.add(pack.getRefineJobId());
                    }
                });
        return result;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultValue(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
