package com.hmrag.backend.service;

import com.hmrag.backend.domain.FileIngestJob;
import com.hmrag.backend.domain.SourceFile;
import com.hmrag.backend.repository.BatchIngestJobRepository;
import com.hmrag.backend.repository.FileIngestJobRepository;
import com.hmrag.backend.repository.SourceFileRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class BackgroundIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundIngestWorker.class);
    private static final List<String> ACTIVE_STATUSES = List.of("blocked", "pending", "running", "cancelling");
    private static final Duration STALE_RUNNING_HEARTBEAT = Duration.ofSeconds(120);

    private final FileIngestJobRepository fileIngestJobRepository;
    private final BatchIngestJobRepository batchIngestJobRepository;
    private final SourceFileRepository sourceFileRepository;
    private final DataSourceService dataSourceService;
    private final DocumentPipelineService documentPipelineService;
    private final KnowledgeGraphBuildService knowledgeGraphBuildService;
    private final AsyncTaskExecutor ingestTaskExecutor;
    private final IngestTaskRegistry ingestTaskRegistry;

    public BackgroundIngestWorker(
            FileIngestJobRepository fileIngestJobRepository,
            BatchIngestJobRepository batchIngestJobRepository,
            SourceFileRepository sourceFileRepository,
            DataSourceService dataSourceService,
            DocumentPipelineService documentPipelineService,
            KnowledgeGraphBuildService knowledgeGraphBuildService,
            @Qualifier("ingestTaskExecutor") AsyncTaskExecutor ingestTaskExecutor,
            IngestTaskRegistry ingestTaskRegistry
    ) {
        this.fileIngestJobRepository = fileIngestJobRepository;
        this.batchIngestJobRepository = batchIngestJobRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.dataSourceService = dataSourceService;
        this.documentPipelineService = documentPipelineService;
        this.knowledgeGraphBuildService = knowledgeGraphBuildService;
        this.ingestTaskExecutor = ingestTaskExecutor;
        this.ingestTaskRegistry = ingestTaskRegistry;
    }

    @Scheduled(fixedDelay = 2000L)
    public void poll() {
        List<FileIngestJob> activeJobs = fileIngestJobRepository.findByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES);
        if (activeJobs.isEmpty()) {
            return;
        }
        Map<String, FileIngestJob> indexed = new HashMap<>();
        Map<UUID, String> batchStatus = new HashMap<>();
        for (var batch : batchIngestJobRepository.findAllById(activeJobs.stream().map(FileIngestJob::getBatchJobId).toList())) {
            batchStatus.put(batch.getId(), batch.getStatus());
        }
        for (FileIngestJob job : activeJobs) {
            indexed.put(key(job.getBatchJobId().toString(), job.getSourceFileId().toString(), job.getJobStage()), job);
        }

        for (FileIngestJob snapshot : activeJobs) {
            try {
                FileIngestJob job = fileIngestJobRepository.findById(snapshot.getId()).orElse(null);
                if (job == null) {
                    continue;
                }

                String bs = batchStatus.get(job.getBatchJobId());
                if (isTerminalBatch(bs)) {
                    if (!List.of("success", "failed", "skipped").contains(job.getStatus())) {
                        job.setStatus("skipped");
                        job.setFinishedAt(OffsetDateTime.now());
                        job.setErrorMessage("SKIPPED_TERMINAL_BATCH");
                        fileIngestJobRepository.save(job);
                    }
                    touchState(job.getSourceFileId(), job.getBatchJobId());
                    continue;
                }

                SourceFile file = sourceFileRepository.findById(job.getSourceFileId()).orElse(null);
                if (file == null) {
                    continue;
                }
                if (recoverStaleRunningJob(job, file)) {
                    touchState(job.getSourceFileId(), job.getBatchJobId());
                    continue;
                }
                if (dataSourceService.isDataSourceMutating(file.getDataSourceId())) {
                    continue;
                }
                if (!dependenciesReady(job, indexed)) {
                    if ("skipped".equals(job.getStatus()) || "cancelled".equals(job.getStatus())) {
                        touchState(job.getSourceFileId(), job.getBatchJobId());
                    }
                    continue;
                }
                dispatch(job, file);
            } catch (ObjectOptimisticLockingFailureException ignored) {
                // Concurrent deletion/update is expected when user deletes a data source during polling.
            } catch (DataIntegrityViolationException ex) {
                // Parent batch/source row may be deleted concurrently; skip this snapshot safely.
            } catch (DataAccessException ex) {
                log.warn("Background poll skipped a job due to SQL error: {}", ex.getMessage());
            } catch (RuntimeException ex) {
                log.error("Background poll job failed unexpectedly: {}", ex.getMessage(), ex);
            }
        }
    }

    private boolean recoverStaleRunningJob(FileIngestJob job, SourceFile file) {
        if (!"running".equals(job.getStatus()) && !"cancelling".equals(job.getStatus())) {
            return false;
        }
        if (ingestTaskRegistry.isRunning(job.getId())) {
            return false;
        }
        OffsetDateTime hb = job.getHeartbeatAt() != null ? job.getHeartbeatAt()
                : (job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
        if (hb == null) {
            return false;
        }
        if (Duration.between(hb.toInstant(), Instant.now()).compareTo(STALE_RUNNING_HEARTBEAT) < 0) {
            return false;
        }

        String reason = "STALE_JOB_RECOVERED_AFTER_SQL_ERROR";
        if ("cancelling".equals(job.getStatus()) || dataSourceService.isBatchCancellationRequested(job.getBatchJobId())) {
            cancel(job, file, reason);
        } else {
            file.setErrorMessage(reason);
            fail(job, reason);
        }
        log.warn("Recovered stale ingest job: jobId={}, stage={}, status={}, hb={}",
                job.getId(), job.getJobStage(), job.getStatus(), hb);
        return true;
    }

    private boolean dependenciesReady(FileIngestJob job, Map<String, FileIngestJob> indexed) {
        int stageIndex = DataSourceService.INGEST_STAGES.indexOf(job.getJobStage());
        if (stageIndex <= 0) {
            return true;
        }
        String previousStage = DataSourceService.INGEST_STAGES.get(stageIndex - 1);
        FileIngestJob dependency = indexed.get(key(job.getBatchJobId().toString(), job.getSourceFileId().toString(), previousStage));
        if (dependency == null) {
            dependency = fileIngestJobRepository
                    .findByBatchJobIdAndSourceFileIdAndJobStage(job.getBatchJobId(), job.getSourceFileId(), previousStage)
                    .orElse(null);
        }
        if (dependency == null) {
            return false;
        }
        if ("success".equals(dependency.getStatus())) {
            return true;
        }
        if ("failed".equals(dependency.getStatus())) {
            job.setStatus("skipped");
            job.setProgressCompleted(0);
            job.setFinishedAt(OffsetDateTime.now());
            job.setErrorMessage("SKIPPED_AFTER_" + previousStage.toUpperCase());
            fileIngestJobRepository.save(job);
        }
        return false;
    }

    private void execute(FileIngestJob job, SourceFile file) {
        if (Thread.currentThread().isInterrupted()) {
            cancel(job, file, "CANCELLED_BY_USER");
            return;
        }
        if (dataSourceService.isBatchCancellationRequested(job.getBatchJobId()) || "cancelling".equals(job.getStatus()) || "cancelled".equals(job.getStatus())) {
            cancel(job, file, "CANCELLED_BY_USER");
            return;
        }
        job.setStatus("running");
        if (job.getStartedAt() == null) {
            job.setStartedAt(OffsetDateTime.now());
        }
        job.setHeartbeatAt(OffsetDateTime.now());
        if (job.getProgressTotal() <= 0) {
            job.setProgressTotal(1);
        }
        job.setProgressCompleted(Math.max(0, Math.min(job.getProgressCompleted(), job.getProgressTotal())));
        file.setIngestStatus("processing");
        file.setProcessingStage(job.getJobStage());
        switch (job.getJobStage()) {
            case "probe" -> file.setClassificationStatus("running");
            case "parse" -> file.setParseStatus("running");
            case "extract" -> file.setExtractStatus("running");
            case "vector" -> file.setIndexStatus("running");
            default -> {
            }
        }
        fileIngestJobRepository.save(job);
        sourceFileRepository.save(file);
        completeStage(job, file);
        fileIngestJobRepository.save(job);
        sourceFileRepository.save(file);
    }

    private void completeStage(FileIngestJob job, SourceFile file) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new TaskCancelledException("CANCELLED_BY_USER");
            }
            if (dataSourceService.isBatchCancellationRequested(job.getBatchJobId()) || "cancelling".equals(job.getStatus()) || "cancelled".equals(job.getStatus())) {
                throw new TaskCancelledException("CANCELLED_BY_USER");
            }
            job.setHeartbeatAt(OffsetDateTime.now());
            switch (job.getJobStage()) {
                case "probe" -> {
                    reportProgress(job, 1, 1);
                    file.setClassificationStatus("success");
                    file.setProcessingStage("probe");
                }
                case "parse" -> documentPipelineService.materializeParseArtifacts(file, (completed, total) -> reportProgress(job, completed, total));
                case "extract" -> {
                    documentPipelineService.materializeKnowledgeUnit(file, (completed, total) -> reportProgress(job, completed, total));
                }
                case "vector" -> {
                    documentPipelineService.materializeEmbeddings(file, (completed, total) -> reportProgress(job, completed, total));
                    knowledgeGraphBuildService.enqueueAfterIndex(file);
                }
                default -> {
                }
            }
            job.setStatus("success");
            job.setProgressCompleted(Math.max(job.getProgressTotal(), 1));
            job.setFinishedAt(OffsetDateTime.now());
            job.setErrorMessage(null);
        } catch (TaskCancelledException ex) {
            cancel(job, file, ex.getMessage());
        } catch (ManualApprovalRequiredException ex) {
            needsApproval(job, file, ex.getMessage());
        } catch (Exception ex) {
            String message = formatStageError(job.getJobStage(), ex);
            file.setErrorMessage(message);
            fail(job, message);
        }
    }

    private void reportProgress(FileIngestJob job, int completed, int total) {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
        if (dataSourceService.isBatchCancellationRequested(job.getBatchJobId()) || "cancelling".equals(job.getStatus()) || "cancelled".equals(job.getStatus())) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        job.setProgressTotal(safeTotal);
        job.setProgressCompleted(safeCompleted);
        job.setHeartbeatAt(OffsetDateTime.now());
        fileIngestJobRepository.save(job);
    }

    private void cancel(FileIngestJob job, SourceFile file, String message) {
        clearInterruptedForJdbc();
        job.setStatus("cancelled");
        job.setErrorMessage(message);
        job.setHeartbeatAt(OffsetDateTime.now());
        job.setFinishedAt(OffsetDateTime.now());
        file.setIngestStatus("cancelled");
        file.setErrorMessage(message);
        switch (job.getJobStage()) {
            case "parse" -> file.setParseStatus("cancelled");
            case "extract" -> file.setExtractStatus("cancelled");
            case "vector" -> file.setIndexStatus("cancelled");
            default -> {
            }
        }
        fileIngestJobRepository.save(job);
    }

    private void needsApproval(FileIngestJob job, SourceFile file, String message) {
        clearInterruptedForJdbc();
        job.setStatus("needs_approval");
        job.setErrorMessage(message);
        job.setHeartbeatAt(OffsetDateTime.now());
        job.setFinishedAt(OffsetDateTime.now());
        file.setIngestStatus("needs_approval");
        file.setErrorMessage(message);
        switch (job.getJobStage()) {
            case "parse" -> file.setParseStatus("needs_approval");
            case "extract" -> file.setExtractStatus("needs_approval");
            case "vector" -> file.setIndexStatus("needs_approval");
            default -> {
            }
        }
        fileIngestJobRepository.save(job);
    }

    private void fail(FileIngestJob job, String errorMessage) {
        clearInterruptedForJdbc();
        job.setStatus("failed");
        job.setErrorMessage(errorMessage);
        job.setHeartbeatAt(OffsetDateTime.now());
        job.setFinishedAt(OffsetDateTime.now());
        fileIngestJobRepository.save(job);
    }

    private int estimateWorkUnits(SourceFile file) {
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        int units = (int) Math.ceil(Math.max(1.0, size / 262_144.0));
        return Math.max(1, Math.min(units, 24));
    }

    private String key(String batchId, String fileId, String stage) {
        return batchId + ":" + fileId + ":" + stage;
    }

    private String formatStageError(String stage, Exception ex) {
        Throwable root = rootCause(ex);
        String base = root.getMessage() == null || root.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : root.getMessage().replaceAll("\\s+", " ").trim();
        if (root instanceof SQLException sql) {
            if ("23502".equals(sql.getSQLState())) {
                return stage.toUpperCase() + "_DATA_CONSTRAINT: 缺少必填字段，无法写入数据库";
            }
            if ("23505".equals(sql.getSQLState())) {
                return stage.toUpperCase() + "_DUPLICATE: 数据重复，无法写入数据库";
            }
            return stage.toUpperCase() + "_SQL_ERROR: " + truncate(base, 180);
        }
        return stage.toUpperCase() + "_FAILED: " + truncate(base, 180);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private boolean isTerminalBatch(String status) {
        return "success".equals(status)
                || "failed".equals(status)
                || "partial_failed".equals(status)
                || "cancelling".equals(status)
                || "cancelled".equals(status);
    }

    private void dispatch(FileIngestJob job, SourceFile file) {
        if (ingestTaskRegistry.isRunning(job.getId())) {
            return;
        }
        if (!ingestTaskRegistry.register(job.getId(), file.getDataSourceId())) {
            return;
        }
        try {
            Future<?> future = ingestTaskExecutor.submit(() -> {
                try {
                    FileIngestJob latestJob = fileIngestJobRepository.findById(job.getId()).orElse(null);
                    SourceFile latestFile = sourceFileRepository.findById(job.getSourceFileId()).orElse(null);
                    if (latestJob == null || latestFile == null) {
                        return;
                    }
                    if (!ACTIVE_STATUSES.contains(latestJob.getStatus())) {
                        return;
                    }
                    execute(latestJob, latestFile);
                } finally {
                    ingestTaskRegistry.complete(job.getId());
                    try {
                        clearInterruptedForJdbc();
                        touchState(job.getSourceFileId(), job.getBatchJobId());
                    } catch (DataAccessException ignored) {
                        // State refresh is best-effort; next poll will reconcile.
                    }
                }
            });
            ingestTaskRegistry.attachFuture(job.getId(), future);
        } catch (TaskRejectedException ex) {
            ingestTaskRegistry.complete(job.getId());
            onDispatchRejected(job, file, ex);
        } catch (RuntimeException ex) {
            ingestTaskRegistry.complete(job.getId());
            onDispatchRejected(job, file, ex);
        }
    }

    private void onDispatchRejected(FileIngestJob job, SourceFile file, Exception ex) {
        log.error("Ingest task dispatch rejected: jobId={}, stage={}, reason={}", job.getId(), job.getJobStage(), ex.getMessage());
        try {
            clearInterruptedForJdbc();
            FileIngestJob latestJob = fileIngestJobRepository.findById(job.getId()).orElse(job);
            SourceFile latestFile = sourceFileRepository.findById(job.getSourceFileId()).orElse(file);
            if ("running".equals(latestJob.getStatus())) {
                latestJob.setStatus("failed");
            } else if (!"cancelled".equals(latestJob.getStatus())) {
                latestJob.setStatus("pending");
            }
            latestJob.setHeartbeatAt(OffsetDateTime.now());
            latestJob.setErrorMessage("EXECUTOR_REJECTED_RETRY");
            fileIngestJobRepository.save(latestJob);
            latestFile.setErrorMessage("EXECUTOR_REJECTED_RETRY");
            sourceFileRepository.save(latestFile);
            touchState(latestJob.getSourceFileId(), latestJob.getBatchJobId());
        } catch (DataAccessException dataEx) {
            log.error("Failed to persist dispatch rejection state: {}", dataEx.getMessage(), dataEx);
        }
    }

    private void touchState(UUID sourceFileId, UUID batchJobId) {
        dataSourceService.refreshSourceFileState(sourceFileId);
        dataSourceService.refreshBatchState(batchJobId);
    }

    private void clearInterruptedForJdbc() {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
        }
    }
}
