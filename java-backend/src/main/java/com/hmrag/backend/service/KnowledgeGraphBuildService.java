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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        int updated = jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'queued',
                    stage = 'queued',
                    graph_batch_id = :graphBatchId,
                    trigger_source = 'batch_rebuild',
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
                new MapSqlParameterSource("jobId", jobId).addValue("graphBatchId", graphBatchId)
        );
        return updated > 0;
    }

    public void runNextBatch() {
        if (!isEnabled()) {
            return;
        }
        recoverStaleJobs();
        int batchSize = Math.max(1, appProperties.knowledgeGraph().batchSize());
        for (int i = 0; i < batchSize; i++) {
            Map<String, Object> job = claimNextJob();
            if (job == null) {
                return;
            }
            runJob(job);
        }
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
            Map<String, Object> localGraph = extractionService.extract(docId, sourceFileId);
            localGraph.put("fusionBatchId", graphBatchId.toString());
            saveLocalGraph(jobId, localGraph);
            markStage(jobId, "writing_global_graph");
            Map<String, Object> summary = graphStoreClient.writeLocalGraph(localGraph);
            markSuccess(jobId, sourceFileId, docId, summary);
        } catch (Exception ex) {
            markFailed(jobId, sourceFileId, ex.getMessage());
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

    private void markStage(UUID jobId, String stage) {
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET stage = :stage,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", jobId).addValue("stage", stage)
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

    private void recoverStaleJobs() {
        int staleSeconds = Math.max(60, appProperties.knowledgeGraph().staleRunningSeconds());
        jdbcTemplate.update(
                """
                UPDATE graph_build_jobs
                SET status = 'queued',
                    stage = 'queued',
                    error_message = 'STALE_GRAPH_JOB_RECOVERED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'running'
                  AND heartbeat_at < CURRENT_TIMESTAMP - (:staleSeconds * INTERVAL '1 second')
                """,
                new MapSqlParameterSource("staleSeconds", staleSeconds)
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

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
