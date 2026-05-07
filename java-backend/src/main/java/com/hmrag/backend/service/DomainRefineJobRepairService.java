package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.repository.DomainMemoryPackRepository;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DomainRefineJobRepairService {

    private static final Logger log = LoggerFactory.getLogger(DomainRefineJobRepairService.class);

    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainMemoryPackRepository domainMemoryPackRepository;

    public DomainRefineJobRepairService(
            DomainRefineJobRepository domainRefineJobRepository,
            DomainMemoryPackRepository domainMemoryPackRepository
    ) {
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainMemoryPackRepository = domainMemoryPackRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void repairOnStartup() {
        repairCompletedJobsWithoutPack();
    }

    @Scheduled(fixedDelayString = "${hmrag.domain-knowledge.repair-poll-delay-millis:300000}")
    @Transactional
    public void repairOnSchedule() {
        repairCompletedJobsWithoutPack();
    }

    private void repairCompletedJobsWithoutPack() {
        List<DomainRefineJob> completedJobs = domainRefineJobRepository.findTop100ByStatusOrderByCreatedAtDesc("completed");
        if (completedJobs.isEmpty()) {
            return;
        }
        int repaired = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (DomainRefineJob job : completedJobs) {
            if (domainMemoryPackRepository.existsByRefineJobId(job.getId())) {
                continue;
            }
            job.setStatus("failed");
            job.setHeartbeatAt(now);
            job.setFinishedAt(job.getFinishedAt() == null ? now : job.getFinishedAt());
            if (job.getErrorMessage() == null || job.getErrorMessage().isBlank()) {
                job.setErrorMessage("DOMAIN_MEMORY_PACK_MISSING");
            }
            domainRefineJobRepository.save(job);
            repaired++;
        }
        if (repaired > 0) {
            log.warn("Repaired completed domain refine jobs without memory pack: count={}", repaired);
        }
    }
}
