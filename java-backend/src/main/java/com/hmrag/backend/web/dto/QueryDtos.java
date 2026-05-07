package com.hmrag.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QueryDtos {

    private QueryDtos() {
    }

    public record SearchItem(
            String kind,
            String matchType,
            double score,
            UUID docId,
            String sourceFile,
            String sourceFilename,
            String relativePath,
            UUID chunkId,
            UUID unitId,
            String docTitle,
            String docType,
            boolean isDevDoc,
            String docDomain,
            String title,
            String content,
            String snippet,
            Integer pageNo,
            String sourceSpan,
            String subject,
            String indicator,
            List<String> tags
    ) {
    }

    public record DocOverview(
            String summary,
            List<String> sections,
            List<String> keyTopics,
            List<String> keywords,
            List<String> entities,
            String timeRange,
            List<String> conclusions,
            Map<String, Object> metadata
    ) {
    }

    public record DocHit(
            UUID docId,
            String docTitle,
            String sourceFile,
            String sourceFilename,
            String relativePath,
            double score,
            int hitCount,
            DocOverview overview
    ) {
    }

    public record SearchResponse(
            List<DocHit> docHits,
            List<SearchItem> evidenceHits,
            List<SearchItem> items,
            long total,
            int page,
            int pageSize
    ) {
    }

    public record QAQueryRequest(
            @NotBlank String query,
            boolean excludeDevDocs,
            int topK
    ) {
    }

    public record CitationItem(
            UUID docId,
            String sourceFile,
            String sourceFilename,
            String relativePath,
            UUID unitId,
            UUID chunkId,
            String title,
            String sourceSpan,
            Integer pageNo
    ) {
    }

    public record StructuredAnswer(
            String subject,
            String action,
            String constraint,
            String exception,
            String indicator,
            Object value,
            String unitName,
            String time,
            String region,
            List<String> summaryPoints
    ) {
    }

    public record QAQueryResponse(
            String queryType,
            String answer,
            StructuredAnswer structuredAnswer,
            List<CitationItem> citations,
            DocOverview docOverview
    ) {
    }

    public record DocumentOverviewResponse(
            UUID docId,
            String docTitle,
            String sourceFile,
            String sourceFilename,
            String relativePath,
            DocOverview overview
    ) {
    }
}
