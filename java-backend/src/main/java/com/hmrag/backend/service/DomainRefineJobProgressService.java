package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DomainRefineJobProgressService {

    private final DomainRefineJobRepository domainRefineJobRepository;

    public DomainRefineJobProgressService(DomainRefineJobRepository domainRefineJobRepository) {
        this.domainRefineJobRepository = domainRefineJobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProgress(UUID jobId, String phase, Map<String, Object> extras) {
        DomainRefineJob job = requireJob(jobId);
        Map<String, Object> summary = new LinkedHashMap<>(
                job.getOutputSummaryJson() == null ? Map.of() : job.getOutputSummaryJson()
        );
        summary.put("phase", phase);
        if (extras != null && !extras.isEmpty()) {
            summary.putAll(extras);
        }
        job.setHeartbeatAt(OffsetDateTime.now());
        job.setOutputSummaryJson(summary);
        domainRefineJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isCancelled(UUID jobId) {
        return domainRefineJobRepository.findById(jobId)
                .map(job -> "cancelled".equalsIgnoreCase(job.getStatus()))
                .orElse(true);
    }

    private DomainRefineJob requireJob(UUID jobId) {
        return domainRefineJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("精炼任务不存在: " + jobId));
    }
}

