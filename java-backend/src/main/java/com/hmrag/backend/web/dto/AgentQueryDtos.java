package com.hmrag.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentQueryDtos {

    private AgentQueryDtos() {
    }

    public record SearchFilters(
            UUID dataSourceId,
            List<String> docTypes,
            String dateFrom,
            String dateTo
    ) {
    }

    public record AgentSearchRequest(
            @NotBlank String query,
            boolean excludeDevDocs,
            String hop,
            Integer page,
            Integer pageSize,
            Integer topKDocs,
            Integer topKEvidencePerDoc,
            String recallMode,
            String rerankModel,
            Boolean includeOverview,
            Boolean includeRawChunk,
            Boolean debug,
            Boolean async,
            SearchFilters filters
    ) {
    }

    public record SearchPlanRequest(
            @NotBlank String query,
            boolean excludeDevDocs,
            String hop,
            Integer topKDocs,
            Integer topKEvidencePerDoc,
            String recallMode,
            String rerankModel,
            Boolean includeOverview,
            Boolean includeRawChunk,
            SearchFilters filters
    ) {
    }

    public record SearchPlan(
            UUID planId,
            String query,
            boolean excludeDevDocs,
            String hop,
            int topKDocs,
            int topKEvidencePerDoc,
            String recallMode,
            String rerankModel,
            boolean includeOverview,
            boolean includeRawChunk,
            SearchFilters filters,
            OffsetDateTime createdAt
    ) {
    }

    public record SearchPlanResponse(
            SearchPlan plan
    ) {
    }

    public record SearchExecuteRequest(
            UUID planId,
            Integer page,
            Integer pageSize,
            Boolean debug,
            Boolean async
    ) {
    }

    public record SearchTrace(
            String query,
            String normalizedQuery,
            String hop,
            String recallMode,
            String rerankModel,
            long tookMs,
            Map<String, Object> filters,
            Map<String, Object> scoreBreakdown
    ) {
    }

    public record AgentSearchResponse(
            UUID taskId,
            String taskStatus,
            QueryDtos.SearchResponse result,
            SearchTrace trace
    ) {
    }

    public record TaskStatusResponse(
            UUID taskId,
            String status,
            int progressPercent,
            String message,
            AgentSearchResponse result,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ChunkItem(
            UUID chunkId,
            int chunkNo,
            String chunkType,
            String title,
            Integer pageNo,
            Integer startOffset,
            Integer endOffset,
            Integer tokenCount,
            String snippet,
            String content
    ) {
    }

    public record DocumentChunksResponse(
            UUID docId,
            int page,
            int pageSize,
            long total,
            List<ChunkItem> items
    ) {
    }

    public record AgentAnswerRequest(
            @NotBlank String query,
            boolean excludeDevDocs,
            Integer topK,
            String hop,
            SearchFilters filters,
            Boolean includeOverview
    ) {
    }

    public record AgentAnswerResponse(
            String answer,
            QueryDtos.StructuredAnswer structuredAnswer,
            List<QueryDtos.CitationItem> citations,
            List<UUID> usedDocIds,
            double confidence,
            List<String> unansweredParts,
            QueryDtos.DocOverview docOverview,
            SearchTrace trace
    ) {
    }
}

