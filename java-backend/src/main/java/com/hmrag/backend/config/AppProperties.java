package com.hmrag.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hmrag")
public record AppProperties(
        Ingest ingest,
        Scan scan,
        Llm llm,
        Embedding embedding,
        Query query,
        Maintenance maintenance,
        DomainKnowledge domainKnowledge,
        KnowledgeGraph knowledgeGraph
) {

    public record Ingest(
            int batchSize,
            int parseTimeoutSeconds,
            int parseMaxChars,
            int parseExecutorThreads,
            int parseExecutorQueueCapacity,
            boolean docConversionEnabled,
            String sofficePath,
            double extractTargetRatio,
            int extractTargetMin,
            int extractTargetMaxDoc,
            int extractTargetMaxPdf,
            int extractTargetMaxDefault,
            double overviewTargetRatio,
            int overviewTargetMin,
            int overviewTargetMaxDoc,
            int overviewTargetMaxPdf,
            int overviewTargetMaxDefault,
            int parseChunkMaxDoc,
            int parseChunkMaxPdf,
            int parseChunkMaxDefault
    ) {
    }

    public record Scan(
            boolean defaultRecursive
    ) {
    }

    public record Llm(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            boolean failOnError
    ) {
    }

    public record Embedding(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            int batchSize,
            int fallbackDimensions,
            boolean failOnError,
            double vectorTargetRatio,
            int vectorTargetMinChunk,
            int vectorTargetMinUnit,
            int vectorTargetMaxChunkDoc,
            int vectorTargetMaxChunkPdf,
            int vectorTargetMaxChunkDefault,
            int vectorTargetMaxUnitDoc,
            int vectorTargetMaxUnitPdf,
            int vectorTargetMaxUnitDefault
    ) {
    }

    public record Query(
            int statementTimeoutSeconds,
            int lockTimeoutSeconds,
            int vectorTimeoutSeconds,
            int asyncTaskTimeoutSeconds,
            int executorThreads,
            int executorQueueCapacity,
            int keywordLimitMultiplier,
            int keywordLimitBase,
            int vectorCandidateMultiplier,
            int vectorCandidateBase,
            int vectorLimitMultiplier,
            int vectorLimitBase,
            int candidateDocLimitBase,
            int candidateDocLimitDivisor,
            int candidateScanMultiplier,
            int candidateScanBase,
            int evidenceLimitBase,
            int fallbackLimitBase,
            int vectorIndependentKnowledgeUnitScanLimit,
            int vectorIndependentChunkScanLimit
    ) {
    }

    public record Maintenance(
            int executorThreads,
            int executorQueueCapacity,
            int requestTimeoutSeconds
    ) {
    }

    public record DomainKnowledge(
            int executorThreads,
            int executorQueueCapacity,
            int pollDelayMillis,
            int autoRefreshPollDelayMillis,
            int candidateDiscoveryPollDelayMillis,
            boolean candidateDiscoveryEnabled,
            int candidateDiscoveryWindowStartHour,
            int candidateDiscoveryWindowEndHour,
            int candidateDiscoveryLookbackHours,
            int candidateDiscoveryMaxDocuments,
            int candidateDiscoveryMaxCandidates,
            int candidateDiscoveryMinDocuments,
            int candidateDiscoveryMinHoursBetweenRuns,
            int candidateDiscoveryKnowledgeUnitFacetLimit,
            int candidateDiscoveryChunkTopicLimit,
            int candidateDiscoverySliceHours,
            int staleRunningSeconds,
            int maxTerms,
            int evidenceCandidateLimit,
            int evidenceFinalLimit,
            int evidencePerDocumentSoftCap,
            int evidencePerDocumentHardCap,
            int documentLimitPerTerm,
            int knowledgeUnitLimitPerTerm,
            int chunkLimitPerTerm,
            int snippetChars,
            int setupAssistantMaxCompletionTokens,
            boolean setupAssistantEnableThinking,
            RefinementLlm refinementLlm
    ) {
    }

    public record RefinementLlm(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            boolean failOnError,
            int maxCompletionTokens,
            int groupMaxCompletionTokens,
            int termPlanMaxCompletionTokens
    ) {
    }

    public record KnowledgeGraph(
            boolean enabled,
            String storeProvider,
            String storeBaseUrl,
            String storeDatabase,
            String storeUsername,
            String storePassword,
            int storeConnectTimeoutSeconds,
            int storeRequestTimeoutSeconds,
            int pollDelayMillis,
            int batchSize,
            int staleRunningSeconds,
            int chunkBatchSize,
            int maxChunksPerDocument,
            boolean chunkSelectionEnabled,
            int minChunkChars,
            int minSelectedChunksPerDocument,
            int maxSelectedChunksPerDocument,
            int maxKnowledgeUnitsPerDocument,
            String extractionProfile,
            String projectProfileKeywords,
            String policyProfileKeywords,
            String speechProfileKeywords,
            String reportProfileKeywords,
            ExtractionLlm extractionLlm,
            EntityFusion entityFusion
    ) {
    }

    public record ExtractionLlm(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            boolean failOnError,
            int maxCompletionTokens
    ) {
    }

    public record EntityFusion(
            boolean enabled,
            String mode,
            boolean createSameAsEdges,
            int minNameLength,
            int maxGroupSize,
            ExtractionLlm fusionLlm
    ) {
    }
}
