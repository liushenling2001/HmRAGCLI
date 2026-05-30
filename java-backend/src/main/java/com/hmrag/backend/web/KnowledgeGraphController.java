package com.hmrag.backend.web;

import com.hmrag.backend.service.KnowledgeGraphBuildService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping("/runtime-settings")
    public Map<String, Object> runtimeSettings() {
        return knowledgeGraphBuildService.runtimeSettings();
    }

    @PostMapping("/runtime-settings/auto-build-after-index")
    public Map<String, Object> updateAutoBuildAfterIndex(@RequestParam boolean enabled) {
        return knowledgeGraphBuildService.updateAutoBuildAfterIndex(enabled);
    }

    @GetMapping("/view")
    public Map<String, Object> graphView(@RequestParam(defaultValue = "50") int targetEntities) {
        return knowledgeGraphBuildService.graphView(targetEntities);
    }

    @GetMapping("/view/top-connected")
    public Map<String, Object> topConnectedGraphView(@RequestParam(defaultValue = "60") int targetEntities) {
        return knowledgeGraphBuildService.topConnectedGraphView(targetEntities);
    }

    @GetMapping("/stats")
    public Map<String, Object> graphStats() {
        return knowledgeGraphBuildService.graphStats();
    }

    @GetMapping("/quality")
    public Map<String, Object> graphQuality() {
        return knowledgeGraphBuildService.graphQuality();
    }

    @GetMapping("/entities")
    public Map<String, Object> entityList(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return knowledgeGraphBuildService.entityList(search, limit);
    }

    @GetMapping("/entities/{entityId}")
    public Map<String, Object> entityDetail(
            @PathVariable String entityId,
            @RequestParam(defaultValue = "300") int connectionLimit
    ) {
        return knowledgeGraphBuildService.entityDetail(entityId, connectionLimit);
    }

    @PostMapping("/entities/{entityId}/enrich")
    public Map<String, Object> enrichEntity(
            @PathVariable String entityId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return knowledgeGraphBuildService.enqueueEntityEnrichment(entityId, limit);
    }

    @GetMapping("/entity-type-templates")
    public List<Map<String, Object>> listEntityTypeTemplates() {
        return knowledgeGraphBuildService.listEntityTypeTemplates();
    }

    @PostMapping("/entity-type-templates")
    public Map<String, Object> saveEntityTypeTemplates(@RequestBody List<Map<String, Object>> templates) {
        return knowledgeGraphBuildService.saveEntityTypeTemplates(templates);
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
            @RequestParam(defaultValue = "false") boolean rebuildSuccess,
            @RequestParam(defaultValue = "skeleton") String extractionDepth,
            @RequestParam(defaultValue = "document") String scopeType,
            @RequestParam(required = false) String scopeKey
    ) {
        String trigger = "enrichment".equalsIgnoreCase(extractionDepth) ? "batch_enrichment" : (rebuildSuccess ? "batch_rebuild" : "batch_skeleton");
        return knowledgeGraphBuildService.enqueueIndexedFiles(limit, dataSourceId, rebuildSuccess, trigger, extractionDepth, scopeType, scopeKey);
    }

    @GetMapping("/fusion-jobs")
    public List<Map<String, Object>> listFusionJobs(@RequestParam(defaultValue = "20") int limit) {
        return knowledgeGraphBuildService.listFusionJobs(limit);
    }

    @PostMapping("/fusion-jobs/start")
    public Map<String, Object> startFusion(@RequestParam(required = false) UUID graphBatchId) {
        return knowledgeGraphBuildService.startFusion(graphBatchId);
    }

    @PostMapping("/governance/attributes/start")
    public Map<String, Object> startAttributeGovernance() {
        return knowledgeGraphBuildService.startAttributeGovernance();
    }

    @GetMapping("/governance/attributes/candidate-clusters")
    public Map<String, Object> attributeCandidateClusters(@RequestParam(defaultValue = "100") int limit) {
        return knowledgeGraphBuildService.attributeCandidateClusters(limit);
    }

    @PostMapping("/governance/attributes/candidate-clusters/apply")
    public Map<String, Object> applyAttributeCandidateClusters(
            @RequestParam(defaultValue = "0.85") double minConfidence,
            @RequestParam(defaultValue = "100") int maxClusters,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return knowledgeGraphBuildService.applyAttributeCandidateClusters(minConfidence, maxClusters, dryRun);
    }

    @PostMapping("/governance/structure/start")
    public Map<String, Object> startStructureEnhancement() {
        return knowledgeGraphBuildService.startStructureEnhancement();
    }

    @PostMapping("/fusion-jobs/state/start")
    public Map<String, Object> startStateFusion(@RequestParam(required = false) UUID graphBatchId) {
        return knowledgeGraphBuildService.startStateFusion(graphBatchId);
    }

    @PostMapping("/fusion-jobs/transitions/start")
    public Map<String, Object> startTransitionBuild(@RequestParam(required = false) UUID graphBatchId) {
        return knowledgeGraphBuildService.startTransitionBuild(graphBatchId);
    }
}
