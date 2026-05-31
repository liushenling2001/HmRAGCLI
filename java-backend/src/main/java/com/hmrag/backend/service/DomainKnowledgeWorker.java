package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DomainKnowledgeWorker {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeWorker.class);

    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainKnowledgeCompilationService domainKnowledgeCompilationService;
    private final KnowledgeGraphBuildService knowledgeGraphBuildService;
    private final AsyncTaskExecutor domainKnowledgeTaskExecutor;
    private final AppProperties appProperties;

    public DomainKnowledgeWorker(
            DomainRefineJobRepository domainRefineJobRepository,
            DomainKnowledgeCompilationService domainKnowledgeCompilationService,
            KnowledgeGraphBuildService knowledgeGraphBuildService,
            @Qualifier("domainKnowledgeTaskExecutor") AsyncTaskExecutor domainKnowledgeTaskExecutor,
            AppProperties appProperties
    ) {
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainKnowledgeCompilationService = domainKnowledgeCompilationService;
        this.knowledgeGraphBuildService = knowledgeGraphBuildService;
        this.domainKnowledgeTaskExecutor = domainKnowledgeTaskExecutor;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${hmrag.domain-knowledge.poll-delay-millis:3000}")
    public void poll() {
        recoverStaleRunningJobs();
        promoteGraphOptimizedJobs();

        List<DomainRefineJob> queuedJobs = domainRefineJobRepository.findTop10ByStatusOrderByCreatedAtAsc("queued");
        if (queuedJobs.isEmpty()) {
            return;
        }
        for (DomainRefineJob snapshot : queuedJobs) {
            try {
                DomainRefineJob job = domainRefineJobRepository.findById(snapshot.getId()).orElse(null);
                if (job == null || !"queued".equals(job.getStatus())) {
                    continue;
                }
                job.setStatus("running");
                if (job.getStartedAt() == null) {
                    job.setStartedAt(OffsetDateTime.now());
                }
                job.setHeartbeatAt(OffsetDateTime.now());
                domainRefineJobRepository.save(job);
                dispatch(job.getId());
            } catch (Exception ex) {
                log.error("Domain knowledge worker failed to claim job: jobId={}", snapshot.getId(), ex);
            }
        }
    }

    private void recoverStaleRunningJobs() {
        Duration staleThreshold = Duration.ofSeconds(Math.max(30, appProperties.domainKnowledge().staleRunningSeconds()));
        List<DomainRefineJob> activeJobs = domainRefineJobRepository.findTop10ByStatusInOrderByCreatedAtAsc(List.of("running"));
        OffsetDateTime now = OffsetDateTime.now();
        for (DomainRefineJob job : activeJobs) {
            OffsetDateTime heartbeat = job.getHeartbeatAt() != null ? job.getHeartbeatAt()
                    : (job.getStartedAt() != null ? job.getStartedAt() : job.getUpdatedAt());
            if (heartbeat == null) {
                continue;
            }
            if (Duration.between(heartbeat.toInstant(), now.toInstant()).compareTo(staleThreshold) < 0) {
                continue;
            }
            job.setStatus("failed");
            job.setFinishedAt(now);
            job.setErrorMessage("STALE_DOMAIN_KNOWLEDGE_JOB");
            job.setHeartbeatAt(now);
            domainRefineJobRepository.save(job);
            log.warn("Recovered stale domain knowledge job: jobId={}", job.getId());
        }
    }

    private void promoteGraphOptimizedJobs() {
        List<DomainRefineJob> waitingJobs = domainRefineJobRepository.findTop10ByStatusOrderByCreatedAtAsc("waiting_graph_optimization");
        for (DomainRefineJob job : waitingJobs) {
            try {
                UUID graphBatchId = graphOptimizationBatchId(job);
                if (graphBatchId != null && !knowledgeGraphBuildService.graphBatchCompleted(graphBatchId)) {
                    continue;
                }
                Map<String, Object> inputSummary = job.getInputSummaryJson() == null
                        ? new HashMap<>()
                        : new HashMap<>(job.getInputSummaryJson());
                Map<String, Object> graphOptimization = graphBatchId == null
                        ? Map.of("completed", true, "reason", "NO_GRAPH_BATCH")
                        : knowledgeGraphBuildService.graphBatchStatus(graphBatchId);
                inputSummary.put("graphOptimizationStatus", graphOptimization);
                job.setInputSummaryJson(inputSummary);
                job.setStatus("queued");
                job.setHeartbeatAt(OffsetDateTime.now());
                domainRefineJobRepository.save(job);
                log.info("Domain knowledge job graph optimization completed: jobId={}, graphBatchId={}", job.getId(), graphBatchId);
            } catch (Exception ex) {
                log.warn("Failed to check domain graph optimization: jobId={}, error={}", job.getId(), ex.getMessage());
            }
        }
    }

    private UUID graphOptimizationBatchId(DomainRefineJob job) {
        Object raw = job.getInputSummaryJson() == null ? null : job.getInputSummaryJson().get("graphOptimization");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object batch = map.get("graphBatchId");
        if (batch == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(batch));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void dispatch(java.util.UUID jobId) {
        try {
            domainKnowledgeTaskExecutor.execute(() -> runJob(jobId));
        } catch (TaskRejectedException ex) {
            requeue(jobId, "DOMAIN_EXECUTOR_REJECTED");
        } catch (RuntimeException ex) {
            requeue(jobId, "DOMAIN_EXECUTOR_DISPATCH_FAILED");
        }
    }

    private void runJob(java.util.UUID jobId) {
        try {
            touchHeartbeat(jobId);
            domainKnowledgeCompilationService.compileJob(jobId);
        } catch (DomainKnowledgePauseException ex) {
            pause(jobId, ex);
        } catch (Exception ex) {
            fail(jobId, ex);
        }
    }

    private void touchHeartbeat(java.util.UUID jobId) {
        domainRefineJobRepository.findById(jobId).ifPresent(job -> {
            if (!"cancelled".equals(job.getStatus())) {
                job.setHeartbeatAt(OffsetDateTime.now());
                domainRefineJobRepository.save(job);
            }
        });
    }

    private void requeue(java.util.UUID jobId, String message) {
        domainRefineJobRepository.findById(jobId).ifPresent(job -> {
            if (!"completed".equals(job.getStatus()) && !"cancelled".equals(job.getStatus())) {
                job.setStatus("queued");
                job.setErrorMessage(message);
                job.setHeartbeatAt(OffsetDateTime.now());
                domainRefineJobRepository.save(job);
            }
        });
    }

    private void fail(java.util.UUID jobId, Exception ex) {
        domainRefineJobRepository.findById(jobId).ifPresent(job -> {
            if (!"cancelled".equals(job.getStatus())) {
                job.setStatus("failed");
                job.setFinishedAt(OffsetDateTime.now());
                job.setHeartbeatAt(OffsetDateTime.now());
                job.setErrorMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                domainRefineJobRepository.save(job);
            }
        });
        log.error("Domain knowledge job failed: jobId={}", jobId, ex);
    }

    private void pause(java.util.UUID jobId, DomainKnowledgePauseException ex) {
        domainRefineJobRepository.findById(jobId).ifPresent(job -> {
            if (!"cancelled".equals(job.getStatus())) {
                job.setStatus("paused");
                job.setHeartbeatAt(OffsetDateTime.now());
                job.setErrorMessage(ex.getMessage());
                domainRefineJobRepository.save(job);
            }
        });
        log.warn("Domain knowledge job paused: jobId={}, reason={}", jobId, ex.getMessage());
    }
}
