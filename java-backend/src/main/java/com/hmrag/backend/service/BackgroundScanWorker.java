package com.hmrag.backend.service;

import com.hmrag.backend.domain.ScanJob;
import com.hmrag.backend.domain.DataSource;
import com.hmrag.backend.repository.ScanJobRepository;
import com.hmrag.backend.repository.DataSourceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BackgroundScanWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundScanWorker.class);

    private final ScanJobRepository scanJobRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;

    public BackgroundScanWorker(
            ScanJobRepository scanJobRepository,
            DataSourceRepository dataSourceRepository,
            DataSourceService dataSourceService
    ) {
        this.scanJobRepository = scanJobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.dataSourceService = dataSourceService;
    }

    @Scheduled(fixedDelay = 2000L)
    public void poll() {
        List<ScanJob> jobs = scanJobRepository.findTop20ByStatusInOrderByCreatedAtAsc(List.of("queued"));
        if (jobs.isEmpty()) {
            return;
        }
        for (ScanJob job : jobs) {
            try {
                DataSource dataSource = dataSourceRepository.findById(job.getDataSourceId())
                        .orElseThrow(() -> new EntityNotFoundException("Data source not found"));
                dataSourceService.runQueuedScan(dataSource, job);
            } catch (Exception ex) {
                log.error("Background scan failed: jobId={}, dataSourceId={}", job.getId(), job.getDataSourceId(), ex);
            }
        }
    }
}
