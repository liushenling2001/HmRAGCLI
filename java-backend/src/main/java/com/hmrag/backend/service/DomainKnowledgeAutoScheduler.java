package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class DomainKnowledgeAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeAutoScheduler.class);
    private static final List<String> ACTIVE_JOB_STATUSES = List.of("queued", "running", "paused");

    private final DomainDefinitionRepository domainDefinitionRepository;
    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainRefineJobService domainRefineJobService;

    public DomainKnowledgeAutoScheduler(
            DomainDefinitionRepository domainDefinitionRepository,
            DomainRefineJobRepository domainRefineJobRepository,
            DomainRefineJobService domainRefineJobService
    ) {
        this.domainDefinitionRepository = domainDefinitionRepository;
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainRefineJobService = domainRefineJobService;
    }

    @Scheduled(fixedDelayString = "${hmrag.domain-knowledge.auto-refresh-poll-delay-millis:60000}")
    public void scheduleAutoRefreshJobs() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        for (DomainDefinition domain : domainDefinitionRepository.findByAutoRefreshEnabledTrueOrderByPriorityDescCreatedAtAsc()) {
            try {
                if (!shouldAutoRun(domain, now)) {
                    continue;
                }
                enqueueAutoRefine(domain, now);
            } catch (Exception ex) {
                log.error("Failed to schedule auto domain knowledge refine: domainId={}", domain.getId(), ex);
            }
        }
    }

    private boolean shouldAutoRun(DomainDefinition domain, OffsetDateTime now) {
        if (!domain.isAutoRefreshEnabled()) {
            return false;
        }
        if (!isRunnableDomainStatus(domain.getStatus())) {
            return false;
        }
        String normalizedCron = normalizeCron(domain.getAutoRefreshCron());
        if (normalizedCron == null) {
            return false;
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(normalizedCron);
        } catch (IllegalArgumentException ex) {
            log.warn("Skip invalid auto refresh cron: domainId={}, cron={}", domain.getId(), domain.getAutoRefreshCron());
            return false;
        }
        OffsetDateTime previousMinute = now.minusMinutes(1);
        OffsetDateTime nextRun = cron.next(previousMinute);
        if (nextRun == null || !nextRun.truncatedTo(ChronoUnit.MINUTES).equals(now)) {
            return false;
        }
        if (domainRefineJobRepository.existsByDomainIdAndStatusIn(domain.getId(), ACTIVE_JOB_STATUSES)) {
            return false;
        }
        DomainRefineJob lastAutoJob = domainRefineJobRepository
                .findTop1ByDomainIdAndTriggerSourceOrderByCreatedAtDesc(domain.getId(), "auto");
        return lastAutoJob == null
                || !lastAutoJob.getCreatedAt().truncatedTo(ChronoUnit.MINUTES).equals(now);
    }

    private void enqueueAutoRefine(DomainDefinition domain, OffsetDateTime now) {
        ApiDtos.StartDomainRefineRequest request = new ApiDtos.StartDomainRefineRequest(
                "domain_refine",
                "auto",
                trimToNull(domain.getActiveModelProfile()),
                null,
                java.util.Map.of(
                        "scheduledAt", now.toString(),
                        "scheduler", "domain-auto-refresh"
                )
        );
        domainRefineJobService.startDomainRefine(domain.getId(), request);
        log.info("Scheduled auto domain knowledge refine: domainId={}, scheduledAt={}", domain.getId(), now);
    }

    private boolean isRunnableDomainStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            return true;
        }
        String lowered = normalized.toLowerCase();
        return !"archived".equals(lowered) && !"disabled".equals(lowered) && !"deleted".equals(lowered);
    }

    private String normalizeCron(String cron) {
        String trimmed = trimToNull(cron);
        if (trimmed == null) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
