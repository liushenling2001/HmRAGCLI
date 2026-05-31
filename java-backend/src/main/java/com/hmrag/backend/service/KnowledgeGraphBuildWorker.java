package com.hmrag.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeGraphBuildWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphBuildWorker.class);

    private final KnowledgeGraphBuildService knowledgeGraphBuildService;

    public KnowledgeGraphBuildWorker(KnowledgeGraphBuildService knowledgeGraphBuildService) {
        this.knowledgeGraphBuildService = knowledgeGraphBuildService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        try {
            int recovered = knowledgeGraphBuildService.recoverInterruptedJobsOnStartup();
            if (recovered > 0) {
                log.warn("Recovered {} interrupted knowledge graph build job(s) on startup.", recovered);
            }
        } catch (Exception ex) {
            log.error("Knowledge graph startup recovery failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelayString = "${hmrag.knowledge-graph.poll-delay-millis:5000}")
    public void poll() {
        try {
            knowledgeGraphBuildService.runNextBatch();
        } catch (Exception ex) {
            log.error("Knowledge graph build worker failed: {}", ex.getMessage(), ex);
        }
    }
}
