package com.hmrag.backend.web;

import com.hmrag.backend.service.KnowledgeGraphBuildService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphBuildService knowledgeGraphBuildService;

    public KnowledgeGraphController(KnowledgeGraphBuildService knowledgeGraphBuildService) {
        this.knowledgeGraphBuildService = knowledgeGraphBuildService;
    }

    @GetMapping("/build-jobs")
    public List<Map<String, Object>> listBuildJobs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return knowledgeGraphBuildService.listJobs(status, limit);
    }

    @GetMapping("/view")
    public Map<String, Object> graphView(@RequestParam(defaultValue = "50") int targetEntities) {
        return knowledgeGraphBuildService.graphView(targetEntities);
    }

    @GetMapping("/stats")
    public Map<String, Object> graphStats() {
        return knowledgeGraphBuildService.graphStats();
    }

    @PostMapping("/source-files/{sourceFileId}/rebuild")
    public Map<String, Object> rebuild(@PathVariable UUID sourceFileId) {
        boolean queued = knowledgeGraphBuildService.enqueueManual(sourceFileId);
        return Map.of("sourceFileId", sourceFileId, "queued", queued);
    }

    @PostMapping("/build-jobs/{jobId}/retry")
    public Map<String, Object> retryJob(@PathVariable UUID jobId) {
        return knowledgeGraphBuildService.retryFailedJob(jobId);
    }

    @PostMapping("/build-jobs/backfill-missing")
    public Map<String, Object> backfillMissing(
            @RequestParam(required = false) UUID dataSourceId,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return knowledgeGraphBuildService.enqueueMissingIndexedFiles(limit, dataSourceId);
    }

    @PostMapping("/build-jobs/batch")
    public Map<String, Object> batchBuild(
            @RequestParam(required = false) UUID dataSourceId,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "false") boolean rebuildSuccess
    ) {
        return knowledgeGraphBuildService.enqueueIndexedFiles(limit, dataSourceId, rebuildSuccess, rebuildSuccess ? "batch_rebuild" : "batch_missing");
    }

    @GetMapping("/fusion-jobs")
    public List<Map<String, Object>> listFusionJobs(@RequestParam(defaultValue = "20") int limit) {
        return knowledgeGraphBuildService.listFusionJobs(limit);
    }

    @PostMapping("/fusion-jobs/start")
    public Map<String, Object> startFusion(@RequestParam(required = false) UUID graphBatchId) {
        return knowledgeGraphBuildService.startFusion(graphBatchId);
    }
}
