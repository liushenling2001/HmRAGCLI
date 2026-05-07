package com.hmrag.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateDataSourceRequest(
            @NotBlank @Size(max = 255) String sourceName,
            @NotBlank @Size(max = 50) String sourceType,
            @NotBlank @Size(max = 2000) String rootPath,
            List<String> includePatterns,
            List<String> excludePatterns,
            Boolean recursive,
            Map<String, Object> metadata
    ) {
    }

    public record StartScanRequest(boolean forceRescan) {
    }

    public record StartIngestRequest(String mode, boolean reprocessFailed) {
    }

    public record IndexResetResult(
            UUID dataSourceId,
            int resetFiles,
            int deletedDocuments,
            int deletedChunks,
            int deletedKnowledgeUnits,
            int deletedChunkEmbeddings,
            int deletedKnowledgeUnitEmbeddings,
            int deletedFileIngestJobs,
            int deletedBatchIngestJobs,
            List<String> deletedIndexDirs,
            Map<String, String> indexDirErrors
    ) {
    }

    public record DataSourceItem(
            UUID id,
            String sourceName,
            String sourceType,
            String rootPath,
            List<String> includePatterns,
            List<String> excludePatterns,
            boolean recursive,
            String status,
            Map<String, Object> metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long totalFiles,
            long successFiles,
            long failedFiles,
            long pendingFiles
    ) {
    }

    public record StageTaskItem(
            String stage,
            String status,
            int total,
            int completed,
            String errorMessage,
            OffsetDateTime heartbeatAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
    }

    public record StageAggregateItem(
            String stage,
            int totalFiles,
            int pendingFiles,
            int runningFiles,
            int successFiles,
            int failedFiles,
            int skippedFiles,
            int totalUnits,
            int completedUnits
    ) {
    }

    public record OperationsOverview(
            long totalDataSources,
            long totalFiles,
            long acceptedFiles,
            long quickPassedFiles,
            long quickRejectedFiles,
            long queuedFiles,
            long runningFiles,
            long readyFiles,
            long failedFiles
    ) {
    }

    public record DataSourceCard(
            UUID id,
            String sourceName,
            String sourceType,
            String rootPath,
            String status,
            long totalFiles,
            long acceptedFiles,
            long quickPassedFiles,
            long quickRejectedFiles,
            long queuedFiles,
            long runningFiles,
            long readyFiles,
            long failedFiles,
            List<StageAggregateItem> stages,
            OffsetDateTime lastScanAt,
            OffsetDateTime lastIngestAt
    ) {
    }

    public record ActiveFileItem(
            UUID id,
            UUID dataSourceId,
            String dataSourceName,
            String fileName,
            String lifecycleStatus,
            String currentStage,
            int progressPercent,
            String errorSummary,
            OffsetDateTime updatedAt
    ) {
    }

    public record OperationsJobItem(
            UUID id,
            String jobKind,
            UUID dataSourceId,
            String dataSourceName,
            String status,
            int totalFiles,
            int completedFiles,
            int queuedFiles,
            int runningFiles,
            int failedFiles,
            int progressPercent,
            String currentStageSummary,
            List<StageAggregateItem> stages,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
    }

    public record FailureItem(
            UUID id,
            UUID dataSourceId,
            String dataSourceName,
            String fileName,
            String filePath,
            String fileExt,
            String failedStage,
            String errorSummary,
            String errorDetail,
            int retryCount,
            OffsetDateTime updatedAt
    ) {
    }

    public record TempFilesCleanupResult(
            int cleanedFiles
    ) {
    }

    public record OperationsDashboard(
            OperationsOverview overview,
            List<DataSourceCard> dataSources,
            List<ActiveFileItem> activeFiles,
            List<OperationsJobItem> recentJobs,
            List<FailureItem> recentFailures
    ) {
    }

    public record JobItem(
            UUID id,
            UUID dataSourceId,
            String status,
            int totalFiles,
            int newFiles,
            int changedFiles,
            int missingFiles,
            int successFiles,
            int failedFiles,
            int skippedFiles,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
    }

    public record SourceFileItem(
            UUID id,
            UUID dataSourceId,
            String filePath,
            String relativePath,
            String fileName,
            String fileExt,
            Long fileSize,
            OffsetDateTime mtime,
            String discoverStatus,
            String quickStatus,
            String quickStatusLabel,
            String backgroundStatus,
            String backgroundStatusLabel,
            String lifecycleStatus,
            String lifecycleLabel,
            String currentStage,
            int progressPercent,
            String errorStage,
            String errorSummary,
            String errorDetail,
            OffsetDateTime lastScanAt,
            OffsetDateTime lastIngestAt,
            List<StageTaskItem> stageTasks
    ) {
    }

    public record CreateDomainDefinitionRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10000) String description,
            @Size(max = 10000) String goal,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            List<String> includeDataSources,
            List<String> excludeDataSources,
            Integer priority,
            Boolean autoRefreshEnabled,
            @Size(max = 100) String autoRefreshCron,
            @Size(max = 100) String activeModelProfile,
            @Size(max = 50) String status,
            @Size(max = 100) String createdBy,
            Map<String, Object> metadata
    ) {
    }

    public record UpdateDomainDefinitionRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10000) String description,
            @Size(max = 10000) String goal,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            List<String> includeDataSources,
            List<String> excludeDataSources,
            Integer priority,
            Boolean autoRefreshEnabled,
            @Size(max = 100) String autoRefreshCron,
            @Size(max = 100) String activeModelProfile,
            @Size(max = 50) String status,
            @Size(max = 100) String createdBy,
            Map<String, Object> metadata
    ) {
    }

    public record CreateTopicDefinitionRequest(
            UUID parentTopicId,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10000) String description,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            Integer priority,
            @Size(max = 50) String status,
            Map<String, Object> metadata
    ) {
    }

    public record UpdateTopicDefinitionRequest(
            UUID parentTopicId,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10000) String description,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            Integer priority,
            @Size(max = 50) String status,
            Map<String, Object> metadata
    ) {
    }

    public record StartDomainRefineRequest(
            @Size(max = 50) String jobType,
            @Size(max = 50) String triggerSource,
            @Size(max = 100) String modelProfile,
            Map<String, Object> scopeSnapshot,
            Map<String, Object> inputSummary
    ) {
    }

    public record StartTopicRefineRequest(
            @Size(max = 50) String jobType,
            @Size(max = 50) String triggerSource,
            @Size(max = 100) String modelProfile,
            Map<String, Object> scopeSnapshot,
            Map<String, Object> inputSummary
    ) {
    }

    public record DomainDefinitionItem(
            UUID id,
            String name,
            String description,
            String goal,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            List<String> includeDataSources,
            List<String> excludeDataSources,
            int priority,
            boolean autoRefreshEnabled,
            String autoRefreshCron,
            String activeModelProfile,
            String status,
            String createdBy,
            Map<String, Object> metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record TopicDefinitionItem(
            UUID id,
            UUID domainId,
            UUID parentTopicId,
            String name,
            String description,
            Map<String, Object> scopeRules,
            List<String> seedQueries,
            int priority,
            String status,
            Map<String, Object> metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record DomainRefineJobItem(
            UUID id,
            String jobType,
            UUID domainId,
            UUID topicId,
            String status,
            String triggerSource,
            String modelProfile,
            boolean hasMemoryPack,
            Map<String, Object> scopeSnapshot,
            Map<String, Object> inputSummary,
            Map<String, Object> outputSummary,
            String errorMessage,
            OffsetDateTime heartbeatAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record DomainMemoryPackItem(
            UUID id,
            UUID domainId,
            UUID topicId,
            UUID refineJobId,
            String artifactType,
            String status,
            String triggerSource,
            String title,
            String summary,
            List<String> keyPoints,
            List<String> evidenceRefs,
            Map<String, Object> sourceSnapshot,
            Map<String, Object> structuredContent,
            String contentMarkdown,
            String modelProfile,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record DomainCandidateItem(
            UUID id,
            String name,
            String description,
            List<String> keywords,
            List<String> evidenceRefs,
            Map<String, Object> sourceSnapshot,
            String status,
            String triggerSource,
            String reviewNote,
            UUID acceptedDomainId,
            OffsetDateTime discoveryWindowStart,
            OffsetDateTime discoveryWindowEnd,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record DiscoverDomainCandidatesRequest(
            Integer lookbackHours,
            Integer maxDocuments,
            Integer maxCandidates,
            String triggerSource,
            OffsetDateTime discoveryWindowStart,
            OffsetDateTime discoveryWindowEnd
    ) {
    }

    public record AcceptDomainCandidateRequest(
            @Size(max = 255) String nameOverride,
            @Size(max = 1000) String note,
            Boolean startRefineAfterAccept
    ) {
    }

    public record RejectDomainCandidateRequest(
            @Size(max = 1000) String note
    ) {
    }

    public record DomainCandidateDiscoveryControlItem(
            boolean configEnabled,
            boolean runningEnabled,
            boolean withinWindow,
            boolean pausedByUser,
            int windowStartHour,
            int windowEndHour,
            int lookbackHours,
            int maxDocuments,
            int maxCandidates,
            int minDocuments,
            int minHoursBetweenRuns,
            OffsetDateTime lastAutoRunAt,
            OffsetDateTime currentCursorWindowStart,
            OffsetDateTime currentCursorWindowEnd,
            OffsetDateTime coverageCompletedAt
    ) {
    }

    public record DomainEvidenceItem(
            String evidenceRef,
            String evidenceType,
            UUID resourceId,
            UUID docId,
            UUID chunkId,
            String title,
            String snippet,
            String sourceFile,
            Integer pageNo
    ) {
    }

    public record DomainEvidenceContextResponse(
            String evidenceRef,
            String evidenceType,
            UUID resourceId,
            UUID docId,
            UUID chunkId,
            String title,
            String content,
            String context,
            String sourceFile,
            Integer pageNo
    ) {
    }

    public record UpdateDomainMemoryPackReviewRequest(
            @NotBlank @Size(max = 50) String status,
            @Size(max = 1000) String note,
            @Size(max = 100) String reviewedBy
    ) {
    }

    public record DomainSetupAssistantMessage(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 4000) String content
    ) {
    }

    public record DomainSetupAssistantRequest(
            @NotBlank @Size(max = 255) String name,
            List<DomainSetupAssistantMessage> history
    ) {
    }

    public record DomainSetupAssistantResponse(
            String question,
            String goal,
            String description,
            List<String> seedQueries,
            List<String> excludeTerms,
            String currentDimension,
            List<String> coveredDimensions,
            String nextDimension,
            boolean ready,
            boolean llmBacked,
            String reason
    ) {
    }

    public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {
    }
}
