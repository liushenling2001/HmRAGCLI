package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.domain.BatchIngestJob;
import com.hmrag.backend.domain.DataSource;
import com.hmrag.backend.domain.FileIngestJob;
import com.hmrag.backend.domain.ScanJob;
import com.hmrag.backend.domain.SourceFile;
import com.hmrag.backend.repository.BatchIngestJobRepository;
import com.hmrag.backend.repository.DataSourceRepository;
import com.hmrag.backend.repository.FileIngestJobRepository;
import com.hmrag.backend.repository.ScanJobRepository;
import com.hmrag.backend.repository.SourceFileRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    public static final List<String> INGEST_STAGES = List.of("probe", "parse", "extract", "vector");

    private static final List<String> FAST_ACCEPT_EXTENSIONS = List.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".txt", ".md"
    );

    private final DataSourceRepository dataSourceRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ScanJobRepository scanJobRepository;
    private final BatchIngestJobRepository batchIngestJobRepository;
    private final FileIngestJobRepository fileIngestJobRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final DowngradeApprovalService downgradeApprovalService;
    private final IngestTaskRegistry ingestTaskRegistry;
    @SuppressWarnings("unused")
    private final FileHashService fileHashService;
    private final Set<UUID> mutatingDataSourceIds = ConcurrentHashMap.newKeySet();

    public DataSourceService(
            DataSourceRepository dataSourceRepository,
            SourceFileRepository sourceFileRepository,
            ScanJobRepository scanJobRepository,
            BatchIngestJobRepository batchIngestJobRepository,
            FileIngestJobRepository fileIngestJobRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            DowngradeApprovalService downgradeApprovalService,
            IngestTaskRegistry ingestTaskRegistry,
            FileHashService fileHashService
    ) {
        this.dataSourceRepository = dataSourceRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.scanJobRepository = scanJobRepository;
        this.batchIngestJobRepository = batchIngestJobRepository;
        this.fileIngestJobRepository = fileIngestJobRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.downgradeApprovalService = downgradeApprovalService;
        this.ingestTaskRegistry = ingestTaskRegistry;
        this.fileHashService = fileHashService;
    }

    @Transactional
    public ApiDtos.DataSourceItem create(ApiDtos.CreateDataSourceRequest request) {
        dataSourceRepository.findByRootPath(request.rootPath()).ifPresent(existing -> {
            throw new IllegalArgumentException("Data source already exists for root path");
        });
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sourceName", request.sourceName())
                .addValue("sourceType", request.sourceType())
                .addValue("rootPath", request.rootPath())
                .addValue("includePatterns", jsonString(request.includePatterns() == null ? List.of("*") : request.includePatterns()))
                .addValue("excludePatterns", jsonString(request.excludePatterns() == null ? List.of() : request.excludePatterns()))
                .addValue("recursive", request.recursive() == null || request.recursive())
                .addValue("status", "active")
                .addValue("metadataJson", jsonString(request.metadata() == null ? new HashMap<>() : request.metadata()));
        jdbcTemplate.update(
                """
                INSERT INTO data_sources (
                    id, source_name, source_type, root_path,
                    include_patterns, exclude_patterns, recursive, status, metadata_json,
                    created_at, updated_at
                ) VALUES (
                    :id, :sourceName, :sourceType, :rootPath,
                    CAST(:includePatterns AS jsonb), CAST(:excludePatterns AS jsonb), :recursive, :status, CAST(:metadataJson AS jsonb),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                params
        );
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DataSourceItem> list() {
        return dataSourceRepository.findAll().stream().map(this::toDataSourceItem).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.DataSourceItem get(UUID id) {
        return toDataSourceItem(getDataSource(id));
    }

    public void deleteDataSource(UUID id) {
        DataSource dataSource = getDataSource(id);
        log.info("Deleting data source: id={}, name={}", dataSource.getId(), dataSource.getSourceName());
        runWithDataSourceMutationLock(id, () -> executeWithConnectionRetry("deleteDataSource", () -> {
            deleteDataSourceInternal(dataSource);
            return null;
        }));
        log.info("Deleted data source: id={}, name={}", dataSource.getId(), dataSource.getSourceName());
    }

    @Transactional
    public ApiDtos.JobItem startScan(UUID dataSourceId, boolean forceRescan) {
        ScanJob job = new ScanJob();
        job.setDataSourceId(dataSourceId);
        job.setStatus("queued");
        job.setStartedAt(OffsetDateTime.now());
        job = scanJobRepository.save(job);
        return toScanJobItem(job);
    }

    @Transactional
    public ApiDtos.JobItem startIngest(UUID dataSourceId, String mode, boolean reprocessFailed) {
        prepareFilesForIngest(dataSourceId, mode, reprocessFailed);
        List<SourceFile> candidates = sourceFileRepository.findByDataSourceIdAndIngestStatusIn(dataSourceId, List.of("pending", "processing"))
                .stream()
                .filter(this::isAcceptedForBackground)
                .toList();

        BatchIngestJob job = new BatchIngestJob();
        job.setDataSourceId(dataSourceId);
        job.setTriggerType(mode == null || mode.isBlank() ? "incremental" : mode);
        job.setStatus(candidates.isEmpty() ? "success" : "queued");
        job.setStartedAt(OffsetDateTime.now());
        job.setTotalFiles(candidates.size());
        job = batchIngestJobRepository.saveAndFlush(job);

        if (!candidates.isEmpty()) {
            enqueueStageJobs(job.getId(), candidates);
            OffsetDateTime now = OffsetDateTime.now();
            for (SourceFile candidate : candidates) {
                candidate.setIngestStatus("queued");
                candidate.setProcessingStage("parse");
                candidate.setClassificationStatus("success");
                candidate.setParseStatus("pending");
                candidate.setExtractStatus("blocked");
                candidate.setIndexStatus("blocked");
                candidate.setErrorMessage(null);
                candidate.setLastIngestAt(now);
            }
            sourceFileRepository.saveAll(candidates);
        }
        return toBatchJobItem(job);
    }

    @Transactional
    public ApiDtos.JobItem cancelActiveJobs(UUID dataSourceId) {
        DataSource dataSource = getDataSource(dataSourceId);
        log.info("Cancelling active jobs: id={}, name={}", dataSource.getId(), dataSource.getSourceName());
        OffsetDateTime now = OffsetDateTime.now();
        MapSqlParameterSource params = new MapSqlParameterSource("dataSourceId", dataSourceId)
                .addValue("now", now);
        jdbcTemplate.update(
                """
                UPDATE batch_ingest_jobs
                SET status = 'cancelling',
                    finished_at = NULL
                WHERE data_source_id = :dataSourceId
                  AND status IN ('queued', 'running', 'pending')
                """,
                params
        );
        jdbcTemplate.update(
                """
                UPDATE file_ingest_jobs
                SET status = CASE WHEN status = 'running' THEN 'cancelling' ELSE 'cancelled' END,
                    finished_at = CASE WHEN status = 'running' THEN finished_at ELSE COALESCE(finished_at, :now) END,
                    heartbeat_at = :now,
                    error_message = COALESCE(error_message, 'CANCELLED_BY_USER')
                WHERE source_file_id IN (
                    SELECT id FROM source_files WHERE data_source_id = :dataSourceId
                )
                  AND status IN ('blocked', 'pending', 'running')
                """,
                params
        );
        jdbcTemplate.update(
                """
                UPDATE source_files
                SET ingest_status = 'cancelled',
                    processing_stage = CASE WHEN processing_stage IN ('parse', 'extract', 'vector') THEN processing_stage ELSE 'accepted' END,
                    parse_status = CASE WHEN parse_status IN ('running', 'pending', 'blocked') THEN 'cancelled' ELSE parse_status END,
                    extract_status = CASE WHEN extract_status IN ('running', 'pending', 'blocked') THEN 'cancelled' ELSE extract_status END,
                    index_status = CASE WHEN index_status IN ('running', 'pending', 'blocked') THEN 'cancelled' ELSE index_status END,
                    error_message = 'CANCELLED_BY_USER',
                    updated_at = CURRENT_TIMESTAMP
                WHERE data_source_id = :dataSourceId
                  AND ingest_status IN ('queued', 'processing', 'pending')
                """,
                params
        );
        ingestTaskRegistry.cancelByDataSource(dataSourceId);
        BatchIngestJob job = batchIngestJobRepository.findAll().stream()
                .filter(item -> dataSourceId.equals(item.getDataSourceId()))
                .filter(item -> List.of("queued", "running", "pending", "cancelling").contains(item.getStatus()))
                .max(Comparator.comparing(BatchIngestJob::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (job == null) {
            job = new BatchIngestJob();
            job.setDataSourceId(dataSourceId);
            job.setTriggerType("cancel");
            job.setStatus("cancelled");
            job.setStartedAt(now);
            job.setFinishedAt(now);
            job.setTotalFiles(0);
        } else {
            job.setStatus("cancelling");
        }
        return toBatchJobItem(job);
    }

    @Transactional
    public ApiDtos.JobItem approveDegradedProcessing(UUID dataSourceId) {
        DataSource dataSource = getDataSource(dataSourceId);
        downgradeApprovalService.approve(dataSourceId, true, true);
        OffsetDateTime now = OffsetDateTime.now();
        MapSqlParameterSource params = new MapSqlParameterSource("dataSourceId", dataSourceId)
                .addValue("now", now);
        jdbcTemplate.update(
                """
                UPDATE file_ingest_jobs
                SET status = 'pending',
                    finished_at = NULL,
                    heartbeat_at = :now,
                    error_message = NULL
                WHERE source_file_id IN (
                    SELECT id FROM source_files WHERE data_source_id = :dataSourceId
                )
                  AND status = 'needs_approval'
                """,
                params
        );
        jdbcTemplate.update(
                """
                UPDATE source_files
                SET ingest_status = 'queued',
                    parse_status = CASE WHEN parse_status = 'needs_approval' THEN 'pending' ELSE parse_status END,
                    extract_status = CASE WHEN extract_status = 'needs_approval' THEN 'pending' ELSE extract_status END,
                    index_status = CASE WHEN index_status = 'needs_approval' THEN 'pending' ELSE index_status END,
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE data_source_id = :dataSourceId
                  AND ingest_status = 'needs_approval'
                """,
                params
        );
        jdbcTemplate.update(
                """
                UPDATE batch_ingest_jobs
                SET status = 'queued',
                    finished_at = NULL
                WHERE data_source_id = :dataSourceId
                  AND status = 'needs_approval'
                """,
                params
        );
        BatchIngestJob job = batchIngestJobRepository.findAll().stream()
                .filter(item -> dataSourceId.equals(item.getDataSourceId()))
                .filter(item -> List.of("queued", "running", "pending", "needs_approval").contains(item.getStatus()))
                .max(Comparator.comparing(BatchIngestJob::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseGet(() -> {
                    BatchIngestJob created = new BatchIngestJob();
                    created.setDataSourceId(dataSourceId);
                    created.setTriggerType("approve_degraded_processing");
                    created.setStatus("queued");
                    created.setStartedAt(now);
                    return created;
                });
        log.info("Approved degraded processing: id={}, name={}", dataSource.getId(), dataSource.getSourceName());
        return toBatchJobItem(job);
    }

    public boolean isBatchCancellationRequested(UUID batchJobId) {
        if (batchJobId == null) {
            return false;
        }
        return batchIngestJobRepository.findById(batchJobId)
                .map(job -> "cancelling".equals(job.getStatus()) || "cancelled".equals(job.getStatus()))
                .orElse(false);
    }

    public ApiDtos.IndexResetResult resetIndexedData(UUID dataSourceId) {
        DataSource dataSource = getDataSource(dataSourceId);
        log.info("Resetting indexed data: id={}, name={}", dataSource.getId(), dataSource.getSourceName());
        return runWithDataSourceMutationLock(dataSourceId,
                () -> executeWithConnectionRetry("resetIndexedData", () -> resetIndexedDataInternal(dataSource)));
    }

    public boolean isDataSourceMutating(UUID dataSourceId) {
        return dataSourceId != null && mutatingDataSourceIds.contains(dataSourceId);
    }

    private void deleteDataSourceInternal(DataSource dataSource) {
        transactionTemplate.executeWithoutResult(status -> deleteDataSourceDbInternal(dataSource));
        cleanupIndexDirs(dataSource);
    }

    private void deleteDataSourceDbInternal(DataSource dataSource) {
        UUID dataSourceId = dataSource.getId();
        MapSqlParameterSource params = new MapSqlParameterSource("dataSourceId", dataSourceId);
        applyMutationTimeouts();
        quiesceJobsForMutation(dataSourceId);
        jdbcTemplate.update("DELETE FROM file_ingest_jobs WHERE source_file_id IN (SELECT id FROM source_files WHERE data_source_id = :dataSourceId)", params);
        jdbcTemplate.update("DELETE FROM batch_ingest_jobs WHERE data_source_id = :dataSourceId", params);
        jdbcTemplate.update("DELETE FROM scan_jobs WHERE data_source_id = :dataSourceId", params);
        deleteDocumentsByDataSourceInBatches(dataSourceId, 500);
        jdbcTemplate.update("DELETE FROM source_files WHERE data_source_id = :dataSourceId", params);
        jdbcTemplate.update("DELETE FROM data_sources WHERE id = :dataSourceId", params);
    }

    private ApiDtos.IndexResetResult resetIndexedDataInternal(DataSource dataSource) {
        ApiDtos.IndexResetResult dbResult = resetIndexedDataDbInternal(dataSource);
        IndexDirCleanup cleanup = cleanupIndexDirs(dataSource);
        ApiDtos.IndexResetResult result = new ApiDtos.IndexResetResult(
                dbResult.dataSourceId(),
                dbResult.resetFiles(),
                dbResult.deletedDocuments(),
                dbResult.deletedChunks(),
                dbResult.deletedKnowledgeUnits(),
                dbResult.deletedChunkEmbeddings(),
                dbResult.deletedKnowledgeUnitEmbeddings(),
                dbResult.deletedFileIngestJobs(),
                dbResult.deletedBatchIngestJobs(),
                cleanup.deletedDirs(),
                cleanup.errors()
        );
        log.info("Reset indexed data finished: id={}, resetFiles={}, deletedDocuments={}, deletedChunks={}, deletedKnowledgeUnits={}, deletedDirs={}, dirErrors={}",
                dataSource.getId(),
                result.resetFiles(),
                result.deletedDocuments(),
                result.deletedChunks(),
                result.deletedKnowledgeUnits(),
                result.deletedIndexDirs().size(),
                result.indexDirErrors().size());
        return result;
    }

    private ApiDtos.IndexResetResult resetIndexedDataDbInternal(DataSource dataSource) {
        UUID dataSourceId = dataSource.getId();
        MapSqlParameterSource params = new MapSqlParameterSource("dataSourceId", dataSourceId);
        quiesceJobsForMutation(dataSourceId);

        int deletedChunks = countRowsBestEffort(
                """
                SELECT COUNT(*)::int
                FROM chunks c
                WHERE c.doc_id IN (
                    SELECT doc_id
                    FROM source_files
                    WHERE data_source_id = :dataSourceId
                      AND doc_id IS NOT NULL
                )
                """,
                params
        );
        int deletedKnowledgeUnits = countRowsBestEffort(
                """
                SELECT COUNT(*)::int
                FROM knowledge_units ku
                WHERE ku.doc_id IN (
                    SELECT doc_id
                    FROM source_files
                    WHERE data_source_id = :dataSourceId
                      AND doc_id IS NOT NULL
                )
                """,
                params
        );
        int deletedChunkEmbeddings = countRowsBestEffort(
                """
                SELECT COUNT(*)::int
                FROM chunk_embeddings ce
                JOIN chunks c ON c.id = ce.chunk_id
                WHERE c.doc_id IN (
                    SELECT doc_id
                    FROM source_files
                    WHERE data_source_id = :dataSourceId
                      AND doc_id IS NOT NULL
                )
                """,
                params
        );
        int deletedKnowledgeUnitEmbeddings = countRowsBestEffort(
                """
                SELECT COUNT(*)::int
                FROM knowledge_unit_embeddings kue
                JOIN knowledge_units ku ON ku.id = kue.knowledge_unit_id
                WHERE ku.doc_id IN (
                    SELECT doc_id
                    FROM source_files
                    WHERE data_source_id = :dataSourceId
                      AND doc_id IS NOT NULL
                )
                """,
                params
        );

        int deletedFileIngestJobs = executeMaintenanceUpdate(
                """
                DELETE FROM file_ingest_jobs
                WHERE source_file_id IN (
                    SELECT id FROM source_files WHERE data_source_id = :dataSourceId
                )
                """,
                params
        );

        int deletedBatchIngestJobs = executeMaintenanceUpdate(
                "DELETE FROM batch_ingest_jobs WHERE data_source_id = :dataSourceId",
                params
        );

        int deletedDocuments = deleteDocumentsByDataSourceInBatches(dataSourceId, 200);

        int resetFiles = executeMaintenanceUpdate(
                """
                UPDATE source_files
                SET
                    doc_id = NULL,
                    ingest_status = CASE WHEN classification_status = 'success' THEN 'pending' ELSE ingest_status END,
                    processing_stage = CASE WHEN classification_status = 'success' THEN 'accepted' ELSE processing_stage END,
                    parse_status = CASE WHEN classification_status = 'success' THEN 'pending' ELSE parse_status END,
                    extract_status = CASE WHEN classification_status = 'success' THEN 'pending' ELSE extract_status END,
                    index_status = CASE WHEN classification_status = 'success' THEN 'pending' ELSE index_status END,
                    retry_count = CASE WHEN classification_status = 'success' THEN 0 ELSE retry_count END,
                    error_message = CASE WHEN classification_status = 'success' THEN NULL ELSE error_message END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE data_source_id = :dataSourceId
                """,
                params
        );

        return new ApiDtos.IndexResetResult(
                dataSourceId,
                resetFiles,
                deletedDocuments,
                deletedChunks,
                deletedKnowledgeUnits,
                deletedChunkEmbeddings,
                deletedKnowledgeUnitEmbeddings,
                deletedFileIngestJobs,
                deletedBatchIngestJobs,
                List.of(),
                Map.of()
        );
    }

    private void quiesceJobsForMutation(UUID dataSourceId) {
        MapSqlParameterSource params = new MapSqlParameterSource("dataSourceId", dataSourceId);
        jdbcTemplate.update(
                """
                UPDATE batch_ingest_jobs
                SET status = 'cancelling',
                    finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP)
                WHERE data_source_id = :dataSourceId
                  AND status IN ('queued', 'running', 'pending')
                """,
                params
        );
        jdbcTemplate.update(
                """
                UPDATE file_ingest_jobs
                SET status = CASE WHEN status = 'running' THEN 'cancelling' ELSE 'cancelled' END,
                    finished_at = CASE WHEN status = 'running' THEN finished_at ELSE COALESCE(finished_at, CURRENT_TIMESTAMP) END,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    error_message = COALESCE(error_message, 'CANCELLED_BY_USER')
                WHERE source_file_id IN (
                    SELECT id FROM source_files WHERE data_source_id = :dataSourceId
                )
                  AND status IN ('blocked', 'pending', 'running')
                """,
                params
        );
        // Force-close stale "running/cancelling" jobs to avoid permanent stuck state.
        forceCloseStaleRunningJobs(dataSourceId, 30);
        int runningJobs = waitForRunningJobsToDrain(dataSourceId);
        if (runningJobs > 0) {
            String details = runningJobDetails(dataSourceId);
            log.warn("Mutation blocked by running jobs: dataSourceId={}, runningJobs={}", dataSourceId, runningJobs);
            throw new IllegalStateException("当前数据源仍有运行中的任务，无法继续清理/删除。runningJobs="
                    + runningJobs + ", details=" + details);
        }
    }

    private int waitForRunningJobsToDrain(UUID dataSourceId) {
        for (int i = 0; i < 80; i++) {
            forceCloseStaleRunningJobs(dataSourceId, 30);
            Integer running = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)::int
                    FROM file_ingest_jobs fij
                    JOIN source_files sf ON sf.id = fij.source_file_id
                    WHERE sf.data_source_id = :dataSourceId
                      AND fij.status IN ('running', 'cancelling')
                    """,
                    new MapSqlParameterSource("dataSourceId", dataSourceId),
                    Integer.class
            );
            if (running == null || running == 0) {
                return 0;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return running;
            }
        }
        Integer running = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)::int
                FROM file_ingest_jobs fij
                JOIN source_files sf ON sf.id = fij.source_file_id
                WHERE sf.data_source_id = :dataSourceId
                  AND fij.status IN ('running', 'cancelling')
                """,
                new MapSqlParameterSource("dataSourceId", dataSourceId),
                Integer.class
        );
        return running == null ? 0 : running;
    }

    private int forceCloseStaleRunningJobs(UUID dataSourceId, int staleSeconds) {
        return jdbcTemplate.update(
                """
                UPDATE file_ingest_jobs fij
                SET status = 'cancelled',
                    finished_at = COALESCE(fij.finished_at, CURRENT_TIMESTAMP),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    error_message = COALESCE(fij.error_message, 'FORCE_CANCEL_STALE_JOB')
                FROM source_files sf
                WHERE sf.id = fij.source_file_id
                  AND sf.data_source_id = :dataSourceId
                  AND fij.status IN ('running', 'cancelling')
                  AND COALESCE(fij.heartbeat_at, fij.started_at, fij.created_at)
                        < CURRENT_TIMESTAMP - (:staleSeconds * INTERVAL '1 second')
                """,
                new MapSqlParameterSource()
                        .addValue("dataSourceId", dataSourceId)
                        .addValue("staleSeconds", Math.max(5, staleSeconds))
        );
    }

    private String runningJobDetails(UUID dataSourceId) {
        List<String> items = jdbcTemplate.query(
                """
                SELECT fij.id::text AS id,
                       fij.job_stage AS stage,
                       fij.status AS status,
                       COALESCE(fij.heartbeat_at, fij.started_at, fij.created_at) AS hb
                FROM file_ingest_jobs fij
                JOIN source_files sf ON sf.id = fij.source_file_id
                WHERE sf.data_source_id = :dataSourceId
                  AND fij.status IN ('running', 'cancelling')
                ORDER BY COALESCE(fij.heartbeat_at, fij.started_at, fij.created_at) ASC
                LIMIT 10
                """,
                new MapSqlParameterSource("dataSourceId", dataSourceId),
                (rs, rowNum) -> rs.getString("id") + ":" + rs.getString("stage") + ":" + rs.getString("status")
                        + ":" + String.valueOf(rs.getObject("hb"))
        );
        return items.isEmpty() ? "[]" : items.toString();
    }

    private void applyMutationTimeouts() {
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL lock_timeout = '3s'");
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL statement_timeout = '20s'");
    }

    private int deleteDocumentsByDataSourceInBatches(UUID dataSourceId, int batchSize) {
        int totalDeleted = 0;
        int effectiveBatchSize = Math.max(50, batchSize);
        totalDeleted += deleteDocumentsByDocIdBatches(dataSourceId, effectiveBatchSize);
        return totalDeleted;
    }

    private int deleteDocumentsByDocIdBatches(UUID dataSourceId, int batchSize) {
        int totalDeleted = 0;
        while (true) {
            int deleted = executeMaintenanceUpdate(
                    """
                    WITH target_doc_ids AS (
                        SELECT doc_id AS id
                        FROM source_files
                        WHERE data_source_id = :dataSourceId
                          AND doc_id IS NOT NULL
                        LIMIT :batchSize
                    )
                    DELETE FROM documents d
                    USING target_doc_ids td
                    WHERE d.id = td.id
                    """,
                    new MapSqlParameterSource()
                            .addValue("dataSourceId", dataSourceId)
                            .addValue("batchSize", batchSize)
            );
            totalDeleted += deleted;
            if (deleted == 0) {
                break;
            }
        }
        return totalDeleted;
    }

    private int executeMaintenanceUpdate(String sql, MapSqlParameterSource params) {
        Integer updated = executeWithConnectionRetry("maintenanceUpdate", () -> transactionTemplate.execute(status -> {
            applyMutationTimeouts();
            return jdbcTemplate.update(sql, params);
        }));
        if (updated == null) {
            throw new IllegalStateException("维护 SQL 执行失败，未返回影响行数");
        }
        return updated;
    }

    private int countRowsBestEffort(String sql, MapSqlParameterSource params) {
        try {
            Integer count = executeWithConnectionRetry("maintenanceCount", () -> transactionTemplate.execute(status -> {
                applyMutationTimeouts();
                return jdbcTemplate.queryForObject(sql, params, Integer.class);
            }));
            return count == null ? 0 : count;
        } catch (DataAccessException ex) {
            log.warn("Skip maintenance count due to database error: {}", ex.getMessage());
            return 0;
        }
    }

    private <T> T executeWithConnectionRetry(String operation, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessResourceFailureException ex) {
            log.warn("{} failed due to database I/O issue, retrying once. reason={}", operation, ex.getMessage());
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw ex;
            }
            return action.get();
        }
    }

    private <T> T runWithDataSourceMutationLock(UUID dataSourceId, java.util.function.Supplier<T> action) {
        if (!mutatingDataSourceIds.add(dataSourceId)) {
            log.warn("Mutation already in progress: dataSourceId={}", dataSourceId);
            throw new IllegalStateException("当前数据源正在执行维护操作，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            mutatingDataSourceIds.remove(dataSourceId);
        }
    }

    private void runWithDataSourceMutationLock(UUID dataSourceId, Runnable action) {
        runWithDataSourceMutationLock(dataSourceId, () -> {
            action.run();
            return null;
        });
    }

    @Transactional(readOnly = true)
    public ApiDtos.PageResponse<ApiDtos.SourceFileItem> listFiles(UUID dataSourceId, int page, int pageSize) {
        List<SourceFile> files = sourceFileRepository.findByDataSourceIdOrderByFilePathAsc(dataSourceId);
        Map<UUID, List<FileIngestJob>> latestJobsByFile = latestJobsByFile(files);
        List<ApiDtos.SourceFileItem> items = files.stream()
                .map(file -> toSourceFileItem(file, latestJobsByFile.get(file.getId())))
                .toList();
        return new ApiDtos.PageResponse<>(pageList(items, page, pageSize), items.size(), Math.max(page, 1), Math.max(pageSize, 1));
    }

    @Transactional(readOnly = true)
    public ApiDtos.SourceFileItem getFile(UUID sourceFileId) {
        SourceFile file = sourceFileRepository.findById(sourceFileId)
                .orElseThrow(() -> new EntityNotFoundException("Source file not found"));
        return toSourceFileItem(file, latestJobsForFile(sourceFileId));
    }

    @Transactional(readOnly = true)
    public ApiDtos.PageResponse<ApiDtos.FailureItem> listFailures(int page, int pageSize) {
        List<SourceFile> failedFiles = sourceFileRepository.findTop100ByIngestStatusOrderByUpdatedAtDesc("failed");
        Map<UUID, List<FileIngestJob>> latestJobsByFile = latestJobsByFile(failedFiles);
        List<ApiDtos.FailureItem> items = failedFiles.stream()
                .filter(file -> !isOfficeTemporaryFile(file.getFileName()))
                .map(file -> toFailureItem(file, latestJobsByFile.get(file.getId())))
                .toList();
        return new ApiDtos.PageResponse<>(pageList(items, page, pageSize), items.size(), Math.max(page, 1), Math.max(pageSize, 1));
    }

    @Transactional
    public ApiDtos.TempFilesCleanupResult cleanupTemporaryFailures() {
        int cleaned = jdbcTemplate.update(
                """
                UPDATE source_files
                SET
                    ingest_status = 'ignored',
                    processing_stage = 'ignored',
                    classification_status = 'skipped',
                    parse_status = 'skipped',
                    extract_status = 'skipped',
                    index_status = 'skipped',
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ingest_status = 'failed'
                  AND file_name LIKE '~$%'
                """
                ,
                new MapSqlParameterSource()
        );
        return new ApiDtos.TempFilesCleanupResult(cleaned);
    }

    @Transactional(readOnly = true)
    public ApiDtos.OperationsDashboard getDashboard() {
        List<DataSource> dataSources = dataSourceRepository.findAll();
        List<SourceFile> allFiles = sourceFileRepository.findAll();
        Map<UUID, List<FileIngestJob>> latestJobsByFile = latestJobsByFile(allFiles);

        List<ApiDtos.ActiveFileItem> activeFiles = allFiles.stream()
                .map(file -> toActiveFileItem(file, latestJobsByFile.get(file.getId())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ApiDtos.ActiveFileItem::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();

        return new ApiDtos.OperationsDashboard(
                buildOverview(allFiles),
                dataSources.stream().map(source -> toDataSourceCard(source, latestJobsByFile)).toList(),
                activeFiles,
                listJobs(1, 20).items(),
                listFailures(1, 20).items()
        );
    }

    @Transactional(readOnly = true)
    public ApiDtos.PageResponse<ApiDtos.OperationsJobItem> listJobs(int page, int pageSize) {
        List<ApiDtos.OperationsJobItem> jobs = new ArrayList<>();
        jobs.addAll(scanJobRepository.findAll().stream().map(this::toScanOpsJob).toList());
        jobs.addAll(batchIngestJobRepository.findAll().stream().map(this::toBatchOpsJob).toList());
        jobs.sort(Comparator.comparing(ApiDtos.OperationsJobItem::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new ApiDtos.PageResponse<>(pageList(jobs, page, pageSize), jobs.size(), Math.max(page, 1), Math.max(pageSize, 1));
    }

    @Transactional
    public void refreshSourceFileState(UUID sourceFileId) {
        SourceFile file = sourceFileRepository.findById(sourceFileId).orElse(null);
        if (file == null) {
            return;
        }
        List<FileIngestJob> latestJobs = latestJobsForFile(sourceFileId);
        if (latestJobs.isEmpty()) {
            return;
        }
        applyAggregateState(file, latestJobs);
        sourceFileRepository.save(file);
    }

    @Transactional
    public void refreshBatchState(UUID batchJobId) {
        BatchIngestJob batchJob = batchIngestJobRepository.findById(batchJobId).orElse(null);
        if (batchJob == null) {
            return;
        }
        List<FileIngestJob> jobs = fileIngestJobRepository.findByBatchJobId(batchJobId);
        Map<UUID, List<FileIngestJob>> jobsByFile = jobs.stream().collect(Collectors.groupingBy(FileIngestJob::getSourceFileId));
        int queued = 0;
        int running = 0;
        int failed = 0;
        int success = 0;
        int skipped = 0;
        int needsApproval = 0;
        for (List<FileIngestJob> fileJobs : jobsByFile.values()) {
            List<ApiDtos.StageTaskItem> stages = toStageTasks(null, fileJobs);
            if (stages.stream().anyMatch(stage -> "failed".equals(stage.status()))) {
                failed++;
            } else if (stages.stream().anyMatch(stage -> "needs_approval".equals(stage.status()))) {
                needsApproval++;
            } else if (stages.stream().anyMatch(stage -> "cancelled".equals(stage.status()) || "cancelling".equals(stage.status()))) {
                skipped++;
            } else if ("success".equals(findStage(stages, "vector").status())) {
                success++;
            } else if (stages.stream().anyMatch(stage -> "running".equals(stage.status()))) {
                running++;
            } else {
                queued++;
            }
        }
        batchJob.setSuccessFiles(success);
        batchJob.setFailedFiles(failed);
        batchJob.setSkippedFiles(skipped);
        if (running > 0) {
            batchJob.setStatus("running");
            batchJob.setFinishedAt(null);
        } else if (queued > 0) {
            batchJob.setStatus("queued");
            batchJob.setFinishedAt(null);
        } else if (needsApproval > 0) {
            batchJob.setStatus("needs_approval");
            batchJob.setFinishedAt(OffsetDateTime.now());
        } else if (skipped > 0 && success == 0 && failed == 0) {
            batchJob.setStatus("cancelled");
            batchJob.setFinishedAt(OffsetDateTime.now());
        } else if (failed > 0 && success > 0) {
            batchJob.setStatus("partial_failed");
            batchJob.setFinishedAt(OffsetDateTime.now());
        } else if (failed > 0) {
            batchJob.setStatus("failed");
            batchJob.setFinishedAt(OffsetDateTime.now());
        } else {
            batchJob.setStatus("success");
            batchJob.setFinishedAt(OffsetDateTime.now());
        }
        batchIngestJobRepository.save(batchJob);
    }

    private void enqueueStageJobs(UUID batchJobId, List<SourceFile> files) {
        if (files.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<FileIngestJob> jobs = new ArrayList<>(files.size() * INGEST_STAGES.size());
        for (SourceFile file : files) {
            for (String stage : INGEST_STAGES) {
                FileIngestJob job = new FileIngestJob();
                job.setBatchJobId(batchJobId);
                job.setSourceFileId(file.getId());
                job.setJobStage(stage);
                boolean isProbe = "probe".equals(stage);
                job.setStatus(isProbe ? "success" : ("parse".equals(stage) ? "pending" : "blocked"));
                job.setProgressTotal(1);
                job.setProgressCompleted(isProbe ? 1 : 0);
                if (isProbe) {
                    job.setStartedAt(now);
                    job.setFinishedAt(now);
                    job.setHeartbeatAt(now);
                }
                jobs.add(job);
            }
        }
        fileIngestJobRepository.saveAll(jobs);
    }

    private Map<UUID, List<FileIngestJob>> latestJobsByFile(Collection<SourceFile> files) {
        List<UUID> ids = files.stream().map(SourceFile::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<FileIngestJob>> allJobs = fileIngestJobRepository.findBySourceFileIdIn(ids).stream()
                .collect(Collectors.groupingBy(FileIngestJob::getSourceFileId));
        Map<UUID, List<FileIngestJob>> result = new HashMap<>();
        for (SourceFile file : files) {
            List<FileIngestJob> jobs = allJobs.getOrDefault(file.getId(), List.of());
            if (jobs.isEmpty()) {
                result.put(file.getId(), List.of());
                continue;
            }
            FileIngestJob latest = jobs.stream().max(Comparator.comparing(FileIngestJob::getCreatedAt)).orElse(null);
            if (latest == null) {
                result.put(file.getId(), List.of());
                continue;
            }
            UUID latestBatchId = latest.getBatchJobId();
            result.put(file.getId(), jobs.stream()
                    .filter(job -> latestBatchId.equals(job.getBatchJobId()))
                    .sorted(Comparator.comparingInt(job -> stageIndex(job.getJobStage())))
                    .toList());
        }
        return result;
    }

    private List<FileIngestJob> latestJobsForFile(UUID sourceFileId) {
        List<FileIngestJob> jobs = fileIngestJobRepository.findBySourceFileIdOrderByCreatedAtDesc(sourceFileId);
        if (jobs.isEmpty()) {
            return List.of();
        }
        UUID latestBatchId = jobs.get(0).getBatchJobId();
        return jobs.stream()
                .filter(job -> latestBatchId.equals(job.getBatchJobId()))
                .sorted(Comparator.comparingInt(job -> stageIndex(job.getJobStage())))
                .toList();
    }

    @Transactional
    public void runQueuedScan(DataSource dataSource, ScanJob job) {
        runScan(dataSource, job, false);
    }

    private void runScan(DataSource dataSource, ScanJob job, boolean forceRescan) {
        Path root = Path.of(dataSource.getRootPath());
        if (!Files.isDirectory(root)) {
            job.setStatus("failed");
            job.setFinishedAt(OffsetDateTime.now());
            scanJobRepository.save(job);
            return;
        }

        job.setStatus("running");
        scanJobRepository.save(job);

        try {
            doRunScan(dataSource, job, root, forceRescan);
        } catch (Exception ex) {
            log.error("Scan failed: dataSourceId={}, root={}", dataSource.getId(), root, ex);
            job.setStatus("failed");
            job.setFinishedAt(OffsetDateTime.now());
            scanJobRepository.save(job);
            throw new IllegalStateException("Failed to scan directory: " + root, ex);
        }
    }

    private void doRunScan(DataSource dataSource, ScanJob job, Path root, boolean forceRescan) throws IOException {
        Map<String, SourceFile> existing = new HashMap<>();
        for (SourceFile row : sourceFileRepository.findByDataSourceIdOrderByFilePathAsc(dataSource.getId())) {
            existing.put(row.getFilePath(), row);
        }

        Set<String> seenPaths = new LinkedHashSet<>();
        int newFiles = 0;
        int changedFiles = 0;
        int scannedCount = 0;
        try (var stream = dataSource.isRecursive() ? Files.walk(root) : Files.list(root)) {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String absolutePath = path.toAbsolutePath().toString();
                seenPaths.add(absolutePath);
                SourceFile row = existing.get(absolutePath);
                if (row == null) {
                    sourceFileRepository.save(newSourceFile(dataSource.getId(), root, path));
                    newFiles++;
                } else {
                    OffsetDateTime mtime = lastModified(path);
                    long size = fileSize(path);
                    boolean changed = forceRescan || row.getFileSize() == null || row.getFileSize() != size || !mtime.equals(row.getMtime());
                    row.setDiscoverStatus("active");
                    row.setLastScanAt(OffsetDateTime.now());
                    if (changed) {
                        applyFastProbe(row, path);
                        row.setFileSize(size);
                        row.setMtime(mtime);
                        row.setFileHash(null);
                        changedFiles++;
                    }
                    sourceFileRepository.save(row);
                }
                scannedCount++;
                if (scannedCount % 100 == 0) {
                    job.setTotalFiles(scannedCount);
                    job.setNewFiles(newFiles);
                    job.setChangedFiles(changedFiles);
                    scanJobRepository.save(job);
                }
            }
        }

        int missingFiles = 0;
        for (SourceFile row : existing.values()) {
            if (!seenPaths.contains(row.getFilePath())) {
                row.setDiscoverStatus("missing");
                sourceFileRepository.save(row);
                missingFiles++;
            }
        }

        job.setStatus("success");
        job.setTotalFiles(scannedCount);
        job.setNewFiles(newFiles);
        job.setChangedFiles(changedFiles);
        job.setMissingFiles(missingFiles);
        job.setFinishedAt(OffsetDateTime.now());
        scanJobRepository.save(job);
    }

    private void prepareFilesForIngest(UUID dataSourceId, String mode, boolean reprocessFailed) {
        String normalizedMode = mode == null || mode.isBlank() ? "incremental" : mode.toLowerCase();
        for (SourceFile row : sourceFileRepository.findByDataSourceIdOrderByFilePathAsc(dataSourceId)) {
            boolean shouldPrepare = switch (normalizedMode) {
                case "full" -> true;
                case "retry_failed" -> "failed".equals(row.getIngestStatus());
                case "resume" -> List.of("failed", "processing", "queued", "pending").contains(row.getIngestStatus());
                default -> "pending".equals(row.getIngestStatus()) || (reprocessFailed && "failed".equals(row.getIngestStatus()));
            };
            if (!shouldPrepare || "rejected".equals(row.getProcessingStage())) {
                continue;
            }
            row.setIngestStatus("pending");
            row.setProcessingStage("accepted");
            row.setClassificationStatus("success");
            row.setParseStatus("pending");
            row.setExtractStatus("pending");
            row.setIndexStatus("pending");
            row.setErrorMessage(null);
            sourceFileRepository.save(row);
        }
    }

    private SourceFile newSourceFile(UUID dataSourceId, Path root, Path path) {
        SourceFile row = new SourceFile();
        row.setDataSourceId(dataSourceId);
        row.setFilePath(path.toAbsolutePath().toString());
        row.setRelativePath(root.relativize(path).toString());
        row.setFileName(path.getFileName().toString());
        row.setFileExt(extension(path));
        row.setFileSize(fileSize(path));
        row.setMtime(lastModified(path));
        row.setDiscoverStatus("active");
        row.setLastScanAt(OffsetDateTime.now());
        applyFastProbe(row, path);
        return row;
    }

    private DataSource getDataSource(UUID id) {
        return dataSourceRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Data source not found"));
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read file size: " + path, ex);
        }
    }

    private OffsetDateTime lastModified(Path path) {
        try {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()), ZoneOffset.UTC);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read file mtime: " + path, ex);
        }
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx).toLowerCase() : null;
    }

    private boolean isAcceptedForBackground(SourceFile row) {
        return "pending".equals(row.getIngestStatus()) || "processing".equals(row.getIngestStatus());
    }

    private void applyFastProbe(SourceFile row, Path path) {
        if (isOfficeTemporaryFile(path.getFileName().toString())) {
            markIgnoredTemporary(row);
            return;
        }
        String ext = extension(path);
        if (ext == null || !FAST_ACCEPT_EXTENSIONS.contains(ext)) {
            markRejected(row, "UNSUPPORTED_EXTENSION: " + (ext == null ? "(none)" : ext));
            return;
        }
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(8);
            if (header.length == 0) {
                markRejected(row, "EMPTY_FILE");
                return;
            }
            if (!matchesSignature(ext, header)) {
                markRejected(row, "SIGNATURE_MISMATCH: " + ext);
                return;
            }
            row.setIngestStatus("pending");
            row.setProcessingStage("accepted");
            row.setClassificationStatus("success");
            row.setParseStatus("blocked");
            row.setExtractStatus("blocked");
            row.setIndexStatus("blocked");
            row.setErrorMessage(null);
        } catch (Exception ex) {
            markRejected(row, "READ_PROBE_FAILED: " + ex.getMessage());
        }
    }

    private boolean isOfficeTemporaryFile(String fileName) {
        return fileName != null && fileName.startsWith("~$");
    }

    private void markIgnoredTemporary(SourceFile row) {
        row.setIngestStatus("ignored");
        row.setProcessingStage("ignored");
        row.setClassificationStatus("skipped");
        row.setParseStatus("skipped");
        row.setExtractStatus("skipped");
        row.setIndexStatus("skipped");
        row.setErrorMessage(null);
    }

    private void markRejected(SourceFile row, String reason) {
        row.setIngestStatus("failed");
        row.setProcessingStage("rejected");
        row.setClassificationStatus("failed");
        row.setParseStatus("skipped");
        row.setExtractStatus("skipped");
        row.setIndexStatus("skipped");
        row.setErrorMessage(reason);
    }

    private boolean matchesSignature(String ext, byte[] header) {
        String ascii = new String(header);
        return switch (ext) {
            case ".pdf" -> ascii.startsWith("%PDF-");
            case ".docx", ".xlsx" -> isZipHeader(header);
            case ".doc", ".xls" -> isOleHeader(header);
            case ".txt", ".md" -> true;
            default -> false;
        };
    }

    private boolean isZipHeader(byte[] header) {
        return header.length >= 4
                && header[0] == 0x50
                && header[1] == 0x4b
                && header[2] == 0x03
                && header[3] == 0x04;
    }

    private boolean isOleHeader(byte[] header) {
        return header.length >= 8
                && (header[0] & 0xff) == 0xd0
                && (header[1] & 0xff) == 0xcf
                && (header[2] & 0xff) == 0x11
                && (header[3] & 0xff) == 0xe0
                && (header[4] & 0xff) == 0xa1
                && (header[5] & 0xff) == 0xb1
                && (header[6] & 0xff) == 0x1a
                && (header[7] & 0xff) == 0xe1;
    }

    private int estimateWorkUnits(SourceFile file) {
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        int units = (int) Math.ceil(Math.max(1.0, size / 262_144.0));
        return Math.max(1, Math.min(units, 24));
    }

    private ApiDtos.DataSourceItem toDataSourceItem(DataSource row) {
        long total = sourceFileRepository.countByDataSourceId(row.getId());
        long success = sourceFileRepository.countByDataSourceIdAndIngestStatus(row.getId(), "success");
        long failed = sourceFileRepository.countByDataSourceIdAndIngestStatus(row.getId(), "failed");
        long pending = Math.max(0, total - success - failed);
        return new ApiDtos.DataSourceItem(
                row.getId(), row.getSourceName(), row.getSourceType(), row.getRootPath(),
                row.getIncludePatterns(), row.getExcludePatterns(), row.isRecursive(), row.getStatus(),
                row.getMetadataJson(), row.getCreatedAt(), row.getUpdatedAt(),
                total, success, failed, pending
        );
    }

    private ApiDtos.JobItem toScanJobItem(ScanJob row) {
        return new ApiDtos.JobItem(
                row.getId(), row.getDataSourceId(), row.getStatus(),
                row.getTotalFiles(), row.getNewFiles(), row.getChangedFiles(), row.getMissingFiles(),
                0, 0, 0, row.getStartedAt(), row.getFinishedAt()
        );
    }

    private ApiDtos.JobItem toBatchJobItem(BatchIngestJob row) {
        return new ApiDtos.JobItem(
                row.getId(), row.getDataSourceId(), row.getStatus(),
                row.getTotalFiles(), 0, 0, 0,
                row.getSuccessFiles(), row.getFailedFiles(), row.getSkippedFiles(),
                row.getStartedAt(), row.getFinishedAt()
        );
    }

    private ApiDtos.OperationsOverview buildOverview(List<SourceFile> files) {
        List<SourceFile> visibleFiles = files.stream().filter(this::isVisibleAcceptedFile).toList();
        long total = visibleFiles.size();
        long accepted = total;
        long quickPassed = files.stream().filter(file -> "success".equals(firstRoundStatus(file))).count();
        long quickRejected = files.stream().filter(file -> "failed".equals(firstRoundStatus(file))).count();
        long queued = visibleFiles.stream().filter(file -> "queued".equals(file.getIngestStatus())).count();
        long running = visibleFiles.stream().filter(file -> "processing".equals(file.getIngestStatus())).count();
        long ready = visibleFiles.stream().filter(file -> "success".equals(file.getIngestStatus())).count();
        long failed = visibleFiles.stream().filter(file -> "failed".equals(file.getIngestStatus())).count();
        return new ApiDtos.OperationsOverview(dataSourceRepository.count(), total, accepted, quickPassed, quickRejected, queued, running, ready, failed);
    }

    private ApiDtos.DataSourceCard toDataSourceCard(DataSource row, Map<UUID, List<FileIngestJob>> latestJobsByFile) {
        List<SourceFile> files = sourceFileRepository.findByDataSourceIdOrderByFilePathAsc(row.getId());
        List<SourceFile> visibleFiles = files.stream().filter(this::isVisibleAcceptedFile).toList();
        long total = visibleFiles.size();
        long accepted = total;
        long quickPassed = files.stream().filter(file -> "success".equals(firstRoundStatus(file))).count();
        long quickRejected = files.stream().filter(file -> "failed".equals(firstRoundStatus(file))).count();
        long queued = visibleFiles.stream().filter(file -> "queued".equals(file.getIngestStatus())).count();
        long running = visibleFiles.stream().filter(file -> "processing".equals(file.getIngestStatus())).count();
        long ready = visibleFiles.stream().filter(file -> "success".equals(file.getIngestStatus())).count();
        long failed = visibleFiles.stream().filter(file -> "failed".equals(file.getIngestStatus())).count();
        List<ApiDtos.StageAggregateItem> stages = aggregateStages(
                visibleFiles.stream()
                        .map(file -> toStageTasks(file, latestJobsByFile.get(file.getId())))
                        .toList()
        );
        OffsetDateTime lastScanAt = visibleFiles.stream().map(SourceFile::getLastScanAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        OffsetDateTime lastIngestAt = visibleFiles.stream().map(SourceFile::getLastIngestAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new ApiDtos.DataSourceCard(
                row.getId(),
                row.getSourceName(),
                row.getSourceType(),
                row.getRootPath(),
                row.getStatus(),
                total,
                accepted,
                quickPassed,
                quickRejected,
                queued,
                running,
                ready,
                failed,
                stages,
                lastScanAt,
                lastIngestAt
        );
    }

    private ApiDtos.ActiveFileItem toActiveFileItem(SourceFile file, List<FileIngestJob> latestJobs) {
        if (!isVisibleAcceptedFile(file)) {
            return null;
        }
        if (!List.of("queued", "processing", "failed").contains(file.getIngestStatus())) {
            return null;
        }
        DataSource source = dataSourceRepository.findById(file.getDataSourceId()).orElse(null);
        List<ApiDtos.StageTaskItem> stages = toStageTasks(file, latestJobs);
        ErrorInfo error = errorInfo(stages, file.getErrorMessage());
        return new ApiDtos.ActiveFileItem(
                file.getId(),
                file.getDataSourceId(),
                source == null ? null : source.getSourceName(),
                file.getFileName(),
                lifecycleStatus(file, stages),
                currentStage(stages),
                progressPercent(stages),
                error.summary(),
                file.getUpdatedAt()
        );
    }

    private ApiDtos.OperationsJobItem toScanOpsJob(ScanJob row) {
        DataSource source = dataSourceRepository.findById(row.getDataSourceId()).orElse(null);
        int runningFiles = "running".equals(row.getStatus()) ? 1 : 0;
        int queuedFiles = "queued".equals(row.getStatus()) ? 1 : 0;
        int completedFiles = "success".equals(row.getStatus()) ? row.getTotalFiles() : 0;
        int progressPercent = "success".equals(row.getStatus()) ? 100 : 0;
        String summary = switch (row.getStatus()) {
            case "queued" -> "已进入扫描队列";
            case "running" -> "扫描中，已遍历 " + row.getTotalFiles() + " 个文件";
            case "failed" -> "扫描失败";
            default -> "扫描已完成";
        };
        return new ApiDtos.OperationsJobItem(
                row.getId(),
                "scan",
                row.getDataSourceId(),
                source == null ? null : source.getSourceName(),
                row.getStatus(),
                row.getTotalFiles(),
                completedFiles,
                queuedFiles,
                runningFiles,
                0,
                progressPercent,
                summary,
                List.of(),
                row.getStartedAt(),
                row.getFinishedAt()
        );
    }

    private ApiDtos.OperationsJobItem toBatchOpsJob(BatchIngestJob row) {
        DataSource source = dataSourceRepository.findById(row.getDataSourceId()).orElse(null);
        List<FileIngestJob> jobs = fileIngestJobRepository.findByBatchJobId(row.getId());
        Map<UUID, List<FileIngestJob>> byFile = jobs.stream().collect(Collectors.groupingBy(FileIngestJob::getSourceFileId));

        int completedFiles = 0;
        int queuedFiles = 0;
        int runningFiles = 0;
        int failedFiles = 0;
        List<List<ApiDtos.StageTaskItem>> allStageTasks = new ArrayList<>();
        for (List<FileIngestJob> fileJobs : byFile.values()) {
            List<ApiDtos.StageTaskItem> stages = toStageTasks(null, fileJobs);
            allStageTasks.add(stages);
            if (stages.stream().anyMatch(stage -> "failed".equals(stage.status()))) {
                failedFiles++;
            } else if ("success".equals(findStage(stages, "vector").status())) {
                completedFiles++;
            } else if (stages.stream().anyMatch(stage -> "running".equals(stage.status()))) {
                runningFiles++;
            } else {
                queuedFiles++;
            }
        }

        int progressPercent = row.getTotalFiles() == 0 ? 100 : (int) Math.round(((double) completedFiles / row.getTotalFiles()) * 100);
        return new ApiDtos.OperationsJobItem(
                row.getId(),
                "ingest",
                row.getDataSourceId(),
                source == null ? null : source.getSourceName(),
                row.getStatus(),
                row.getTotalFiles(),
                completedFiles,
                queuedFiles,
                runningFiles,
                failedFiles,
                progressPercent,
                currentStageSummary(aggregateStages(allStageTasks)),
                aggregateStages(allStageTasks),
                row.getStartedAt(),
                row.getFinishedAt()
        );
    }

    private ApiDtos.FailureItem toFailureItem(SourceFile file, List<FileIngestJob> latestJobs) {
        DataSource source = dataSourceRepository.findById(file.getDataSourceId()).orElse(null);
        ErrorInfo error = errorInfo(toStageTasks(file, latestJobs), file.getErrorMessage());
        return new ApiDtos.FailureItem(
                file.getId(),
                file.getDataSourceId(),
                source == null ? null : source.getSourceName(),
                file.getFileName(),
                file.getFilePath(),
                file.getFileExt(),
                error.stageLabel(),
                error.summary(),
                error.detail(),
                file.getRetryCount(),
                file.getUpdatedAt()
        );
    }

    private ApiDtos.SourceFileItem toSourceFileItem(SourceFile file, List<FileIngestJob> latestJobs) {
        List<ApiDtos.StageTaskItem> stageTasks = toStageTasks(file, latestJobs);
        ErrorInfo error = errorInfo(stageTasks, file.getErrorMessage());
        return new ApiDtos.SourceFileItem(
                file.getId(),
                file.getDataSourceId(),
                file.getFilePath(),
                file.getRelativePath(),
                file.getFileName(),
                file.getFileExt(),
                file.getFileSize(),
                file.getMtime(),
                file.getDiscoverStatus(),
                firstRoundStatus(file),
                firstRoundStatusLabel(file),
                backgroundStatus(file, stageTasks),
                backgroundStatusLabel(file, stageTasks),
                lifecycleStatus(file, stageTasks),
                lifecycleLabel(file, stageTasks),
                currentStage(stageTasks),
                progressPercent(stageTasks),
                error.stageLabel(),
                error.summary(),
                error.detail(),
                file.getLastScanAt(),
                file.getLastIngestAt(),
                stageTasks
        );
    }

    private boolean isVisibleAcceptedFile(SourceFile file) {
        return file != null
                && !"rejected".equals(file.getProcessingStage())
                && !"ignored".equals(file.getProcessingStage());
    }

    private List<ApiDtos.StageTaskItem> toStageTasks(SourceFile file, List<FileIngestJob> latestJobs) {
        Map<String, FileIngestJob> jobsByStage = latestJobs == null ? Map.of() : latestJobs.stream()
                .collect(Collectors.toMap(FileIngestJob::getJobStage, job -> job, (left, right) -> left, LinkedHashMap::new));

        List<ApiDtos.StageTaskItem> stages = new ArrayList<>();
        for (String stage : INGEST_STAGES) {
            FileIngestJob job = jobsByStage.get(stage);
            if (job != null) {
                stages.add(new ApiDtos.StageTaskItem(
                        stage,
                        job.getStatus(),
                        job.getProgressTotal(),
                        job.getProgressCompleted(),
                        job.getErrorMessage(),
                        job.getHeartbeatAt(),
                        job.getStartedAt(),
                        job.getFinishedAt()
                ));
                continue;
            }

            String status = file == null ? "blocked" : sourceStageStatus(file, stage);
            int total = 1;
            int completed = switch (status) {
                case "success", "skipped", "cancelled" -> total;
                default -> 0;
            };
            String errorMessage = ("failed".equals(status) || "cancelled".equals(status)) && file != null ? file.getErrorMessage() : null;
            stages.add(new ApiDtos.StageTaskItem(stage, status, total, completed, errorMessage, null, null, null));
        }
        return stages;
    }

    private String sourceStageStatus(SourceFile file, String stage) {
        return switch (stage) {
            case "probe" -> file.getClassificationStatus();
            case "parse" -> file.getParseStatus();
            case "extract" -> file.getExtractStatus();
            case "vector" -> file.getIndexStatus();
            default -> "blocked";
        };
    }

    private List<ApiDtos.StageAggregateItem> aggregateStages(List<List<ApiDtos.StageTaskItem>> fileStages) {
        List<ApiDtos.StageAggregateItem> aggregates = new ArrayList<>();
        for (String stageName : INGEST_STAGES) {
            int totalFiles = 0;
            int pendingFiles = 0;
            int runningFiles = 0;
            int successFiles = 0;
            int failedFiles = 0;
            int skippedFiles = 0;
            int totalUnits = 0;
            int completedUnits = 0;
            for (List<ApiDtos.StageTaskItem> stages : fileStages) {
                ApiDtos.StageTaskItem stage = findStage(stages, stageName);
                totalFiles++;
                totalUnits += stage.total();
                completedUnits += Math.min(stage.completed(), stage.total());
                switch (stage.status()) {
                    case "running" -> runningFiles++;
                    case "success" -> successFiles++;
                    case "failed" -> failedFiles++;
                    case "skipped", "cancelled", "needs_approval" -> skippedFiles++;
                    default -> pendingFiles++;
                }
            }
            aggregates.add(new ApiDtos.StageAggregateItem(
                    stageName,
                    totalFiles,
                    pendingFiles,
                    runningFiles,
                    successFiles,
                    failedFiles,
                    skippedFiles,
                    totalUnits,
                    completedUnits
            ));
        }
        return aggregates;
    }

    private ApiDtos.StageTaskItem findStage(List<ApiDtos.StageTaskItem> stages, String stageName) {
        return stages.stream()
                .filter(stage -> stageName.equals(stage.stage()))
                .findFirst()
                .orElse(new ApiDtos.StageTaskItem(stageName, "blocked", 0, 0, null, null, null, null));
    }

    private int progressPercent(List<ApiDtos.StageTaskItem> stages) {
        double completedStages = 0.0;
        for (String stageName : INGEST_STAGES) {
            String status = findStage(stages, stageName).status();
            if ("success".equals(status) || "skipped".equals(status) || "cancelled".equals(status)) {
                completedStages += 1.0;
                continue;
            }
            if ("running".equals(status)) {
                completedStages += 0.5;
            }
            break;
        }
        return (int) Math.round((completedStages * 100.0) / INGEST_STAGES.size());
    }

    private String currentStage(List<ApiDtos.StageTaskItem> stages) {
        return stages.stream()
                .filter(stage -> "running".equals(stage.status()) || "pending".equals(stage.status()) || "blocked".equals(stage.status()))
                .map(ApiDtos.StageTaskItem::stage)
                .findFirst()
                .orElse("completed");
    }

    private String lifecycleStatus(SourceFile file, List<ApiDtos.StageTaskItem> stages) {
        if ("rejected".equals(file.getProcessingStage())) {
            return "build_failed";
        }
        if (stages.stream().anyMatch(stage -> "needs_approval".equals(stage.status()))) {
            return "needs_approval";
        }
        if (stages.stream().anyMatch(stage -> "cancelled".equals(stage.status()) || "cancelling".equals(stage.status()))) {
            return "cancelled";
        }
        if (stages.stream().anyMatch(stage -> "failed".equals(stage.status()))) {
            return "failed";
        }
        if ("success".equals(findStage(stages, "vector").status())) {
            return "fully_ready";
        }
        if (stages.stream().anyMatch(stage -> "running".equals(stage.status()))) {
            return "running";
        }
        if (stages.stream().anyMatch(stage -> "pending".equals(stage.status()) || "blocked".equals(stage.status()))) {
            return "queued";
        }
        return file.getIngestStatus();
    }

    private String lifecycleLabel(SourceFile file, List<ApiDtos.StageTaskItem> stages) {
        return switch (lifecycleStatus(file, stages)) {
            case "build_failed" -> "快速探测失败";
            case "needs_approval" -> "等待人工批准";
            case "cancelled" -> "已停止";
            case "failed" -> "后台处理失败";
            case "fully_ready" -> "完全就绪";
            case "running" -> "后台处理中";
            case "queued" -> "队列中";
            default -> "待处理";
        };
    }

    private String firstRoundStatus(SourceFile file) {
        if ("rejected".equals(file.getProcessingStage()) || "failed".equals(file.getClassificationStatus())) {
            return "failed";
        }
        if ("success".equals(file.getClassificationStatus())) {
            return "success";
        }
        if ("running".equals(file.getClassificationStatus())) {
            return "running";
        }
        return "pending";
    }

    private String firstRoundStatusLabel(SourceFile file) {
        return switch (firstRoundStatus(file)) {
            case "success" -> "可接收";
            case "failed" -> "拒绝接收";
            case "running" -> "快速探测中";
            default -> "待探测";
        };
    }

    private String backgroundStatus(SourceFile file, List<ApiDtos.StageTaskItem> stages) {
        if (!"success".equals(firstRoundStatus(file))) {
            return "blocked";
        }
        if ("needs_approval".equals(lifecycleStatus(file, stages))) {
            return "needs_approval";
        }
        if ("cancelled".equals(lifecycleStatus(file, stages))) {
            return "cancelled";
        }
        if ("failed".equals(lifecycleStatus(file, stages))) {
            return "failed";
        }
        if ("fully_ready".equals(lifecycleStatus(file, stages))) {
            return "success";
        }
        if (stages.stream().anyMatch(stage -> "running".equals(stage.status()))) {
            return "running";
        }
        if (stages.stream().anyMatch(stage -> "pending".equals(stage.status()) || "blocked".equals(stage.status()))) {
            return "queued";
        }
        return "queued";
    }

    private String backgroundStatusLabel(SourceFile file, List<ApiDtos.StageTaskItem> stages) {
        return switch (backgroundStatus(file, stages)) {
            case "success" -> "后台完成";
            case "needs_approval" -> "等待人工批准";
            case "cancelled" -> "后台已停止";
            case "failed" -> "后台失败";
            case "running" -> "后台处理中";
            case "blocked" -> "未进入后台";
            default -> "后台排队中";
        };
    }

    private String currentStageSummary(List<ApiDtos.StageAggregateItem> stages) {
        return stages.stream()
                .filter(stage -> stage.runningFiles() > 0)
                .findFirst()
                .map(stage -> stage.stage() + " running=" + stage.runningFiles())
                .orElseGet(() -> stages.stream()
                        .filter(stage -> stage.skippedFiles() > 0)
                        .findFirst()
                        .map(stage -> stage.stage() + " cancelled=" + stage.skippedFiles())
                        .orElseGet(() -> stages.stream()
                        .filter(stage -> stage.pendingFiles() > 0)
                        .findFirst()
                        .map(stage -> stage.stage() + " queued=" + stage.pendingFiles())
                        .orElse("全部完成")));
    }

    private ErrorInfo errorInfo(List<ApiDtos.StageTaskItem> stages, String fallbackDetail) {
        for (ApiDtos.StageTaskItem stage : stages) {
            if (!"failed".equals(stage.status())) {
                continue;
            }
            String detail = stage.errorMessage() == null ? fallbackDetail : stage.errorMessage();
            return new ErrorInfo(stage.stage(), errorSummary(stage.stage(), detail), detail);
        }
        return new ErrorInfo(null, null, fallbackDetail);
    }

    private String errorSummary(String stage, String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String lowered = detail.toLowerCase();
        if (lowered.startsWith("unsupported_extension")) {
            return "文件类型不支持";
        }
        if (lowered.startsWith("empty_file")) {
            return "空文件";
        }
        if (lowered.startsWith("signature_mismatch")) {
            return "文件签名与扩展名不匹配";
        }
        if (lowered.startsWith("read_probe_failed")) {
            return "文件快速探测失败";
        }
        if (lowered.contains("timeout")) {
            return "阶段执行超时";
        }
        return stageLabel(stage) + "失败";
    }

    private String stageLabel(String stage) {
        return switch (stage) {
            case "probe" -> "探测";
            case "parse" -> "解析";
            case "extract" -> "抽取";
            case "vector" -> "向量";
            default -> "处理";
        };
    }

    private void applyAggregateState(SourceFile file, List<FileIngestJob> latestJobs) {
        List<ApiDtos.StageTaskItem> tasks = toStageTasks(file, latestJobs);
        ApiDtos.StageTaskItem probe = findStage(tasks, "probe");
        ApiDtos.StageTaskItem parse = findStage(tasks, "parse");
        ApiDtos.StageTaskItem extract = findStage(tasks, "extract");
        ApiDtos.StageTaskItem vector = findStage(tasks, "vector");

        file.setClassificationStatus(probe.status());
        file.setParseStatus(parse.status());
        file.setExtractStatus(extract.status());
        file.setIndexStatus(vector.status());
        file.setLastIngestAt(OffsetDateTime.now());

        if (tasks.stream().anyMatch(stage -> "failed".equals(stage.status()))) {
            String failedStage = tasks.stream()
                    .filter(stage -> "failed".equals(stage.status()))
                    .findFirst()
                    .map(ApiDtos.StageTaskItem::stage)
                    .orElse("probe");
            file.setIngestStatus("failed");
            file.setProcessingStage(failedStage);
            file.setErrorMessage(findStage(tasks, failedStage).errorMessage());
            return;
        }

        if ("success".equals(vector.status())) {
            file.setIngestStatus("success");
            file.setProcessingStage("completed");
            file.setErrorMessage(null);
            return;
        }

        if (tasks.stream().anyMatch(stage -> "running".equals(stage.status()))) {
            file.setIngestStatus("processing");
            file.setProcessingStage(currentStage(tasks));
            file.setErrorMessage(null);
            return;
        }

        file.setIngestStatus("queued");
        file.setProcessingStage(currentStage(tasks));
        file.setErrorMessage(null);
    }

    private IndexDirCleanup cleanupIndexDirs(DataSource dataSource) {
        Path root = Path.of(dataSource.getRootPath()).toAbsolutePath().normalize();
        Set<Path> candidates = resolveIndexDirCandidates(dataSource, root);
        List<String> deleted = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            String key = normalized.toString();
            if (!normalized.startsWith(root)) {
                errors.put(key, "路径不在数据源根目录下，已跳过");
                continue;
            }
            if (!Files.exists(normalized)) {
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                errors.put(key, "不是目录，已跳过");
                continue;
            }
            try {
                deleteDirectory(normalized);
                deleted.add(key);
            } catch (Exception ex) {
                errors.put(key, ex.getMessage());
            }
        }
        return new IndexDirCleanup(deleted, errors);
    }

    private Set<Path> resolveIndexDirCandidates(DataSource dataSource, Path root) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        List<String> defaults = List.of(
                ".hmrag-index",
                ".hmrag-vector",
                ".hmrag-cache",
                ".hmrag",
                ".hmrag/index",
                ".hmrag/vector",
                ".hmrag/embeddings",
                ".rag-index",
                ".rag-cache"
        );
        for (String relative : defaults) {
            result.add(root.resolve(relative));
        }

        Map<String, Object> metadata = dataSource.getMetadataJson() == null ? Map.of() : dataSource.getMetadataJson();
        List<String> keys = List.of(
                "indexDir", "indexPath", "indexFolder", "indexRoot",
                "vectorDir", "vectorPath", "vectorStoreDir", "vectorStorePath",
                "embeddingDir", "embeddingPath"
        );
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String pathValue && !pathValue.isBlank()) {
                result.add(resolveDirPath(pathValue, root));
                continue;
            }
            if (value instanceof List<?> listValues) {
                for (Object item : listValues) {
                    if (item instanceof String pathValue && !pathValue.isBlank()) {
                        result.add(resolveDirPath(pathValue, root));
                    }
                }
            }
        }
        return result;
    }

    private Path resolveDirPath(String rawPath, Path root) {
        Path path = Path.of(rawPath.trim());
        if (!path.isAbsolute()) {
            return root.resolve(path).normalize();
        }
        return path.normalize();
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private <T> List<T> pageList(List<T> rows, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(pageSize, 1);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return rows.subList(from, to);
    }

    private int stageIndex(String stage) {
        int idx = INGEST_STAGES.indexOf(stage);
        return idx >= 0 ? idx : INGEST_STAGES.size();
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON", ex);
        }
    }

    private record IndexDirCleanup(List<String> deletedDirs, Map<String, String> errors) {
    }

    private record ErrorInfo(String stage, String summary, String detail) {
        String stageLabel() {
            return stage == null ? null : switch (stage) {
                case "probe" -> "探测";
                case "parse" -> "解析";
                case "extract" -> "抽取";
                case "vector" -> "向量";
                default -> "处理";
            };
        }
    }
}
