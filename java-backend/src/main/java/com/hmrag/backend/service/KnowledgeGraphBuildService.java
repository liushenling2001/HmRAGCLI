package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.SourceFile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeGraphBuildService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final KnowledgeGraphExtractionService extractionService;
    private final KnowledgeGraphStoreClient graphStoreClient;

    public KnowledgeGraphBuildService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            KnowledgeGraphExtractionService extractionService,
            KnowledgeGraphStoreClient graphStoreClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.extractionService = extractionService;
        this.graphStoreClient = graphStoreClient;
    }

    public boolean isEnabled() {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        return config != null && config.enabled();
    }

    public Map<String, Object> graphView(int targetEntities) {
        return graphStoreClient.readGraphView(targetEntities);
    }

    public Map<String, Object> graphStats() {
        return graphStoreClient.readGraphStats();
    }

    public Map<String, Object> graphQuality() {
        return graphStoreClient.readGraphQuality();
    }

    public Map<String, Object> entityList(String search, int limit) {
        return graphStoreClient.readEntityList(search, limit);
    }

    public Map<String, Object> entityDetail(String entityId, int connectionLimit) {
        return graphStoreClient.readEntityDetail(entityId, connectionLimit);
    }

    public List<Map<String, Object>> listEntityTypeTemplates() {
        return jdbcTemplate.query(
                """
                SELECT code, label, description, aliases_json::text AS aliases_json,
                       attribute_hints_json::text AS attribute_hints_json,
                       relation_hints_json::text AS relation_hints_json,
                       state_attribute_hints_json::text AS state_attribute_hints_json,
                       transition_hints_json::text AS transition_hints_json,
                       anti_patterns_json::text AS anti_patterns_json,
                       enabled, sort_order, updated_at
                FROM graph_entity_type_templates
                ORDER BY sort_order ASC, code ASC
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", rs.getString("code"));
                    item.put("label", rs.getString("label"));
                    item.put("description", rs.getString("description"));
                    item.put("aliases", parseList(rs.getString("aliases_json")));
                    item.put("attributeHints", parseList(rs.getString("attribute_hints_json")));
                    item.put("relationHints", parseList(rs.getString("relation_hints_json")));
                    item.put("stateAttributeHints", parseList(rs.getString("state_attribute_hints_json")));
                    item.put("transitionHints", parseList(rs.getString("transition_hints_json")));
                    item.put("antiPatterns", parseList(rs.getString("anti_patterns_json")));
                    item.put("enabled", rs.getBoolean("enabled"));
                    item.put("sortOrder", rs.getInt("sort_order"));
                    item.put("updatedAt", rs.getObject("updated_at"));
                    return item;
                }
        );
    }

    public Map<String, Object> saveEntityTypeTemplates(List<Map<String, Object>> templates) {
        if (templates == null) {
            templates = List.of();
        }
        int saved = 0;
        for (Map<String, Object> template : templates) {
            String code = stringValue(template.get("code"));
            String label = stringValue(template.get("label"));
            if (code.isBlank() || label.isBlank()) {
                continue;
            }
            try {
                jdbcTemplate.update(
                        """
                        INSERT INTO graph_entity_type_templates (
                            code, label, description, aliases_json, attribute_hints_json, relation_hints_json,
                            state_attribute_hints_json, transition_hints_json, anti_patterns_json,
                            enabled, sort_order, created_at, updated_at
                        ) VALUES (
                            :code, :label, :description, CAST(:aliases AS jsonb), CAST(:attributeHints AS jsonb),
                            CAST(:relationHints AS jsonb), CAST(:stateAttributeHints AS jsonb),
                            CAST(:transitionHints AS jsonb), CAST(:antiPatterns AS jsonb),
                            :enabled, :sortOrder, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        ON CONFLICT (code)
                        DO UPDATE SET
                            label = EXCLUDED.label,
                            description = EXCLUDED.description,
                            aliases_json = EXCLUDED.aliases_json,
                            attribute_hints_json = EXCLUDED.attribute_hints_json,
                            relation_hints_json = EXCLUDED.relation_hints_json,
                            state_attribute_hints_json = EXCLUDED.state_attribute_hints_json,
                            transition_hints_json = EXCLUDED.transition_hints_json,
                            anti_patterns_json = EXCLUDED.anti_patterns_json,
                            enabled = EXCLUDED.enabled,
                            sort_order = EXCLUDED.sort_order,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                        new MapSqlParameterSource()
                                .addValue("code", code)
                                .addValue("label", label)
                                .addValue("description", stringValue(template.get("description")))
                                .addValue("aliases", jsonArray(template.get("aliases")))
                                .addValue("attributeHints", jsonArray(template.get("attributeHints")))
                                .addValue("relationHints", jsonArray(template.get("relationHints")))
                                .addValue("stateAttributeHints", jsonArray(template.get("stateAttributeHints")))
                                .addValue("transitionHints", jsonArray(template.get("transitionHints")))
                                .addValue("antiPatterns", jsonArray(template.get("antiPatterns")))
                                .addValue("enabled", booleanValue(template.get("enabled"), true))
                                .addValue("sortOrder", intValue(template.get("sortOrder"), saved * 10))
                );
                saved++;
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid entity type template JSON: " + ex.getMessage(), ex);
            }
        }
        return Map.of("saved", saved, "templates", listEntityTypeTemplates());
    }

    public void enqueueAfterIndex(SourceFile file) {
        if (!isEnabled() || file == null || file.getDocId() == null) {
            return;
        }
        enqueueJob(file.getId(), file.getDocId(), UUID.randomUUID(), "ingest");
    }

    private boolean enqueueJob(UUID sourceFileId, UUID docId, UUID graphBatchId, String triggerSource) {
        if (!isEnabled() || sourceFileId == null || docId == null) {
            return false;
        }
        UUID jobId = UUID.randomUUID();
        String store = graphStoreClient.provider();
        String graphVersion = extractionService.graphVersion();
        String model = appProperties.knowledgeGraph().extractionLlm() == null ? null : appProperties.knowledgeGraph().extractionLlm().model();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("sourceFileId", sourceFileId)
                .addValue("docId", docId)
                .addValue("graphBatchId", graphBatchId)
                .addValue("docKey", docId.toString())
                .addValue("triggerSource", triggerSource == null || triggerSource.isBlank() ? "manual" : triggerSource)
                .addValue("graphStore", store)
                .addValue("graphVersion", graphVersion)
                .addValue("extractModel", model);
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO graph_build_jobs (
                    id, source_file_id, doc_id, graph_batch_id, status, stage, trigger_source,
                    graph_store, graph_version, extract_model, fusion_model,
                    created_at, updated_at
                ) VALUES (
                    :id, :sourceFileId, :docId, :graphBatchId, 'queued', 'queued', :triggerSource,
                    :graphStore, :graphVersion, :extractModel, 'deterministic-canonical-fusion-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT DO NOTHING
                """,
                params
        );
        if (inserted == 0) {
            return false;
        }
        jdbcTemplate.update(
                """
                INSERT INTO graph_sync_state (
                    source_file_id, doc_id, graph_store, graph_version, graph_document_key,
                    status, metadata_json, created_at, updated_at
                ) VALUES (
                    :sourceFileId, :docId, :graphStore, :graphVersion, :docKey,
                    'pending', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (source_file_id)
                DO UPDATE SET
                    doc_id = EXCLUDED.doc_id,
                    graph_store = EXCLUDED.graph_store,
                    graph_version = EXCLUDED.graph_version,
                    graph_document_key = EXCLUDED.graph_document_key,
                    status = 'pending',
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """,
                params
        );
        return true;
    }

    public List<Map<String, Object>> listJobs(String status, int limit) {
        String statusFilter = status == null || status.isBlank() ? "" : "AND status = :status";
        MapSqlParameterSource params = new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 200)));
        if (!statusFilter.isBlank()) {
            params.addValue("status", status);
        }
        return jdbcTemplate.query(
                """
                WITH ranked AS (
                    SELECT id, source_file_id, doc_id, graph_batch_id, status, stage, trigger_source, graph_store,
                           graph_version, extract_model, fusion_model, output_summary_json,
                           error_message, heartbeat_at, started_at, finished_at, created_at, updated_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY source_file_id
                               ORDER BY created_at DESC, updated_at DESC
                           ) AS rn
                    FROM graph_build_jobs
                    WHERE status <> 'cancelled'
                )
                SELECT id, source_file_id, doc_id, graph_batch_id, status, stage, trigger_source, graph_store,
                       graph_version, extract_model, fusion_model, output_summary_json::text AS output_summary_json,
                       error_message, heartbeat_at, started_at, finished_at, created_at, updated_at
                FROM ranked
                WHERE rn = 1
                """ + statusFilter + """

                ORDER BY
                    CASE status
                        WHEN 'running' THEN 1
                        WHEN 'queued' THEN 2
                        WHEN 'failed' THEN 3
                        WHEN 'success' THEN 4
                        ELSE 5
                    END,
                    updated_at DESC
                LIMIT :limit
                """,
                params,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("sourceFileId", rs.getString("source_file_id"));
                    item.put("docId", rs.getString("doc_id"));
                    item.put("graphBatchId", rs.getString("graph_batch_id"));
                    item.put("status", rs.getString("status"));
                    item.put("stage", rs.getString("stage"));
                    item.put("triggerSource", rs.getString("trigger_source"));
                    item.put("graphStore", rs.getString("graph_store"));
                    item.put("graphVersion", rs.getString("graph_version"));
                    item.put("extractModel", rs.getString("extract_model"));
                    item.put("fusionModel", rs.getString("fusion_model"));
                    item.put("outputSummary", parseMap(rs.getString("output_summary_json")));
                    item.put("errorMessage", rs.getString("error_message"));
                    item.put("heartbeatAt", rs.getObject("heartbeat_at"));
                    item.put("startedAt", rs.getObject("started_at"));
                    item.put("finishedAt", rs.getObject("finished_at"));
                    item.put("createdAt", rs.getObject("created_at"));
                    item.put("updatedAt", rs.getObject("updated_at"));
                    return item;
                }
        );
    }

    public Map<String, Object> retryFailedJob(UUID jobId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        UUID graphBatchId = UUID.randomUUID();
        Map<String, Object> target = jdbcTemplate.query(
                """
                SELECT source_file_id, doc_id
                FROM graph_build_jobs
                WHERE id = :jobId
                  AND status IN ('failed', 'success')
                LIMIT 1
                """,
                new MapSqlParameterSource("jobId", jobId),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return Map.of(
                            "sourceFileId", UUID.fromString(rs.getString("source_file_id")),
                            "docId", UUID.fromString(rs.getString("doc_id"))
                    );
                }
        );
        if (target == null) {
            return Map.of("jobId", jobId, "queued", false, "message", "Only completed graph jobs can be restarted. Queued or running jobs are already active.");
        }
        clearExtractionCache((UUID) target.get("sourceFileId"), (UUID) target.get("docId"));
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'cancelled',
                    stage = 'cancelled_by_retry',
                    error_message = 'CANCELLED_BY_GRAPH_RETRY',
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE source_file_id = :sourceFileId
                  AND id <> :jobId
                  AND status IN ('queued', 'running')
                """,
                new MapSqlParameterSource()
                        .addValue("sourceFileId", target.get("sourceFileId"))
                        .addValue("jobId", jobId)
        );
        int updated = jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'queued',
                    stage = 'queued',
                    graph_batch_id = :graphBatchId,
                    trigger_source = 'manual_retry',
                    graph_version = :graphVersion,
                    extract_model = :extractModel,
                    local_graph_json = '{}'::jsonb,
                    output_summary_json = '{}'::jsonb,
                    error_message = NULL,
                    heartbeat_at = NULL,
                    started_at = NULL,
                    finished_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :jobId
                  AND status IN ('failed', 'success')
                """,
                new MapSqlParameterSource("jobId", jobId)
                        .addValue("graphBatchId", graphBatchId)
                        .addValue("graphVersion", extractionService.graphVersion())
                        .addValue("extractModel", currentExtractionModel())
        );
        if (updated == 0) {
            return Map.of("jobId", jobId, "queued", false, "message", "Only completed graph jobs can be restarted. Queued or running jobs are already active.");
        }
        jdbcTemplate.update(
                """
                UPDATE graph_sync_state gss
                SET status = 'pending',
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM graph_build_jobs gj
                WHERE gj.id = :jobId
                  AND gss.source_file_id = gj.source_file_id
                """,
                new MapSqlParameterSource("jobId", jobId)
        );
        return Map.of("jobId", jobId, "graphBatchId", graphBatchId, "queued", true);
    }

    private int clearExtractionCache(UUID sourceFileId, UUID docId) {
        return jdbcTemplate.update(
                """
                DELETE FROM graph_extraction_batches
                WHERE source_file_id = :sourceFileId
                  AND doc_id = :docId
                """,
                new MapSqlParameterSource()
                        .addValue("sourceFileId", sourceFileId)
                        .addValue("docId", docId)
        );
    }

    public boolean enqueueManual(UUID sourceFileId) {
        Map<String, Object> row = jdbcTemplate.query(
                """
                SELECT id, doc_id
                FROM source_files
                WHERE id = :sourceFileId
                  AND doc_id IS NOT NULL
                LIMIT 1
                """,
                new MapSqlParameterSource("sourceFileId", sourceFileId),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return Map.of("sourceFileId", UUID.fromString(rs.getString("id")), "docId", UUID.fromString(rs.getString("doc_id")));
                }
        );
        if (row == null) {
            throw new IllegalArgumentException("Source file is missing or has no indexed document: " + sourceFileId);
        }
        clearExtractionCache((UUID) row.get("sourceFileId"), (UUID) row.get("docId"));
        return enqueueJob((UUID) row.get("sourceFileId"), (UUID) row.get("docId"), UUID.randomUUID(), "manual");
    }

    public Map<String, Object> enqueueMissingIndexedFiles(Integer limit, UUID dataSourceId) {
        return enqueueIndexedFiles(limit, dataSourceId, false, "backfill_missing");
    }

    public Map<String, Object> enqueueIndexedFiles(Integer limit, UUID dataSourceId, boolean rebuildSuccess, String triggerSource) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 200 : limit, 5000));
        boolean filterDataSource = dataSourceId != null;
        UUID graphBatchId = UUID.randomUUID();
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT sf.id AS source_file_id, sf.doc_id
                FROM source_files sf
                LEFT JOIN graph_sync_state gss ON gss.source_file_id = sf.id
                WHERE sf.doc_id IS NOT NULL
                  AND sf.index_status = 'success'
                  AND (:filterDataSource = false OR sf.data_source_id = :dataSourceId)
                  AND (:rebuildSuccess = true OR COALESCE(gss.status, 'missing') <> 'success')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM graph_build_jobs gj
                      WHERE gj.source_file_id = sf.id
                        AND gj.status IN ('queued', 'running')
                  )
                ORDER BY sf.updated_at ASC NULLS LAST, sf.created_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("filterDataSource", filterDataSource)
                        .addValue("dataSourceId", dataSourceId)
                        .addValue("rebuildSuccess", rebuildSuccess)
                        .addValue("limit", safeLimit),
                (rs, rowNum) -> Map.<String, Object>of(
                        "sourceFileId", UUID.fromString(rs.getString("source_file_id")),
                        "docId", UUID.fromString(rs.getString("doc_id"))
                )
        );
        int queued = 0;
        List<String> queuedSourceFileIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID sourceFileId = (UUID) row.get("sourceFileId");
            if (retryLatestCompletedJobForSource(sourceFileId, graphBatchId, rebuildSuccess) || enqueueJob(sourceFileId, (UUID) row.get("docId"), graphBatchId, triggerSource == null || triggerSource.isBlank() ? "batch" : triggerSource)) {
                queued++;
                queuedSourceFileIds.add(sourceFileId.toString());
            }
        }
        return Map.of(
                "graphBatchId", graphBatchId,
                "matched", rows.size(),
                "queued", queued,
                "limit", safeLimit,
                "dataSourceId", dataSourceId == null ? "" : dataSourceId.toString(),
                "rebuildSuccess", rebuildSuccess,
                "sourceFileIds", queuedSourceFileIds
        );
    }

    public Map<String, Object> startFusion(UUID graphBatchId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        UUID fusionJobId = UUID.randomUUID();
        String mode = appProperties.knowledgeGraph().entityFusion() == null || appProperties.knowledgeGraph().entityFusion().mode() == null
                ? "deterministic"
                : appProperties.knowledgeGraph().entityFusion().mode();
        jdbcTemplate.update(
                """
                INSERT INTO graph_fusion_jobs (
                    id, graph_batch_id, status, stage, trigger_source, fusion_mode,
                    created_at, updated_at
                ) VALUES (
                    :id, :graphBatchId, 'running', 'fusing_entities', 'manual', :fusionMode,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", fusionJobId)
                        .addValue("graphBatchId", graphBatchId)
                        .addValue("fusionMode", mode)
        );
        try {
            Map<String, Object> summary = graphStoreClient.fuseEntities(graphBatchId == null ? null : graphBatchId.toString());
            summary = new LinkedHashMap<>(summary);
            summary.put("fusionJobId", fusionJobId.toString());
            String summaryJson = objectMapper.writeValueAsString(summary);
            jdbcTemplate.update(
                    """
                    UPDATE graph_fusion_jobs
                    SET status = 'success',
                        stage = 'completed',
                        output_summary_json = CAST(:summary AS jsonb),
                        error_message = NULL,
                        started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                        finished_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource("id", fusionJobId).addValue("summary", summaryJson)
            );
            if (graphBatchId != null) {
                jdbcTemplate.update(
                        """
                        UPDATE graph_build_jobs
                        SET output_summary_json = output_summary_json || CAST(:fusionSummary AS jsonb),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE graph_batch_id = :graphBatchId
                          AND status = 'success'
                        """,
                        new MapSqlParameterSource()
                                .addValue("graphBatchId", graphBatchId)
                                .addValue("fusionSummary", summaryJson)
                );
            }
            return summary;
        } catch (Exception ex) {
            jdbcTemplate.update(
                    """
                    UPDATE graph_fusion_jobs
                    SET status = 'failed',
                        stage = 'failed',
                        error_message = :message,
                        finished_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource("id", fusionJobId).addValue("message", truncate(ex.getMessage(), 1000))
            );
            throw new IllegalStateException("Knowledge graph fusion failed: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> startAttributeGovernance() {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        List<Map<String, Object>> templateHints = loadAttributeTemplateHints();
        Map<String, Object> base = graphStoreClient.governAttributes(templateHints, UUID.randomUUID().toString());
        Map<String, Object> applied = applyAttributeCandidateClusters(0.85d, 500, false);
        Map<String, Object> response = new LinkedHashMap<>(base);
        response.put("candidateClusterApplied", applied);
        return response;
    }

    public Map<String, Object> attributeCandidateClusters(int limit) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        List<Map<String, Object>> templateHints = loadAttributeTemplateHints();
        Map<String, Object> result = graphStoreClient.readAttributeCandidateClusters(templateHints, limit);
        return refineAttributeCandidateClusterResult(result, templateHints);
    }

    public Map<String, Object> applyAttributeCandidateClusters(double minConfidence, int maxClusters, boolean dryRun) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        List<Map<String, Object>> templateHints = loadAttributeTemplateHints();
        Map<String, Object> result = graphStoreClient.readAttributeCandidateClusters(templateHints, Math.max(1, Math.min(maxClusters, 500)));
        Map<String, Object> refined = refineAttributeCandidateClusterResult(result, templateHints);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = refined.get("clusters") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        Map<String, Object> applied = graphStoreClient.applyAttributeCandidateClusterDecisions(clusters, minConfidence, maxClusters, dryRun);
        Map<String, Object> response = new LinkedHashMap<>(applied);
        response.put("llmGovernance", refined.getOrDefault("llmGovernance", Map.of("enabled", false)));
        return response;
    }

    private Map<String, Object> refineAttributeCandidateClusterResult(Map<String, Object> result, List<Map<String, Object>> templateHints) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = result.get("clusters") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        Map<String, Object> response = new LinkedHashMap<>(result);
        Map<String, Object> llmGovernance = extractionService.refineAttributeCandidateClusters(clusters, templateHints);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refinedClusters = llmGovernance.get("clusters") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : clusters;
        response.put("clusters", refinedClusters);
        response.put("clusterCount", refinedClusters.size());
        response.put("llmGovernance", governanceSummary(llmGovernance));
        return response;
    }

    private Map<String, Object> governanceSummary(Map<String, Object> llmGovernance) {
        Map<String, Object> summary = new LinkedHashMap<>(llmGovernance == null ? Map.of() : llmGovernance);
        summary.remove("clusters");
        return summary;
    }

    private List<Map<String, Object>> loadAttributeTemplateHints() {
        return jdbcTemplate.query(
                """
                SELECT code, attribute_hints_json::text AS attribute_hints_json
                FROM graph_entity_type_templates
                WHERE enabled = true
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    List<Object> values = parseList(rs.getString("attribute_hints_json"));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Object value : values) {
                        String hint = stringValue(value);
                        if (!hint.isBlank()) {
                            rows.add(Map.of("entityType", rs.getString("code"), "hint", hint));
                        }
                    }
                    return rows;
                }
        ).stream().flatMap(List::stream).toList();
    }

    private List<Map<String, Object>> loadStateTemplateHints() {
        return jdbcTemplate.query(
                """
                SELECT code,
                       state_attribute_hints_json::text AS state_attribute_hints_json,
                       transition_hints_json::text AS transition_hints_json
                FROM graph_entity_type_templates
                WHERE enabled = true
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Object value : parseList(rs.getString("state_attribute_hints_json"))) {
                        String hint = stringValue(value);
                        if (!hint.isBlank()) {
                            rows.add(Map.of("entityType", rs.getString("code"), "hint", hint, "hintKind", "state"));
                        }
                    }
                    for (Object value : parseList(rs.getString("transition_hints_json"))) {
                        String hint = stringValue(value);
                        if (!hint.isBlank()) {
                            rows.add(Map.of("entityType", rs.getString("code"), "hint", hint, "hintKind", "transition"));
                        }
                    }
                    return rows;
                }
        ).stream().flatMap(List::stream).toList();
    }

    public Map<String, Object> startStructureEnhancement() {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        List<Map<String, Object>> contexts = jdbcTemplate.query(
                """
                SELECT c.id::text AS chunk_id,
                       c.doc_id::text AS doc_id,
                       COALESCE(c.title, '') AS title,
                       COALESCE(c.chunk_type, '') AS chunk_type,
                       COALESCE(c.content, '') AS content,
                       COALESCE(c.metadata_json, '{}'::jsonb)::text AS chunk_metadata_json,
                       COALESCE(d.title, '') AS doc_title,
                       COALESCE(jsonb_agg(DISTINCT ku.title) FILTER (WHERE ku.title IS NOT NULL AND btrim(ku.title) <> ''), '[]'::jsonb)::text AS ku_titles_json,
                       COALESCE(jsonb_agg(DISTINCT ku.subject) FILTER (WHERE ku.subject IS NOT NULL AND btrim(ku.subject) <> ''), '[]'::jsonb)::text AS ku_subjects_json,
                       COALESCE(jsonb_agg(DISTINCT ku.indicator) FILTER (WHERE ku.indicator IS NOT NULL AND btrim(ku.indicator) <> ''), '[]'::jsonb)::text AS ku_indicators_json,
                       COALESCE(jsonb_agg(ku.fields_json) FILTER (WHERE ku.id IS NOT NULL), '[]'::jsonb)::text AS ku_fields_json
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                LEFT JOIN knowledge_units ku ON ku.chunk_id = c.id
                WHERE EXISTS (
                    SELECT 1
                    FROM graph_build_jobs j
                    WHERE j.doc_id = c.doc_id
                      AND j.status = 'success'
                )
                GROUP BY c.id, c.doc_id, c.title, c.chunk_type, c.content, c.metadata_json, d.title, c.chunk_no
                ORDER BY c.doc_id, c.chunk_no
                LIMIT 5000
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    String title = rs.getString("title");
                    String content = rs.getString("content");
                    String docTitle = rs.getString("doc_title");
                    Map<String, Object> chunkMetadata = parseMap(rs.getString("chunk_metadata_json"));
                    List<Object> kuTitles = parseList(rs.getString("ku_titles_json"));
                    List<Object> kuSubjects = parseList(rs.getString("ku_subjects_json"));
                    List<Object> kuIndicators = parseList(rs.getString("ku_indicators_json"));
                    List<Object> kuFields = parseList(rs.getString("ku_fields_json"));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", rs.getString("chunk_id"));
                    item.put("docId", rs.getString("doc_id"));
                    item.put("title", title);
                    item.put("chunkType", rs.getString("chunk_type"));
                    item.put("docTitle", docTitle);
                    item.put("keywords", buildStructureSignals(docTitle, title, content, chunkMetadata, kuTitles, kuSubjects, kuIndicators, kuFields));
                    item.put("kuSubjects", stringifySignals(kuSubjects, 12));
                    item.put("kuIndicators", stringifySignals(kuIndicators, 12));
                    item.put("kuTitles", stringifySignals(kuTitles, 12));
                    return item;
                }
        );
        return graphStoreClient.enhanceStructure(contexts, UUID.randomUUID().toString());
    }

    public Map<String, Object> startStateFusion(UUID graphBatchId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        Map<String, Object> materialized = graphStoreClient.materializeEntityStates(
                loadStateTemplateHints(),
                graphBatchId == null ? null : graphBatchId.toString()
        );
        Map<String, Object> summary = graphStoreClient.fuseEntityStates(graphBatchId == null ? null : graphBatchId.toString());
        summary = new LinkedHashMap<>(summary);
        summary.put("stage", "state_fusion_completed");
        summary.put("stateMaterialization", materialized);
        return summary;
    }

    public Map<String, Object> startTransitionBuild(UUID graphBatchId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Knowledge graph build is disabled.");
        }
        Map<String, Object> materialized = graphStoreClient.materializeEntityStates(
                loadStateTemplateHints(),
                graphBatchId == null ? null : graphBatchId.toString()
        );
        Map<String, Object> summary = graphStoreClient.buildStateTransitions(graphBatchId == null ? null : graphBatchId.toString());
        summary = new LinkedHashMap<>(summary);
        summary.put("stage", "transition_build_completed");
        summary.put("stateMaterialization", materialized);
        return summary;
    }

    public List<Map<String, Object>> listFusionJobs(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, graph_batch_id, status, stage, trigger_source, fusion_mode,
                       output_summary_json::text AS output_summary_json,
                       error_message, started_at, finished_at, created_at, updated_at
                FROM graph_fusion_jobs
                ORDER BY created_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100))),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("graphBatchId", rs.getString("graph_batch_id"));
                    item.put("status", rs.getString("status"));
                    item.put("stage", rs.getString("stage"));
                    item.put("triggerSource", rs.getString("trigger_source"));
                    item.put("fusionMode", rs.getString("fusion_mode"));
                    item.put("outputSummary", parseMap(rs.getString("output_summary_json")));
                    item.put("errorMessage", rs.getString("error_message"));
                    item.put("startedAt", rs.getObject("started_at"));
                    item.put("finishedAt", rs.getObject("finished_at"));
                    item.put("createdAt", rs.getObject("created_at"));
                    item.put("updatedAt", rs.getObject("updated_at"));
                    return item;
                }
        );
    }

    private boolean retryLatestCompletedJobForSource(UUID sourceFileId, UUID graphBatchId, boolean includeSuccess) {
        UUID jobId = jdbcTemplate.query(
                """
                SELECT id
                FROM graph_build_jobs
                WHERE source_file_id = :sourceFileId
                  AND status IN ('failed', 'success')
                  AND (:includeSuccess = true OR status = 'failed')
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("sourceFileId", sourceFileId)
                        .addValue("includeSuccess", includeSuccess),
                rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null
        );
        if (jobId == null) {
            return false;
        }
        return queueExistingCompletedJob(jobId, graphBatchId);
    }

    private boolean queueExistingCompletedJob(UUID jobId, UUID graphBatchId) {
        Map<String, Object> target = jdbcTemplate.query(
                """
                SELECT source_file_id, doc_id
                FROM graph_build_jobs
                WHERE id = :jobId
                LIMIT 1
                """,
                new MapSqlParameterSource("jobId", jobId),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return Map.of(
                            "sourceFileId", UUID.fromString(rs.getString("source_file_id")),
                            "docId", UUID.fromString(rs.getString("doc_id"))
                    );
                }
        );
        if (target == null) {
            return false;
        }
        clearExtractionCache((UUID) target.get("sourceFileId"), (UUID) target.get("docId"));
        int updated = jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'queued',
                    stage = 'queued',
                    graph_batch_id = :graphBatchId,
                    trigger_source = 'batch_rebuild',
                    graph_version = :graphVersion,
                    extract_model = :extractModel,
                    local_graph_json = '{}'::jsonb,
                    output_summary_json = '{}'::jsonb,
                    error_message = NULL,
                    heartbeat_at = NULL,
                    started_at = NULL,
                    finished_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :jobId
                  AND status IN ('failed', 'success')
                """,
                new MapSqlParameterSource("jobId", jobId)
                        .addValue("graphBatchId", graphBatchId)
                        .addValue("graphVersion", extractionService.graphVersion())
                        .addValue("extractModel", currentExtractionModel())
        );
        return updated > 0;
    }

    private String currentExtractionModel() {
        AppProperties.ExtractionLlm llm = appProperties.knowledgeGraph() == null ? null : appProperties.knowledgeGraph().extractionLlm();
        return llm == null ? "" : stringValue(llm.model());
    }

    public void runNextBatch() {
        if (!isEnabled()) {
            return;
        }
        int batchSize = Math.max(1, appProperties.knowledgeGraph().batchSize());
        for (int i = 0; i < batchSize; i++) {
            Map<String, Object> job = claimNextJob();
            if (job == null) {
                return;
            }
            runJob(job);
        }
    }

    public int recoverInterruptedJobsOnStartup() {
        if (!isEnabled()) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'queued',
                    stage = 'queued',
                    error_message = 'INTERRUPTED_GRAPH_JOB_RECOVERED_ON_STARTUP',
                    heartbeat_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'running'
                """,
                new MapSqlParameterSource()
        );
    }

    private Map<String, Object> claimNextJob() {
        return transactionTemplate.execute(status -> jdbcTemplate.query(
                """
                WITH picked AS (
                    SELECT id
                    FROM graph_build_jobs
                    WHERE status = 'queued'
                    ORDER BY created_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE graph_build_jobs j
                SET status = 'running',
                    stage = 'extracting_local_graph',
                    error_message = NULL,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM picked
                WHERE j.id = picked.id
                RETURNING j.id, j.source_file_id, j.doc_id, j.graph_batch_id
                """,
                new MapSqlParameterSource(),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", UUID.fromString(rs.getString("id")));
                    item.put("sourceFileId", UUID.fromString(rs.getString("source_file_id")));
                    item.put("docId", UUID.fromString(rs.getString("doc_id")));
                    String graphBatchId = rs.getString("graph_batch_id");
                    item.put("graphBatchId", graphBatchId == null ? UUID.randomUUID() : UUID.fromString(graphBatchId));
                    return item;
                }
        ));
    }

    private void runJob(Map<String, Object> job) {
        UUID jobId = (UUID) job.get("id");
        UUID sourceFileId = (UUID) job.get("sourceFileId");
        UUID docId = (UUID) job.get("docId");
        UUID graphBatchId = (UUID) job.get("graphBatchId");
        try {
            markStage(jobId, "extracting_local_graph");
            Map<String, Object> localGraph = extractionService.extract(docId, sourceFileId, progress -> markExtractionProgress(jobId, progress));
            localGraph.put("fusionBatchId", graphBatchId.toString());
            markStage(jobId, "normalizing_candidates");
            normalizeEntityTypesWithTemplates(localGraph);
            saveLocalGraph(jobId, localGraph);
            markStage(jobId, "writing_global_graph");
            Map<String, Object> summary = graphStoreClient.writeLocalGraph(localGraph);
            markSuccess(jobId, sourceFileId, docId, summary);
        } catch (Exception ex) {
            markFailed(jobId, sourceFileId, ex.getMessage());
        }
    }

    private void markExtractionProgress(UUID jobId, Map<String, Object> progress) {
        try {
            jdbcTemplate.update(
                    """
                    UPDATE graph_build_jobs
                    SET output_summary_json = jsonb_set(
                            COALESCE(output_summary_json, '{}'::jsonb),
                            '{currentExtractionBatch}',
                            CAST(:progress AS jsonb),
                            true
                        ),
                        graph_version = :graphVersion,
                        extract_model = :extractModel,
                        heartbeat_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", jobId)
                            .addValue("graphVersion", extractionService.graphVersion())
                            .addValue("extractModel", currentExtractionModel())
                            .addValue("progress", objectMapper.writeValueAsString(progress))
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize graph extraction progress: " + ex.getMessage(), ex);
        }
    }

    private void saveLocalGraph(UUID jobId, Map<String, Object> localGraph) throws JsonProcessingException {
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET local_graph_json = CAST(:localGraph AS jsonb),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("localGraph", objectMapper.writeValueAsString(localGraph))
        );
    }

    @SuppressWarnings("unchecked")
    private void normalizeEntityTypesWithTemplates(Map<String, Object> localGraph) {
        Map<String, String> typeLookup = entityTypeTemplateLookup();
        if (typeLookup.isEmpty()) {
            return;
        }
        Object rawEntities = localGraph.get("entities");
        if (rawEntities instanceof List<?> entities) {
            for (Object item : entities) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> entity = (Map<String, Object>) rawMap;
                    String normalizedType = typeLookup.get(normalizeTypeToken(stringValue(entity.get("type"))));
                    if (normalizedType != null && !normalizedType.isBlank()) {
                        entity.put("type", normalizedType);
                    }
                }
            }
        }
        Object rawFacts = localGraph.get("facts");
        if (rawFacts instanceof List<?> facts) {
            for (Object item : facts) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> fact = (Map<String, Object>) rawMap;
                    String normalizedObjectType = typeLookup.get(normalizeTypeToken(stringValue(fact.get("objectType"))));
                    if (normalizedObjectType != null && !normalizedObjectType.isBlank()) {
                        fact.put("objectType", normalizedObjectType);
                    }
                }
            }
        }
    }

    private Map<String, String> entityTypeTemplateLookup() {
        Map<String, String> lookup = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT code, label, aliases_json::text AS aliases_json
                FROM graph_entity_type_templates
                WHERE enabled = true
                ORDER BY sort_order ASC, code ASC
                """,
                new MapSqlParameterSource(),
                rs -> {
                    String code = rs.getString("code");
                    addTypeLookup(lookup, code, code);
                    addTypeLookup(lookup, rs.getString("label"), code);
                    for (Object alias : parseList(rs.getString("aliases_json"))) {
                        addTypeLookup(lookup, String.valueOf(alias), code);
                    }
                }
        );
        return lookup;
    }

    private void addTypeLookup(Map<String, String> lookup, String rawType, String code) {
        String token = normalizeTypeToken(rawType);
        if (!token.isBlank()) {
            lookup.putIfAbsent(token, code);
        }
    }

    private void markStage(UUID jobId, String stage) {
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET stage = :stage,
                    graph_version = :graphVersion,
                    extract_model = :extractModel,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", jobId)
                        .addValue("stage", stage)
                        .addValue("graphVersion", extractionService.graphVersion())
                        .addValue("extractModel", currentExtractionModel())
        );
    }

    private void markSuccess(UUID jobId, UUID sourceFileId, UUID docId, Map<String, Object> summary) throws JsonProcessingException {
        String summaryJson = objectMapper.writeValueAsString(summary);
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'success',
                    stage = 'completed',
                    output_summary_json = CAST(:summary AS jsonb),
                    error_message = NULL,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", jobId).addValue("summary", summaryJson)
        );
        jdbcTemplate.update(
                """
                INSERT INTO graph_sync_state (
                    source_file_id, doc_id, graph_store, graph_version, graph_document_key,
                    status, last_build_job_id, last_built_at, metadata_json, created_at, updated_at
                ) VALUES (
                    :sourceFileId, :docId, :graphStore, :graphVersion, :docKey,
                    'success', :jobId, CURRENT_TIMESTAMP, CAST(:summary AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (source_file_id)
                DO UPDATE SET
                    doc_id = EXCLUDED.doc_id,
                    graph_store = EXCLUDED.graph_store,
                    graph_version = EXCLUDED.graph_version,
                    graph_document_key = EXCLUDED.graph_document_key,
                    status = 'success',
                    last_build_job_id = EXCLUDED.last_build_job_id,
                    last_built_at = CURRENT_TIMESTAMP,
                    error_message = NULL,
                    metadata_json = EXCLUDED.metadata_json,
                    updated_at = CURRENT_TIMESTAMP
                """,
                new MapSqlParameterSource()
                        .addValue("sourceFileId", sourceFileId)
                        .addValue("docId", docId)
                        .addValue("docKey", docId.toString())
                        .addValue("jobId", jobId)
                        .addValue("graphStore", graphStoreClient.provider())
                        .addValue("graphVersion", extractionService.graphVersion())
                        .addValue("summary", summaryJson)
        );
    }

    private void markFailed(UUID jobId, UUID sourceFileId, String message) {
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'failed',
                    stage = 'failed',
                    error_message = :message,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", jobId).addValue("message", truncate(message, 1000))
        );
        jdbcTemplate.update(
                """
                UPDATE graph_sync_state
                SET status = 'failed',
                    error_message = :message,
                    updated_at = CURRENT_TIMESTAMP
                WHERE source_file_id = :sourceFileId
                """,
                new MapSqlParameterSource("sourceFileId", sourceFileId).addValue("message", truncate(message, 1000))
        );
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Object> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String jsonArray(Object raw) throws JsonProcessingException {
        if (raw instanceof List<?> list) {
            return objectMapper.writeValueAsString(list);
        }
        if (raw == null) {
            return "[]";
        }
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) {
            return "[]";
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            objectMapper.readTree(value);
            return value;
        }
        return objectMapper.writeValueAsString(List.of(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeTypeToken(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private List<String> extractChunkKeywords(String title, String content) {
        String text = (stringValue(title) + " " + stringValue(content)).replaceAll("\\s+", " ");
        if (text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9（）()《》/\\-]{1,24}")
                .matcher(text);
        while (matcher.find()) {
            String token = normalizeTypeToken(matcher.group());
            if (token.length() < 2 || token.length() > 24 || token.matches("^[0-9]+$")) {
                continue;
            }
            if (List.of("以及", "或者", "进行", "通过", "相关", "包括", "其中", "按照", "根据", "要求").contains(token)) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : Integer.compare(right.getKey().length(), left.getKey().length());
                })
                .limit(12)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> buildStructureSignals(
            String docTitle,
            String chunkTitle,
            String content,
            Map<String, Object> chunkMetadata,
            List<Object> kuTitles,
            List<Object> kuSubjects,
            List<Object> kuIndicators,
            List<Object> kuFields
    ) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        addStructureSignal(signals, docTitle);
        addStructureSignal(signals, chunkTitle);
        addMetadataSignals(signals, chunkMetadata);
        stringifySignals(kuSubjects, 24).forEach(value -> addStructureSignal(signals, value));
        stringifySignals(kuIndicators, 24).forEach(value -> addStructureSignal(signals, value));
        stringifySignals(kuTitles, 24).forEach(value -> addStructureSignal(signals, value));
        for (Object field : kuFields) {
            collectKnownKeywordFields(signals, field);
        }
        if (signals.size() < 8) {
            extractChunkKeywords(chunkTitle, content).forEach(value -> addStructureSignal(signals, value));
        }
        return signals.stream().limit(32).toList();
    }

    private void addMetadataSignals(LinkedHashSet<String> signals, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        collectKnownKeywordFields(signals, metadata);
        addPathSignals(signals, metadata.get("relativePath"));
        addPathSignals(signals, metadata.get("path"));
        addPathSignals(signals, metadata.get("sourcePath"));
        addPathSignals(signals, metadata.get("filePath"));
        addStructureSignal(signals, metadata.get("sectionTitle"));
        addStructureSignal(signals, metadata.get("heading"));
    }

    private void collectKnownKeywordFields(LinkedHashSet<String> signals, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey()).toLowerCase(Locale.ROOT);
                Object fieldValue = entry.getValue();
                if (List.of("keywords", "keyword", "keyphrases", "keyPhrases", "tags", "subjects", "subject",
                        "indicators", "indicator", "topics", "topic").contains(key)) {
                    addAnySignal(signals, fieldValue);
                } else if (fieldValue instanceof Map<?, ?> || fieldValue instanceof Collection<?>) {
                    collectKnownKeywordFields(signals, fieldValue);
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectKnownKeywordFields(signals, item);
            }
        }
    }

    private void addAnySignal(LinkedHashSet<String> signals, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addAnySignal(signals, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                addAnySignal(signals, item);
            }
            return;
        }
        addStructureSignal(signals, value);
    }

    private void addPathSignals(LinkedHashSet<String> signals, Object rawPath) {
        String path = stringValue(rawPath);
        if (path.isBlank()) {
            return;
        }
        for (String segment : path.split("[/\\\\]+")) {
            String clean = segment.replaceFirst("(?i)\\.(docx?|pdf|xlsx?|pptx?|txt|md)$", "");
            addStructureSignal(signals, clean);
        }
    }

    private List<String> stringifySignals(List<Object> raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : raw) {
            addStructureSignal(values, item);
            if (values.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    private void addStructureSignal(LinkedHashSet<String> signals, Object raw) {
        String value = stringValue(raw)
                .replaceAll("\\s+", "")
                .replaceAll("(?i)\\.(docx?|pdf|xlsx?|pptx?|txt|md)$", "");
        if (value.isBlank()) {
            return;
        }
        String key = normalizeTypeToken(value);
        if (key.length() < 2 || key.length() > 40 || key.matches("^[0-9]+$")) {
            return;
        }
        if (List.of("以及", "或者", "进行", "通过", "相关", "包括", "其中", "按照", "根据", "要求",
                "项目", "系统", "平台", "内容", "其他", "功能", "关键词").contains(key)) {
            return;
        }
        signals.add(value.length() > 40 ? value.substring(0, 40) : value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
