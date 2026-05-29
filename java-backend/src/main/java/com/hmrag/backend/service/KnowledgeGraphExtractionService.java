package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class KnowledgeGraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphExtractionService.class);
    private static final int ATTRIBUTE_GOVERNANCE_MAX_BATCH_CHARS = 12_000;
    private static final int EXTRACTION_MAX_FACTS_PER_BATCH = 24;
    private static final int EXTRACTION_MAX_FACTS_PER_CHUNK = 4;
    private static final int EXTRACTION_MAX_RESPONSE_CHARS = 12_000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public KnowledgeGraphExtractionService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AppProperties appProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public Map<String, Object> extract(UUID docId, UUID sourceFileId) {
        return extract(docId, sourceFileId, progress -> {
        });
    }

    public Map<String, Object> extract(UUID docId, UUID sourceFileId, Consumer<Map<String, Object>> progressCallback) {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        if (config == null || config.extractionLlm() == null || !llmEnabled(config.extractionLlm())) {
            throw new IllegalStateException("Knowledge graph extraction LLM is disabled or not configured.");
        }
        Map<String, Object> document = loadDocument(docId);
        List<Map<String, Object>> loadedChunks = loadChunks(docId, Math.max(1, config.maxChunksPerDocument()));
        List<Map<String, Object>> units = loadKnowledgeUnits(docId, Math.max(0, config.maxKnowledgeUnitsPerDocument()));
        ChunkSelectionResult chunkSelection = selectChunksForExtraction(loadedChunks, units, config);
        List<Map<String, Object>> chunks = chunkSelection.chunks();
        String extractionProfile = resolveExtractionProfile(config, document);
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> batchSummaries = new ArrayList<>();
        int batchSize = Math.max(1, config.chunkBatchSize());
        int batchCount = (int) Math.ceil((double) chunks.size() / batchSize);
        int extractedChunkCount = 0;
        int failedBatchCount = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int batchIndex = i / batchSize;
            int batchNo = batchIndex + 1;
            List<Map<String, Object>> batchChunks = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            List<Map<String, Object>> batchUnits = filterUnitsForChunks(units, batchChunks);
            String batchKey = extractionBatchKey(docId, graphVersion(), extractionProfile, batchNo, batchChunks);
            String inputHash = extractionInputHash(document, batchChunks, batchUnits, extractionProfile, config.extractionLlm());
            try {
                Map<String, Object> batchCandidate = loadSuccessfulBatchCandidates(batchKey, inputHash);
                List<Map<String, Object>> batchEntities;
                List<Map<String, Object>> batchFacts;
                List<Map<String, Object>> batchEvents;
                if (batchCandidate == null) {
                    UUID batchId = upsertExtractionBatch(docId, sourceFileId, graphVersion(), extractionProfile, batchNo, batchCount, batchKey, inputHash, batchChunks);
                    UUID attemptId = createExtractionAttempt(batchId);
                    String prompt = buildPrompt(document, batchChunks, batchUnits, batchNo, batchCount, extractionProfile);
                    markExtractionAttemptStarted(attemptId, prompt);
                    publishProgress(progressCallback, batchNo, batchCount, batchChunks.size(), "running", null);
                    long llmStartedAt = System.nanoTime();
                    log.info("Knowledge graph LLM extraction request started: docId={}, sourceFileId={}, batch={}/{}, chunks={}, model={}, promptChars={}",
                            docId, sourceFileId, batchNo, batchCount, batchChunks.size(), config.extractionLlm().model(), prompt.length());
                    try {
                        String raw = requestExtractionRaw(config.extractionLlm(), prompt);
                        log.info("Knowledge graph LLM extraction request succeeded: docId={}, sourceFileId={}, batch={}/{}, elapsedMs={}, responseChars={}",
                                docId, sourceFileId, batchNo, batchCount, elapsedMillis(llmStartedAt), raw == null ? 0 : raw.length());
                        markExtractionAttemptRawResponse(attemptId, raw);
                        Map<String, Object> extracted = parseJsonObject(raw);
                        Map<String, String> mentionIdMap = new LinkedHashMap<>();
                        List<Map<String, Object>> rawEntities = listOfMaps(extracted.get("entities"));
                        List<Map<String, Object>> rawRelations = listOfMaps(extracted.get("relations"));
                        List<Map<String, Object>> rawAttributes = listOfMaps(extracted.get("attributes"));
                        List<Map<String, Object>> rawFacts = extractedFacts(extracted);
                        batchEntities = prefixEntities(rawEntities, batchIndex, mentionIdMap, document);
                        FactBindingResult binding = prefixFacts(rawFacts, mentionIdMap, batchEntities, batchIndex, document);
                        batchFacts = binding.facts();
                        int boundEntityCount = batchEntities.size();
                        EntityUsageMark entityUsage = markEntitiesUsedByFacts(batchEntities, batchFacts);
                        batchEvents = listOfMaps(extracted.get("events"));
                        log.info("Knowledge graph extraction binding: docId={}, batch={}/{}, rawEntities={}, boundEntities={}, factEndpointEntities={}, contextEntities={}, autoEntities={}, rawRelations={}, rawAttributes={}, keptFacts={}, droppedFacts={}, droppedSubjectMissing={}, droppedObjectMissing={}",
                                docId, batchNo, batchCount, rawEntities.size(), boundEntityCount, entityUsage.factEndpointEntities(), entityUsage.contextEntities(), binding.autoEntities(),
                                rawRelations.size(), rawAttributes.size(), batchFacts.size(), binding.droppedFacts(),
                                binding.droppedSubjectMissing(), binding.droppedObjectMissing());
                        saveBatchCandidates(batchId, attemptId, batchKey, batchEntities, batchFacts);
                        markExtractionAttemptSuccess(attemptId, raw, extracted);
                        markExtractionBatchSuccess(batchId, batchEntities.size(), batchFacts.size(), batchFacts.stream().filter(this::isRelationFact).count());
                        publishProgress(progressCallback, batchNo, batchCount, batchChunks.size(), "success", null);
                    } catch (Exception batchEx) {
                        log.warn("Knowledge graph LLM extraction request failed: docId={}, sourceFileId={}, batch={}/{}, elapsedMs={}, error={}",
                                docId, sourceFileId, batchNo, batchCount, elapsedMillis(llmStartedAt), batchEx.getMessage());
                        markExtractionAttemptFailed(attemptId, batchEx);
                        markExtractionBatchFailed(batchId, batchEx);
                        publishProgress(progressCallback, batchNo, batchCount, batchChunks.size(), "failed", batchEx.getMessage());
                        throw batchEx;
                    }
                } else {
                    batchEntities = listOfMaps(batchCandidate.get("entities"));
                    batchFacts = listOfMaps(batchCandidate.get("facts"));
                    markEntitiesUsedByFacts(batchEntities, batchFacts);
                    batchEvents = List.of();
                    publishProgress(progressCallback, batchNo, batchCount, batchChunks.size(), "resumed", null);
                }
                entities.addAll(batchEntities);
                facts.addAll(batchFacts);
                events.addAll(batchEvents);
                extractedChunkCount += batchChunks.size();
                batchSummaries.add(Map.of(
                        "batchNo", batchNo,
                        "status", "success",
                        "chunkCount", batchChunks.size(),
                        "entityMentions", batchEntities.size(),
                        "facts", batchFacts.size(),
                        "relations", batchFacts.stream().filter(this::isRelationFact).count(),
                        "resumed", batchCandidate != null
                ));
            } catch (Exception ex) {
                failedBatchCount++;
                batchSummaries.add(Map.of(
                        "batchNo", batchNo,
                        "status", "failed",
                        "chunkCount", batchChunks.size(),
                        "error", truncate(ex.getMessage(), 500)
                ));
            }
        }
        if (!chunks.isEmpty() && failedBatchCount > 0) {
            String firstError = batchSummaries.stream()
                    .filter(summary -> "failed".equals(summary.get("status")))
                    .map(summary -> stringValue(summary.get("error")))
                    .filter(error -> !error.isBlank())
                    .findFirst()
                    .orElse("unknown error");
            throw new IllegalStateException("Knowledge graph extraction incomplete: "
                    + (batchCount - failedBatchCount) + "/" + batchCount
                    + " batch(es) succeeded, " + failedBatchCount + " failed. First error: "
                    + firstError + ". Retry will resume successful batches.");
        }
        if (!chunks.isEmpty() && extractedChunkCount == 0) {
            throw new IllegalStateException("Knowledge graph extraction failed for all batches.");
        }
        facts = dedupeFacts(facts);
        Map<String, Object> localGraph = new LinkedHashMap<>();
        localGraph.put("graphVersion", graphVersion());
        localGraph.put("sourceFileId", sourceFileId.toString());
        localGraph.put("docId", docId.toString());
        localGraph.put("document", document);
        localGraph.put("entities", entities);
        localGraph.put("facts", facts);
        localGraph.put("relations", facts.stream().filter(this::isRelationFact).toList());
        localGraph.put("events", events);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategy", "fact_kind_chunk_batch_local_graph_document_merge_then_global_fusion");
        metadata.put("model", config.extractionLlm().model());
        metadata.put("provider", normalizeProvider(config.extractionLlm().provider()));
        metadata.put("extractionProfile", extractionProfile);
        metadata.put("chunkCount", loadedChunks.size());
        metadata.put("selectedChunkCount", chunks.size());
        metadata.put("skippedChunkCount", Math.max(0, loadedChunks.size() - chunks.size()));
        metadata.put("extractedChunkCount", extractedChunkCount);
        metadata.put("failedBatchCount", failedBatchCount);
        metadata.put("batchCount", batchCount);
        metadata.put("chunkBatchSize", batchSize);
        metadata.put("chunkSelection", chunkSelection.summary());
        metadata.put("batchSummaries", batchSummaries);
        metadata.put("knowledgeUnitCount", units.size());
        metadata.put("entityMentions", entities.size());
        metadata.put("facts", facts.size());
        metadata.put("relations", facts.stream().filter(this::isRelationFact).count());
        metadata.put("attributeFacts", facts.stream().filter(this::isAttributeFact).count());
        metadata.put("transitionFacts", facts.stream().filter(this::isTransitionFact).count());
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        localGraph.put("metadata", metadata);
        return localGraph;
    }

    private void publishProgress(
            Consumer<Map<String, Object>> progressCallback,
            int batchNo,
            int batchCount,
            int chunkCount,
            String status,
            String error
    ) {
        if (progressCallback == null) {
            return;
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("batchNo", batchNo);
        progress.put("batchCount", batchCount);
        progress.put("chunkCount", chunkCount);
        progress.put("status", status);
        if (error != null && !error.isBlank()) {
            progress.put("error", truncate(error, 500));
        }
        progressCallback.accept(progress);
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    public String graphVersion() {
        AppProperties.ExtractionLlm llm = appProperties.knowledgeGraph() == null ? null : appProperties.knowledgeGraph().extractionLlm();
        String model = llm == null || llm.model() == null || llm.model().isBlank() ? "unconfigured" : llm.model();
        return "kg-v1:" + model;
    }

    private Map<String, Object> loadDocument(UUID docId) {
        return jdbcTemplate.query(
                """
                SELECT d.id, d.title, d.doc_type, d.source_file, d.source_filename,
                       d.publish_date, d.effective_date, d.metadata_json::text AS metadata_json,
                       sf.relative_path
                FROM documents d
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE d.id = :docId
                LIMIT 1
                """,
                new MapSqlParameterSource("docId", docId),
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException("Document not found for graph build: " + docId);
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("docType", rs.getString("doc_type"));
                    item.put("sourceFile", rs.getString("source_file"));
                    item.put("sourceFilename", rs.getString("source_filename"));
                    item.put("relativePath", rs.getString("relative_path"));
                    item.put("publishDate", rs.getString("publish_date"));
                    item.put("effectiveDate", rs.getString("effective_date"));
                    item.put("metadata", parseMap(rs.getString("metadata_json")));
                    return item;
                }
        );
    }

    private List<Map<String, Object>> loadChunks(UUID docId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, chunk_no, chunk_type, title, content, page_no
                FROM chunks
                WHERE doc_id = :docId
                ORDER BY chunk_no ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("docId", docId)
                        .addValue("limit", limit),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", rs.getString("id"));
                    item.put("chunkNo", rs.getInt("chunk_no"));
                    item.put("chunkType", rs.getString("chunk_type"));
                    item.put("title", rs.getString("title"));
                    item.put("content", truncate(rs.getString("content"), 1400));
                    int pageNo = rs.getInt("page_no");
                    item.put("pageNo", rs.wasNull() ? null : pageNo);
                    return item;
                }
        );
    }

    private List<Map<String, Object>> loadKnowledgeUnits(UUID docId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT id, chunk_id, unit_type, title, content, normalized_text, subject, indicator, source_page, source_span
                FROM knowledge_units
                WHERE doc_id = :docId
                ORDER BY created_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("docId", docId)
                        .addValue("limit", limit),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("knowledgeUnitId", rs.getString("id"));
                    item.put("chunkId", rs.getString("chunk_id"));
                    item.put("unitType", rs.getString("unit_type"));
                    item.put("title", rs.getString("title"));
                    item.put("content", truncate(rs.getString("content"), 900));
                    item.put("normalizedText", truncate(rs.getString("normalized_text"), 900));
                    item.put("subject", rs.getString("subject"));
                    item.put("indicator", rs.getString("indicator"));
                    int pageNo = rs.getInt("source_page");
                    item.put("pageNo", rs.wasNull() ? null : pageNo);
                    item.put("sourceSpan", rs.getString("source_span"));
                    return item;
                }
        );
    }

    private ChunkSelectionResult selectChunksForExtraction(
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> units,
            AppProperties.KnowledgeGraph config
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return new ChunkSelectionResult(List.of(), Map.of(
                    "enabled", config != null && config.chunkSelectionEnabled(),
                    "originalChunkCount", 0,
                    "selectedChunkCount", 0,
                    "skippedChunkCount", 0
            ));
        }
        if (config == null || !config.chunkSelectionEnabled()) {
            return new ChunkSelectionResult(chunks, Map.of(
                    "enabled", false,
                    "originalChunkCount", chunks.size(),
                    "selectedChunkCount", chunks.size(),
                    "skippedChunkCount", 0
            ));
        }
        Map<String, List<Map<String, Object>>> unitsByChunkId = unitsByChunkId(units);
        int minChars = Math.max(0, config.minChunkChars());
        int minSelected = Math.max(0, config.minSelectedChunksPerDocument());
        int maxSelected = config.maxSelectedChunksPerDocument() <= 0
                ? chunks.size()
                : Math.max(minSelected, config.maxSelectedChunksPerDocument());
        maxSelected = Math.min(maxSelected, chunks.size());
        List<ChunkCandidate> candidates = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();
        int duplicateSkipped = 0;
        int lowSignalSkipped = 0;
        for (Map<String, Object> chunk : chunks) {
            String chunkId = stringValue(chunk.get("chunkId"));
            List<Map<String, Object>> chunkUnits = unitsByChunkId.getOrDefault(chunkId, List.of());
            int score = chunkSelectionScore(chunk, chunkUnits, minChars);
            String contentKey = normalizedChunkContentKey(chunk);
            boolean duplicate = !contentKey.isBlank() && !seenContent.add(contentKey);
            if (duplicate && chunkUnits.isEmpty()) {
                duplicateSkipped++;
                continue;
            }
            if (score <= 0 && candidates.size() >= minSelected) {
                lowSignalSkipped++;
                continue;
            }
            candidates.add(new ChunkCandidate(chunk, score, chunkNo(chunk)));
        }
        if (candidates.isEmpty()) {
            candidates = chunks.stream()
                    .map(chunk -> new ChunkCandidate(chunk, 0, chunkNo(chunk)))
                    .toList();
        }
        List<Map<String, Object>> selected = candidates.stream()
                .sorted(Comparator
                        .comparingInt(ChunkCandidate::score).reversed()
                        .thenComparingInt(ChunkCandidate::chunkNo))
                .limit(maxSelected)
                .sorted(Comparator.comparingInt(ChunkCandidate::chunkNo))
                .map(ChunkCandidate::chunk)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabled", true);
        summary.put("strategy", "structure_ku_density_dedupe");
        summary.put("originalChunkCount", chunks.size());
        summary.put("selectedChunkCount", selected.size());
        summary.put("skippedChunkCount", Math.max(0, chunks.size() - selected.size()));
        summary.put("duplicateSkipped", duplicateSkipped);
        summary.put("lowSignalSkipped", lowSignalSkipped);
        summary.put("minChunkChars", minChars);
        summary.put("minSelectedChunksPerDocument", minSelected);
        summary.put("maxSelectedChunksPerDocument", maxSelected);
        summary.put("selectionSignals", List.of(
                "knowledge_unit_overlap",
                "section_title",
                "chunk_type",
                "content_density",
                "numeric_or_table_shape",
                "near_duplicate_content"
        ));
        return new ChunkSelectionResult(selected, summary);
    }

    private Map<String, List<Map<String, Object>>> unitsByChunkId(List<Map<String, Object>> units) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (units == null) {
            return result;
        }
        for (Map<String, Object> unit : units) {
            String chunkId = stringValue(unit.get("chunkId"));
            if (!chunkId.isBlank()) {
                result.computeIfAbsent(chunkId, ignored -> new ArrayList<>()).add(unit);
            }
        }
        return result;
    }

    private int chunkSelectionScore(Map<String, Object> chunk, List<Map<String, Object>> units, int minChars) {
        String title = stringValue(chunk.get("title"));
        String content = stringValue(chunk.get("content"));
        String chunkType = normalizeProvider(stringValue(chunk.get("chunkType")));
        String compact = content.replaceAll("\\s+", "");
        boolean hasUnits = units != null && !units.isEmpty();
        if (compact.length() < minChars && title.isBlank() && !hasUnits && !"table".equals(chunkType)) {
            return -1;
        }
        int score = 0;
        if (!title.isBlank()) {
            score += 4;
        }
        if ("table".equals(chunkType)) {
            score += 5;
        } else if ("list".equals(chunkType)) {
            score += 3;
        } else if ("heading".equals(chunkType) || "section".equals(chunkType)) {
            score += 4;
        }
        if (hasUnits) {
            score += 6;
            for (Map<String, Object> unit : units) {
                if (!stringValue(unit.get("subject")).isBlank()) {
                    score += 2;
                }
                if (!stringValue(unit.get("indicator")).isBlank()) {
                    score += 2;
                }
            }
        }
        int density = lightweightTokenCount(title + " " + content);
        score += Math.min(5, Math.max(0, density / 8));
        if (compact.length() >= 120) {
            score += 2;
        }
        if (compact.length() >= 450) {
            score += 1;
        }
        if (looksLikeNumericOrTableContent(content)) {
            score += 2;
        }
        return score;
    }

    private int lightweightTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Set<String> tokens = new HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9（）()《》/\\-]{1,24}")
                .matcher(text);
        while (matcher.find()) {
            String token = normalizeEntityName(matcher.group());
            if (token.length() >= 2 && !token.matches("^\\d+$")) {
                tokens.add(token);
            }
        }
        return tokens.size();
    }

    private boolean looksLikeNumericOrTableContent(String content) {
        String raw = stringValue(content);
        if (raw.isBlank()) {
            return false;
        }
        long digitCount = raw.chars().filter(Character::isDigit).count();
        return digitCount >= 4
                || raw.contains("|")
                || raw.contains("：")
                || raw.contains(":")
                || raw.contains("\t");
    }

    private String normalizedChunkContentKey(Map<String, Object> chunk) {
        String raw = (stringValue(chunk.get("title")) + "\n" + stringValue(chunk.get("content")))
                .replaceAll("\\s+", "");
        if (raw.length() < 80) {
            return "";
        }
        return sha256(raw.length() > 500 ? raw.substring(0, 500) : raw);
    }

    private int chunkNo(Map<String, Object> chunk) {
        Object value = chunk.get("chunkNo");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (Exception ex) {
            return Integer.MAX_VALUE;
        }
    }

    private String requestExtractionRaw(AppProperties.ExtractionLlm llm, String prompt) {
        try {
            return switch (normalizeProvider(llm.provider())) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(llm, prompt);
                case "ollama" -> callOllama(llm, prompt);
                default -> throw new IllegalStateException("Unsupported knowledge graph LLM provider: " + llm.provider());
            };
        } catch (Exception ex) {
            throw new IllegalStateException("Knowledge graph extraction failed: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> refineAttributeCandidateClusters(
            List<Map<String, Object>> clusters,
            List<Map<String, Object>> templateHints
    ) {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        AppProperties.ExtractionLlm llm = config == null ? null : config.extractionLlm();
        List<Map<String, Object>> originalClusters = clusters == null ? List.of() : clusters;
        if (llm == null || !llmEnabled(llm) || originalClusters.isEmpty()) {
            return Map.of(
                    "enabled", false,
                    "reason", llm == null || !llmEnabled(llm) ? "llm_not_configured" : "empty_clusters",
                    "clusters", originalClusters
            );
        }
        List<Map<String, Object>> reviewClusters = originalClusters.stream()
                .filter(this::shouldReviewAttributeGovernanceCluster)
                .map(this::attributeGovernancePromptCluster)
                .toList();
        if (reviewClusters.isEmpty()) {
            return Map.of("enabled", false, "reason", "no_reviewable_clusters", "clusters", originalClusters);
        }
        try {
            List<List<Map<String, Object>>> batches = attributeGovernanceBatches(reviewClusters, templateHints);
            long startedAt = System.nanoTime();
            Map<String, Map<String, Object>> byClusterKey = new LinkedHashMap<>();
            int failedBatches = 0;
            int batchNo = 0;
            for (List<Map<String, Object>> batch : batches) {
                batchNo++;
                String prompt = buildAttributeGovernancePrompt(batch, templateHints);
                log.info("Attribute governance LLM review batch started: batch={}/{}, clusters={}, model={}, promptChars={}",
                        batchNo, batches.size(), batch.size(), llm.model(), prompt.length());
                try {
                    String raw = requestExtractionRaw(llm, prompt);
                    Map<String, Object> parsed = parseJsonObject(raw);
                    List<Map<String, Object>> decisions = listOfMaps(parsed.get("decisions"));
                    for (Map<String, Object> decision : decisions) {
                        String clusterKey = stringValue(decision.get("clusterKey"));
                        if (!clusterKey.isBlank()) {
                            byClusterKey.put(clusterKey, decision);
                        }
                    }
                    log.info("Attribute governance LLM review batch succeeded: batch={}/{}, decisions={}",
                            batchNo, batches.size(), decisions.size());
                } catch (Exception batchEx) {
                    failedBatches++;
                    log.warn("Attribute governance LLM review batch failed: batch={}/{}, error={}",
                            batchNo, batches.size(), batchEx.getMessage());
                }
            }
            if (byClusterKey.isEmpty()) {
                throw new IllegalStateException("LLM governance response contains no decisions");
            }
            List<Map<String, Object>> refined = new ArrayList<>();
            int changed = 0;
            for (Map<String, Object> cluster : originalClusters) {
                Map<String, Object> item = new LinkedHashMap<>(cluster);
                Map<String, Object> llmDecision = byClusterKey.get(stringValue(cluster.get("clusterKey")));
                if (llmDecision != null && mergeAttributeGovernanceDecision(item, llmDecision)) {
                    changed++;
                }
                refined.add(item);
            }
            log.info("Attribute governance LLM review succeeded: reviewed={}, batches={}, failedBatches={}, decisions={}, changed={}, elapsedMs={}",
                    reviewClusters.size(), batches.size(), failedBatches, byClusterKey.size(), changed, elapsedMillis(startedAt));
            return Map.of(
                    "enabled", true,
                    "model", llm.model(),
                    "reviewedClusters", reviewClusters.size(),
                    "batchCount", batches.size(),
                    "failedBatchCount", failedBatches,
                    "decisionCount", byClusterKey.size(),
                    "changedClusters", changed,
                    "clusters", refined
            );
        } catch (Exception ex) {
            log.warn("Attribute governance LLM review failed: {}", ex.getMessage());
            return Map.of(
                    "enabled", false,
                    "reason", "llm_review_failed",
                    "error", truncate(ex.getMessage(), 500),
                    "clusters", originalClusters
            );
        }
    }

    private boolean shouldReviewAttributeGovernanceCluster(Map<String, Object> cluster) {
        String decision = normalizeAttributeGovernanceDecision(stringValue(cluster.get("decision")));
        double confidence = doubleValue(cluster.get("confidence"));
        String valueShape = normalizeProvider(stringValue(cluster.get("valueShape")));
        String predicate = normalizeProvider(stringValue(cluster.get("predicate")));
        String reason = normalizeProvider(stringValue(cluster.get("reason")));
        if ("attribute_fact".equals(decision)) {
            return confidence < 0.85d;
        }
        if ("relation_or_event".equals(decision) || "discard_or_relation".equals(decision)) {
            return false;
        }
        if (!"keep_candidate".equals(decision)) {
            return true;
        }
        if (reason.contains("valueentitynotattributesubject")) {
            return false;
        }
        if (Set.of("money", "date", "percent", "scalar").contains(valueShape)) {
            return true;
        }
        return looksLikeStableAttributeKey(predicate);
    }

    private List<List<Map<String, Object>>> attributeGovernanceBatches(
            List<Map<String, Object>> clusters,
            List<Map<String, Object>> templateHints
    ) throws JsonProcessingException {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> cluster : clusters) {
            List<Map<String, Object>> candidate = new ArrayList<>(current);
            candidate.add(cluster);
            boolean tooManyChars = buildAttributeGovernancePrompt(candidate, templateHints).length() > ATTRIBUTE_GOVERNANCE_MAX_BATCH_CHARS;
            if (!current.isEmpty() && tooManyChars) {
                batches.add(current);
                current = new ArrayList<>();
            }
            current.add(cluster);
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private boolean mergeAttributeGovernanceDecision(Map<String, Object> cluster, Map<String, Object> llmDecision) {
        String decision = normalizeAttributeGovernanceDecision(stringValue(llmDecision.get("decision")));
        if (decision.isBlank()) {
            return false;
        }
        String currentDecision = stringValue(cluster.get("decision"));
        String targetAttributeKey = stringValue(llmDecision.get("targetAttributeKey"));
        double confidence = Math.max(0.0d, Math.min(1.0d, doubleValue(llmDecision.get("confidence"))));
        if (confidence <= 0.0d) {
            confidence = doubleValue(cluster.get("confidence"));
        }
        String reason = stringValue(llmDecision.get("reason"));
        boolean changed = !decision.equals(currentDecision);
        cluster.put("decision", decision);
        if ("attribute_fact".equals(decision) && !targetAttributeKey.isBlank()) {
            changed = changed || !targetAttributeKey.equals(stringValue(cluster.get("targetAttributeKey")));
            cluster.put("targetAttributeKey", targetAttributeKey);
        } else if ("attribute_fact".equals(decision)) {
            decision = "keep_candidate";
            changed = true;
            cluster.put("decision", decision);
            cluster.put("targetAttributeKey", "");
            cluster.put("confidence", Math.min(Math.max(0.58d, confidence), 0.74d));
            cluster.put("reason", "llm_attribute_governance:missing_attribute_key");
            cluster.put("llmDecision", Map.of(
                    "decision", decision,
                    "targetAttributeKey", "",
                    "confidence", Math.min(Math.max(0.58d, confidence), 0.74d),
                    "reason", "missing_attribute_key",
                    "explanation", "LLM 认为可能是属性，但没有给出可落库的规范属性字段名，保留候选。"
            ));
            return true;
        } else if (!"attribute_fact".equals(decision)) {
            cluster.put("targetAttributeKey", "");
        }
        if ("attribute_fact".equals(decision) && valueDoesNotBelongToAttributeSubject(cluster)) {
            decision = "keep_candidate";
            changed = true;
            cluster.put("decision", decision);
            cluster.put("targetAttributeKey", "");
            cluster.put("confidence", Math.min(Math.max(0.58d, confidence), 0.74d));
            cluster.put("reason", "llm_attribute_governance:value_entity_not_attribute_subject");
            cluster.put("llmDecision", Map.of(
                    "decision", decision,
                    "targetAttributeKey", "",
                    "confidence", Math.min(Math.max(0.58d, confidence), 0.74d),
                    "reason", "value_entity_not_attribute_subject",
                    "explanation", "样例显示值并不属于当前主体实体的属性，保留候选等待主体归属治理。"
            ));
            return true;
        }
        if ("attribute_fact".equals(decision) && actionPredicateWithoutStableAttributeKey(cluster, targetAttributeKey)) {
            decision = "relation_or_event";
            changed = true;
            cluster.put("decision", decision);
            cluster.put("targetAttributeKey", "");
            cluster.put("confidence", Math.min(Math.max(0.66d, confidence), 0.78d));
            cluster.put("reason", "llm_attribute_governance:action_without_stable_attribute_key");
            cluster.put("llmDecision", Map.of(
                    "decision", decision,
                    "targetAttributeKey", "",
                    "confidence", Math.min(Math.max(0.66d, confidence), 0.78d),
                    "reason", "action_without_stable_attribute_key",
                    "explanation", "LLM 判为属性，但属性键仍是动作谓词，不能作为规范属性字段落库。"
            ));
            return true;
        }
        if ("attribute_fact".equals(decision) && unstableAttributeKeyForNewFact(cluster, targetAttributeKey)) {
            decision = "keep_candidate";
            changed = true;
            cluster.put("decision", decision);
            cluster.put("targetAttributeKey", "");
            cluster.put("confidence", Math.min(Math.max(0.58d, confidence), 0.74d));
            cluster.put("reason", "llm_attribute_governance:unstable_attribute_key");
            cluster.put("llmDecision", Map.of(
                    "decision", decision,
                    "targetAttributeKey", "",
                    "confidence", Math.min(Math.max(0.58d, confidence), 0.74d),
                    "reason", "unstable_attribute_key",
                    "explanation", "LLM 认为是属性，但给出的属性键不像规范字段名，保留候选等待模板治理。"
            ));
            return true;
        }
        if ("attribute_fact".equals(decision) && looksLikeNameAttribute(targetAttributeKey, cluster) && nameAttributeValueMismatchesSubject(cluster)) {
            decision = "keep_candidate";
            changed = true;
            cluster.put("decision", decision);
            cluster.put("targetAttributeKey", "");
            cluster.put("confidence", Math.min(Math.max(0.66d, confidence), 0.74d));
            cluster.put("reason", "llm_attribute_governance:name_value_mismatch");
            cluster.put("llmDecision", Map.of(
                    "decision", decision,
                    "targetAttributeKey", "",
                    "confidence", Math.min(Math.max(0.66d, confidence), 0.74d),
                    "reason", "name_value_mismatch",
                    "explanation", "名称属性的值不像主体实体自己的名称，保留候选等待人工或后续治理。"
            ));
            return true;
        }
        cluster.put("confidence", Math.max(doubleValue(cluster.get("confidence")), confidence));
        cluster.put("reason", reason.isBlank() ? "llm_attribute_governance" : "llm_attribute_governance:" + reason);
        cluster.put("llmDecision", Map.of(
                "decision", decision,
                "targetAttributeKey", targetAttributeKey,
                "confidence", confidence,
                "reason", reason,
                "explanation", truncate(stringValue(llmDecision.get("explanation")), 300)
        ));
        return changed || !reason.isBlank();
    }

    private boolean looksLikeNameAttribute(String targetAttributeKey, Map<String, Object> cluster) {
        String target = normalizeProvider(targetAttributeKey);
        String predicate = normalizeProvider(stringValue(cluster.get("predicate")));
        return target.contains("名称") || target.contains("name") || predicate.contains("名称") || predicate.contains("name");
    }

    private boolean unstableAttributeKeyForNewFact(Map<String, Object> cluster, String targetAttributeKey) {
        if (doubleValue(cluster.get("formalCount")) > 0) {
            return false;
        }
        return !looksLikeStableAttributeKey(normalizeProvider(targetAttributeKey));
    }

    private boolean valueDoesNotBelongToAttributeSubject(Map<String, Object> cluster) {
        String reason = normalizeProvider(stringValue(cluster.get("reason")));
        String ruleReason = normalizeProvider(stringValue(cluster.get("ruleReason")));
        return reason.contains("value_entity_not_attribute_subject")
                || ruleReason.contains("value_entity_not_attribute_subject")
                || reason.contains("valueentitynotattributesubject")
                || ruleReason.contains("valueentitynotattributesubject");
    }

    private boolean actionPredicateWithoutStableAttributeKey(Map<String, Object> cluster, String targetAttributeKey) {
        String predicate = normalizeProvider(stringValue(cluster.get("predicate")));
        String target = normalizeProvider(targetAttributeKey);
        String ruleReason = normalizeProvider(stringValue(cluster.get("ruleReason")));
        String reason = normalizeProvider(stringValue(cluster.get("reason")));
        if (!(ruleReason.contains("actionpredicate") || reason.contains("actionpredicate") || looksLikeActionPredicate(predicate))) {
            return false;
        }
        if (looksLikeStableAttributeKey(target)) {
            return false;
        }
        return true;
    }

    private boolean looksLikeStableAttributeKey(String normalizedKey) {
        if (normalizedKey.isBlank()) {
            return false;
        }
        return normalizedKey.contains("名称")
                || normalizedKey.contains("类型")
                || normalizedKey.contains("分类")
                || normalizedKey.contains("范围")
                || normalizedKey.contains("时间")
                || normalizedKey.contains("日期")
                || normalizedKey.contains("年份")
                || normalizedKey.contains("版本")
                || normalizedKey.contains("编号")
                || normalizedKey.contains("状态")
                || normalizedKey.contains("主题")
                || normalizedKey.contains("单位")
                || normalizedKey.contains("机构")
                || normalizedKey.contains("作者")
                || normalizedKey.contains("导师")
                || normalizedKey.contains("金额")
                || normalizedKey.contains("费用")
                || normalizedKey.contains("成本")
                || normalizedKey.contains("数量")
                || normalizedKey.contains("人数")
                || normalizedKey.endsWith("数")
                || normalizedKey.contains("比例")
                || normalizedKey.contains("指标")
                || normalizedKey.contains("要求")
                || normalizedKey.contains("条件")
                || normalizedKey.contains("负责人")
                || normalizedKey.contains("功能")
                || normalizedKey.contains("能力")
                || normalizedKey.contains("职责")
                || normalizedKey.contains("服务")
                || normalizedKey.contains("内容")
                || normalizedKey.contains("说明");
    }

    private boolean looksLikeActionPredicate(String normalizedValue) {
        if (normalizedValue.isBlank()) {
            return false;
        }
        return normalizedValue.contains("召开")
                || normalizedValue.contains("提出")
                || normalizedValue.contains("完成")
                || normalizedValue.contains("开展")
                || normalizedValue.contains("贯彻")
                || normalizedValue.contains("部署")
                || normalizedValue.contains("颁布")
                || normalizedValue.contains("印发")
                || normalizedValue.contains("取消")
                || normalizedValue.contains("撤销")
                || normalizedValue.contains("增列")
                || normalizedValue.contains("生成")
                || normalizedValue.contains("促进")
                || normalizedValue.contains("提升")
                || normalizedValue.contains("加强")
                || normalizedValue.contains("改进")
                || normalizedValue.contains("遵循")
                || normalizedValue.contains("满足")
                || normalizedValue.contains("包括")
                || normalizedValue.contains("提供")
                || normalizedValue.contains("响应")
                || normalizedValue.contains("准备")
                || normalizedValue.contains("审议")
                || normalizedValue.contains("通过")
                || normalizedValue.contains("认定")
                || normalizedValue.contains("授予")
                || normalizedValue.contains("招收");
    }

    private boolean nameAttributeValueMismatchesSubject(Map<String, Object> cluster) {
        List<Map<String, Object>> samples = limitedMapList(cluster.get("sampleFacts"), 5);
        if (samples.isEmpty()) {
            return false;
        }
        int checked = 0;
        int mismatch = 0;
        for (Map<String, Object> sample : samples) {
            String entityName = normalizeNameForComparison(stringValue(sample.get("entityName")));
            String value = normalizeNameForComparison(stringValue(sample.get("value")));
            if (entityName.isBlank() || value.isBlank()) {
                continue;
            }
            checked++;
            if (!(entityName.contains(value) || value.contains(entityName))) {
                mismatch++;
            }
        }
        return checked > 0 && mismatch == checked;
    }

    private String normalizeNameForComparison(String value) {
        return Normalizer.normalize(stringValue(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private String normalizeAttributeGovernanceDecision(String value) {
        String compact = normalizeProvider(value).replace("-", "_");
        return switch (compact) {
            case "attribute", "attr", "property", "attribute_fact" -> "attribute_fact";
            case "relation_fact", "event_fact", "claim_fact", "task_fact", "relation_or_event", "relation", "event" -> "relation_or_event";
            case "discard_or_relation", "reject", "rejected", "noise", "discard" -> "discard_or_relation";
            case "keep_candidate", "candidate", "uncertain", "keep" -> "keep_candidate";
            default -> "";
        };
    }

    private Map<String, Object> attributeGovernancePromptCluster(Map<String, Object> cluster) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("clusterKey", stringValue(cluster.get("clusterKey")));
        item.put("entityType", stringValue(cluster.get("entityType")));
        item.put("predicate", stringValue(cluster.get("predicate")));
        item.put("valueShape", stringValue(cluster.get("valueShape")));
        item.put("statementPattern", stringValue(cluster.get("statementPattern")));
        item.put("ruleDecision", stringValue(cluster.get("decision")));
        item.put("ruleTargetAttributeKey", stringValue(cluster.get("targetAttributeKey")));
        item.put("ruleConfidence", doubleValue(cluster.get("confidence")));
        item.put("ruleReason", stringValue(cluster.get("reason")));
        item.put("factCount", cluster.get("factCount"));
        item.put("formalCount", cluster.get("formalCount"));
        item.put("candidateCount", cluster.get("candidateCount"));
        item.put("entityCount", cluster.get("entityCount"));
        item.put("documentCount", cluster.get("documentCount"));
        item.put("sampleValues", limitedStringList(cluster.get("sampleValues"), 5, 120));
        item.put("sampleStatements", limitedStringList(cluster.get("sampleStatements"), 4, 220));
        item.put("sampleFacts", limitedMapList(cluster.get("sampleFacts"), 5));
        return item;
    }

    private String buildAttributeGovernancePrompt(List<Map<String, Object>> clusters, List<Map<String, Object>> templateHints) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "attribute_governance_cluster_review");
        payload.put("instructions", List.of(
                "你只审查候选属性簇，不重新抽取全文。",
                "根据实体类型模板、谓词、样例值、证据语句，判断候选应归为属性、关系/事件、噪声移出、继续候选。",
                "属性事实必须是主体实体的稳定字段或阶段字段，例如名称、作者、导师、立项时间、经费、建设单位、应用单位、版本、状态、主题。",
                "审查时必须同时看 sampleFacts 中的 entityName、predicate、value、statement；如果 value 并不是 entityName 所指实体的属性，不能判为 attribute_fact。",
                "如果 predicate 是“名称”，value 必须是该主体实体自己的名称或规范名；如果只是字段名、项目名、文档标题、另一个实体名称，应判为 keep_candidate 或 relation_or_event。",
                "实体之间的动作、应用、建设、负责、包含、召开、发布等通常是关系或事件，不要提升为属性。",
                "如果判为 attribute_fact，targetAttributeKey 必须是可落库的名词性字段名，例如 功能、金额、起始年份、响应时间、建设单位；不能直接复制动作谓词，例如 生成、召开、完成、促进、响应。",
                "如果像属性但无法确定规范字段名，返回 keep_candidate，不要返回 attribute_fact。",
                "目录、字段清单、章节引用、表头说明是噪声或结构线索，不要提升为属性。",
                "必须为每个输入 cluster 返回一个 decisions 项。",
                "只返回 JSON 对象，格式必须严格为 {\"decisions\":[...]}，不要 Markdown，不要解释文本。"
        ));
        payload.put("allowedDecisions", List.of("attribute_fact", "relation_or_event", "discard_or_relation", "keep_candidate"));
        payload.put("outputSchema", Map.of(
                "decisions", List.of(Map.of(
                        "clusterKey", "原 clusterKey",
                        "decision", "attribute_fact|relation_or_event|discard_or_relation|keep_candidate",
                        "targetAttributeKey", "仅 attribute_fact 必填，应使用模板中的属性名或规范短字段名",
                        "confidence", "0-1",
                        "reason", "简短原因代码",
                        "explanation", "一句话解释"
                ))
        ));
        payload.put("entityTypeAttributeTemplates", templateHints == null ? List.of() : templateHints);
        payload.put("clusters", clusters);
        return objectMapper.writeValueAsString(payload);
    }

    private List<String> limitedStringList(Object raw, int limit, int maxLength) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String value = truncate(stringValue(item), maxLength);
            if (!value.isBlank() && result.size() < limit) {
                result.add(value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> limitedMapList(Object raw, int limit) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (result.size() >= limit) {
                break;
            }
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private String extractionBatchKey(
            UUID docId,
            String graphVersion,
            String extractionProfile,
            int batchNo,
            List<Map<String, Object>> chunks
    ) {
        String chunkIds = chunks.stream()
                .map(chunk -> stringValue(chunk.get("chunkId")))
                .filter(chunkId -> !chunkId.isBlank())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return sha256(docId + "|" + graphVersion + "|" + extractionProfile + "|" + batchNo + "|" + chunkIds);
    }

    private String extractionInputHash(
            Map<String, Object> document,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> units,
            String extractionProfile,
            AppProperties.ExtractionLlm llm
    ) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("promptVersion", "triple-candidate-fact-compatible-v3-structured-units-bounded-output");
            input.put("document", documentPromptContext(document));
            input.put("chunks", chunks);
            input.put("knowledgeUnits", unitsPromptContext(units));
            input.put("extractionProfile", extractionProfile);
            input.put("provider", normalizeProvider(llm.provider()));
            input.put("model", llm.model());
            return sha256(objectMapper.writeValueAsString(input));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to hash extraction batch input: " + ex.getMessage(), ex);
        }
    }

    private UUID upsertExtractionBatch(
            UUID docId,
            UUID sourceFileId,
            String graphVersion,
            String extractionProfile,
            int batchNo,
            int batchCount,
            String batchKey,
            String inputHash,
            List<Map<String, Object>> chunks
    ) {
        String chunkIds = jsonString(chunks.stream().map(chunk -> stringValue(chunk.get("chunkId"))).toList());
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO graph_extraction_batches (
                    doc_id, source_file_id, graph_version, extraction_profile, batch_no, batch_count,
                    batch_key, input_hash, chunk_ids_json, status, created_at, updated_at
                ) VALUES (
                    :docId, :sourceFileId, :graphVersion, :extractionProfile, :batchNo, :batchCount,
                    :batchKey, :inputHash, CAST(:chunkIdsJson AS jsonb), 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (batch_key)
                DO UPDATE SET
                    doc_id = EXCLUDED.doc_id,
                    source_file_id = EXCLUDED.source_file_id,
                    graph_version = EXCLUDED.graph_version,
                    extraction_profile = EXCLUDED.extraction_profile,
                    batch_no = EXCLUDED.batch_no,
                    batch_count = EXCLUDED.batch_count,
                    input_hash = EXCLUDED.input_hash,
                    chunk_ids_json = EXCLUDED.chunk_ids_json,
                    status = CASE
                        WHEN graph_extraction_batches.input_hash = EXCLUDED.input_hash
                             AND graph_extraction_batches.status = 'success' THEN 'success'
                        ELSE 'pending'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("docId", docId)
                        .addValue("sourceFileId", sourceFileId)
                        .addValue("graphVersion", graphVersion)
                        .addValue("extractionProfile", extractionProfile)
                        .addValue("batchNo", batchNo)
                        .addValue("batchCount", batchCount)
                        .addValue("batchKey", batchKey)
                        .addValue("inputHash", inputHash)
                        .addValue("chunkIdsJson", chunkIds),
                UUID.class
        );
    }

    private UUID createExtractionAttempt(UUID batchId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO graph_extraction_attempts (
                    batch_id, status, started_at, created_at, updated_at
                ) VALUES (
                    :batchId, 'running', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """,
                new MapSqlParameterSource("batchId", batchId),
                UUID.class
        );
    }

    private void markExtractionAttemptStarted(UUID attemptId, String prompt) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_attempts
                SET raw_request = :rawRequest,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                """,
                new MapSqlParameterSource("attemptId", attemptId)
                        .addValue("rawRequest", truncate(prompt, 20000))
        );
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_batches
                SET status = 'running',
                    attempt_count = attempt_count + 1,
                    lease_token = :leaseToken,
                    lease_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT batch_id
                    FROM graph_extraction_attempts
                    WHERE id = :attemptId
                )
                """,
                new MapSqlParameterSource("attemptId", attemptId)
                        .addValue("leaseToken", attemptId.toString())
        );
    }

    private void markExtractionAttemptSuccess(UUID attemptId, String raw, Map<String, Object> parsed) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_attempts
                SET status = 'success',
                    raw_response = :rawResponse,
                    parsed_output_json = CAST(:parsedOutput AS jsonb),
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                """,
                new MapSqlParameterSource("attemptId", attemptId)
                        .addValue("rawResponse", truncate(raw, 20000))
                        .addValue("parsedOutput", jsonString(parsed))
        );
    }

    private void markExtractionAttemptRawResponse(UUID attemptId, String raw) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_attempts
                SET raw_response = :rawResponse,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                """,
                new MapSqlParameterSource("attemptId", attemptId)
                        .addValue("rawResponse", truncate(raw, 20000))
        );
    }

    private void markExtractionAttemptFailed(UUID attemptId, Exception ex) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_attempts
                SET status = :status,
                    error_message = :errorMessage,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                """,
                new MapSqlParameterSource("attemptId", attemptId)
                        .addValue("status", parseFailure(ex) ? "parse_failed" : "failed")
                        .addValue("errorMessage", truncate(ex.getMessage(), 2000))
        );
    }

    private void markExtractionBatchSuccess(UUID batchId, int entityMentions, int facts, long relations) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_batches
                SET status = 'success',
                    entity_mentions = :entityMentions,
                    facts = :facts,
                    relations = :relations,
                    error_message = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :batchId
                """,
                new MapSqlParameterSource("batchId", batchId)
                        .addValue("entityMentions", entityMentions)
                        .addValue("facts", facts)
                        .addValue("relations", relations)
        );
    }

    private void markExtractionBatchFailed(UUID batchId, Exception ex) {
        jdbcTemplate.update(
                """
                UPDATE graph_extraction_batches
                SET status = :status,
                    error_message = :errorMessage,
                    lease_token = NULL,
                    lease_until = NULL,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :batchId
                """,
                new MapSqlParameterSource("batchId", batchId)
                        .addValue("status", parseFailure(ex) ? "parse_failed" : "failed")
                        .addValue("errorMessage", truncate(ex.getMessage(), 2000))
        );
    }

    private Map<String, Object> loadSuccessfulBatchCandidates(String batchKey, String inputHash) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT c.candidate_type, c.payload_json::text AS payload_json
                FROM graph_extraction_batches b
                JOIN graph_candidate_records c ON c.batch_id = b.id
                WHERE b.batch_key = :batchKey
                  AND b.input_hash = :inputHash
                  AND b.status = 'success'
                  AND c.status = 'active'
                ORDER BY c.created_at ASC
                """,
                new MapSqlParameterSource("batchKey", batchKey)
                        .addValue("inputHash", inputHash),
                (rs, rowNum) -> Map.of(
                        "candidateType", rs.getString("candidate_type"),
                        "payload", parseMap(rs.getString("payload_json"))
                )
        );
        if (rows.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> payload = mapFromObject(row.get("payload"));
            if ("entity".equals(row.get("candidateType"))) {
                entities.add(payload);
            } else if ("fact".equals(row.get("candidateType"))) {
                facts.add(payload);
            }
        }
        return Map.of("entities", entities, "facts", facts);
    }

    private void saveBatchCandidates(
            UUID batchId,
            UUID attemptId,
            String batchKey,
            List<Map<String, Object>> entities,
            List<Map<String, Object>> facts
    ) {
        jdbcTemplate.update(
                "DELETE FROM graph_candidate_records WHERE batch_id = :batchId",
                new MapSqlParameterSource("batchId", batchId)
        );
        for (Map<String, Object> entity : entities) {
            insertCandidate(batchId, attemptId, batchKey, "entity", stringValue(entity.get("mentionId")), entity);
        }
        for (Map<String, Object> fact : facts) {
            String stableLocalId = stringValue(fact.get("subjectMentionId")) + "|"
                    + stringValue(fact.get("predicate")) + "|"
                    + stringValue(fact.get("objectMentionId")) + "|"
                    + stringValue(fact.get("chunkId")) + "|"
                    + stringValue(fact.get("statement"));
            insertCandidate(batchId, attemptId, batchKey, "fact", stableLocalId, fact);
        }
    }

    private void insertCandidate(
            UUID batchId,
            UUID attemptId,
            String batchKey,
            String candidateType,
            String stableLocalId,
            Map<String, Object> payload
    ) {
        String payloadJson = jsonString(payload);
        String payloadHash = sha256(payloadJson);
        String candidateKey = batchKey + ":" + candidateType + ":" + sha256(stableLocalId) + ":" + payloadHash;
        jdbcTemplate.update(
                """
                INSERT INTO graph_candidate_records (
                    batch_id, attempt_id, candidate_key, candidate_type, stable_local_id,
                    payload_hash, payload_json, status, created_at, updated_at
                ) VALUES (
                    :batchId, :attemptId, :candidateKey, :candidateType, :stableLocalId,
                    :payloadHash, CAST(:payloadJson AS jsonb), 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (candidate_key)
                DO UPDATE SET
                    batch_id = EXCLUDED.batch_id,
                    attempt_id = EXCLUDED.attempt_id,
                    payload_json = EXCLUDED.payload_json,
                    status = 'active',
                    updated_at = CURRENT_TIMESTAMP
                """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("attemptId", attemptId)
                        .addValue("candidateKey", candidateKey)
                        .addValue("candidateType", candidateType)
                        .addValue("stableLocalId", truncate(stableLocalId, 500))
                        .addValue("payloadHash", payloadHash)
                        .addValue("payloadJson", payloadJson)
        );
    }

    private String buildPrompt(Map<String, Object> document, List<Map<String, Object>> chunks, List<Map<String, Object>> units, int batchNo, int batchCount, String extractionProfile) throws JsonProcessingException {
        return """
                你是严格的信息三元组抽取器。请只返回一个 JSON 对象，不要 Markdown。
                目标：从当前 chunk 批次中高召回抽取“候选事实三元组”，后端会再做实体匹配、属性/关系分流、文档级合并和全局融合。
                当前批次：%s / %s。

                必须返回字段：
                {
                  "entities": [
                    {
                      "mentionId": "可为空；如果填写，仅用于本批次内引用",
                      "name": "实体名",
                      "type": "Person|Organization|Location|Time|Event|Document|Project|Product|System|Policy|Process|Indicator|Concept|Technology|Resource|Requirement|Outcome|Other",
                      "definition": "该文档语境下含义，无法判断则空字符串",
                      "chunkId": "证据chunkId",
                      "sourceSpan": "可为空",
                      "confidence": 0.0
                    }
                  ],
                  "triples": [
                    {
                      "subjectMentionId": "entities中的mentionId，可为空",
                      "subject": "主体实体名称，必须填写",
                      "objectMentionId": "entities中的mentionId，可为空",
                      "object": "客体实体名称，必须填写",
                      "subjectType": "主体实体类型，可为空",
                      "objectType": "客体实体类型，可为空",
                      "predicate": "原文关系或简短归一化关系",
                      "statement": "忠于原文的事实陈述",
                      "factKind": "不确定可为空；仅在明确是演化/别名/属性时填写 transition_fact|identity_fact|attribute_fact，否则留空",
                      "chunkId": "证据chunkId",
                      "sourceSpan": "可为空",
                      "confidence": 0.0
                    }
                  ],
                  "facts": [],
                  "relations": [],
                  "attributes": [],
                  "events": []
                }

                规则：
                1. 不要编造实体或关系。
                2. 每个实体、关系、属性必须带 chunkId 作为证据。
                3. subject/object 请填写原文中的实体、概念、组织、人物、项目、制度、系统、算法、事件等名称；不要填写内部ID。
                4. 不要做阶段、演进、跨文档融合等治理判断；只抽当前批次直接表达的事实。
                5. document.title、sourceFilename、relativePath 是来源上下文，默认不是业务实体。
                6. 优先抽取两个业务对象之间的明确事实；若是数值、时间、数量、评价、定义、要求等，也可以作为候选三元组输出，后端会分流为属性事实。
                7. 不要输出 subject 与 object 完全相同的三元组；没有明确事实则返回空数组。
                8. 输出必须克制：整批 triples 最多 %s 条，每个 chunk 最多 %s 条；超过上限时只保留业务意义最高、证据最明确的事实。
                9. 不要逐行展开表格、清单、经费明细、人员名单、编号列表；只抽取表格/清单表达的核心对象、字段含义、约束或汇总关系。
                10. entities 只输出 triples 会用到的端点实体和少量必要上下文实体，避免把所有短语都列为实体。
                11. statement/sourceSpan 保持短句，单项不超过 80 个中文字符；整个 JSON 响应控制在 %s 字符以内。

                文档：
                %s

                chunks：
                %s

                已有索引信号（来自解析/knowledge_units，只作为当前批次上下文，不等同于最终实体）：
                %s
                """.formatted(
                batchNo,
                batchCount,
                EXTRACTION_MAX_FACTS_PER_BATCH,
                EXTRACTION_MAX_FACTS_PER_CHUNK,
                EXTRACTION_MAX_RESPONSE_CHARS,
                objectMapper.writeValueAsString(documentPromptContext(document)),
                objectMapper.writeValueAsString(chunks),
                objectMapper.writeValueAsString(unitsPromptContext(units))
        );
    }

    private Map<String, Object> documentPromptContext(Map<String, Object> document) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("title", document.get("title"));
        context.put("docType", document.get("docType"));
        context.put("sourceFilename", document.get("sourceFilename"));
        context.put("relativePath", document.get("relativePath"));
        return context;
    }

    private List<Map<String, Object>> unitsPromptContext(List<Map<String, Object>> units) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> unit : units) {
            if (result.size() >= 40) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", unit.get("chunkId"));
            item.put("unitType", unit.get("unitType"));
            item.put("title", truncate(stringValue(unit.get("title")), 120));
            item.put("subject", truncate(stringValue(unit.get("subject")), 80));
            item.put("indicator", truncate(stringValue(unit.get("indicator")), 80));
            item.put("content", truncate(firstNonBlank(
                    stringValue(unit.get("normalizedText")),
                    stringValue(unit.get("content"))
            ), 220));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> filterUnitsForChunks(List<Map<String, Object>> units, List<Map<String, Object>> chunks) {
        if (units.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        Set<String> chunkIds = new HashSet<>();
        for (Map<String, Object> chunk : chunks) {
            chunkIds.add(String.valueOf(chunk.get("chunkId")));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> unit : units) {
            if (chunkIds.contains(String.valueOf(unit.get("chunkId")))) {
                result.add(unit);
            }
        }
        return result;
    }

    private List<Map<String, Object>> prefixEntities(List<Map<String, Object>> entities, int batchIndex, Map<String, String> mentionIdMap, Map<String, Object> document) {
        List<Map<String, Object>> result = new ArrayList<>();
        int fallbackIndex = 0;
        for (Map<String, Object> entity : entities) {
            Map<String, Object> copy = new LinkedHashMap<>(entity);
            if (isSourceArtifactEntity(copy, document)) {
                continue;
            }
            String oldMentionId = stringValue(copy.get("mentionId"));
            if (oldMentionId.isBlank()) {
                oldMentionId = "e" + (++fallbackIndex);
            }
            String newMentionId = "b" + (batchIndex + 1) + "_" + oldMentionId;
            mentionIdMap.put(oldMentionId, newMentionId);
            copy.put("mentionId", newMentionId);
            result.add(copy);
        }
        return result;
    }

    private boolean isSourceArtifactEntity(Map<String, Object> entity, Map<String, Object> document) {
        String name = stringValue(entity.get("name"));
        String type = normalizeProvider(stringValue(entity.get("type")));
        if (name.isBlank()) {
            return true;
        }
        if ("document".equals(type)) {
            return true;
        }
        String normalizedName = normalizeArtifactName(name);
        if (sourceArtifactNames(document).contains(normalizedName)) {
            return true;
        }
        return looksLikeImportedFilename(name);
    }

    private Set<String> sourceArtifactNames(Map<String, Object> document) {
        Set<String> names = new HashSet<>();
        for (String key : List.of("title", "sourceFilename", "sourceFile", "relativePath")) {
            String value = stringValue(document.get(key));
            if (value.isBlank()) {
                continue;
            }
            addArtifactName(names, value);
            addArtifactName(names, fileNameFromPath(value));
        }
        return names;
    }

    private void addArtifactName(Set<String> names, String value) {
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return;
        }
        names.add(normalizeArtifactName(raw));
        names.add(normalizeArtifactName(stripExtension(raw)));
    }

    private String fileNameFromPath(String value) {
        String raw = stringValue(value);
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        return slash >= 0 ? raw.substring(slash + 1) : raw;
    }

    private String stripExtension(String value) {
        return stringValue(value).replaceFirst("(?i)\\.(docx?|pdf|xlsx?|pptx?|txt|md)$", "");
    }

    private boolean looksLikeImportedFilename(String value) {
        String raw = stringValue(value);
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(docx?|pdf|xlsx?|pptx?|txt|md)$")
                || lower.startsWith("liushl_")
                || raw.matches(".*_[0-9]{3,}$");
    }

    private String normalizeArtifactName(String value) {
        String normalized = Normalizer.normalize(stringValue(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        return stripExtension(normalized)
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private List<Map<String, Object>> prefixRelations(List<Map<String, Object>> relations, Map<String, String> mentionIdMap) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> relation : relations) {
            String subject = stringValue(relation.get("subjectMentionId"));
            String object = stringValue(relation.get("objectMentionId"));
            String mappedSubject = mentionIdMap.get(subject);
            String mappedObject = mentionIdMap.get(object);
            if (mappedSubject == null || mappedObject == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(relation);
            copy.put("subjectMentionId", mappedSubject);
            copy.put("objectMentionId", mappedObject);
            result.add(copy);
        }
        return result;
    }

    private List<Map<String, Object>> extractedFacts(Map<String, Object> extracted) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<String, Object> fact : listOfMaps(extracted.get("facts"))) {
            Map<String, Object> copy = new LinkedHashMap<>(fact);
            copy.put("factKind", normalizeFactKind(stringValue(copy.get("factKind"))));
            if (stringValue(copy.get("predicate")).isBlank() && !stringValue(copy.get("relationType")).isBlank()) {
                copy.put("predicate", stringValue(copy.get("relationType")));
            }
            facts.add(copy);
        }
        for (Map<String, Object> triple : listOfMaps(extracted.get("triples"))) {
            Map<String, Object> fact = new LinkedHashMap<>(triple);
            fact.put("factKind", normalizeFactKind(stringValue(fact.get("factKind"))));
            fact.put("predicate", blankTo(stringValue(triple.get("predicate")), stringValue(triple.get("relationType"))));
            fact.put("relationType", blankTo(stringValue(triple.get("relationType")), stringValue(triple.get("predicate"))));
            fact.put("object", stringValue(triple.get("object")));
            fact.put("objectType", stringValue(triple.get("objectType")));
            fact.put("value", stringValue(triple.get("value")));
            facts.add(fact);
        }
        for (Map<String, Object> relation : listOfMaps(extracted.get("relations"))) {
            Map<String, Object> fact = new LinkedHashMap<>(relation);
            fact.put("factKind", "relation_fact");
            fact.put("predicate", blankTo(stringValue(relation.get("predicate")), stringValue(relation.get("relationType"))));
            fact.put("object", stringValue(relation.get("object")));
            fact.put("objectType", stringValue(relation.get("objectType")));
            fact.put("value", stringValue(relation.get("value")));
            facts.add(fact);
        }
        for (Map<String, Object> attribute : listOfMaps(extracted.get("attributes"))) {
            Map<String, Object> fact = new LinkedHashMap<>(attribute);
            fact.put("factKind", "attribute_fact");
            fact.put("predicate", blankTo(stringValue(attribute.get("predicate")), stringValue(attribute.get("name"))));
            fact.put("objectMentionId", "");
            fact.put("object", stringValue(attribute.get("value")));
            fact.put("objectType", "Text");
            fact.put("value", stringValue(attribute.get("value")));
            facts.add(fact);
        }
        return facts;
    }

    private FactBindingResult prefixFacts(
            List<Map<String, Object>> facts,
            Map<String, String> mentionIdMap,
            List<Map<String, Object>> entities,
            int batchIndex,
            Map<String, Object> document
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, String> nameToMentionId = entityNameIndex(entities);
        int autoEntitiesBefore = entities.size();
        int droppedFacts = 0;
        int droppedSubjectMissing = 0;
        int droppedObjectMissing = 0;
        for (Map<String, Object> fact : facts) {
            String factKind = normalizeCandidateFactKind(fact);
            String mappedSubject = resolveFactEndpoint(
                    fact,
                    mentionIdMap,
                    nameToMentionId,
                    entities,
                    batchIndex,
                    document,
                    "subjectMentionId",
                    List.of("subject", "subjectName", "source", "sourceName"),
                    List.of("subjectType", "sourceType", "entityType", "type"),
                    true
            );
            if (mappedSubject == null) {
                droppedFacts++;
                droppedSubjectMissing++;
                continue;
            }
            if (candidateObjectRepeatsSubjectOrPredicate(fact)) {
                droppedFacts++;
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(fact);
            String mappedObject = null;
            if ("relation_fact".equals(factKind) || "transition_fact".equals(factKind)) {
                if (relationLooksLikeAttribute(fact)) {
                    factKind = "attribute_fact";
                    mappedObject = null;
                } else {
                    mappedObject = resolveFactEndpoint(
                            fact,
                            mentionIdMap,
                            nameToMentionId,
                            entities,
                            batchIndex,
                            document,
                            "objectMentionId",
                            List.of("object", "objectName", "target", "targetName"),
                            List.of("objectType", "targetType", "entityType", "type"),
                            true
                    );
                    if (mappedObject == null) {
                        if ("relation_fact".equals(factKind)) {
                            factKind = "attribute_fact";
                        } else {
                            droppedFacts++;
                            droppedObjectMissing++;
                            continue;
                        }
                    } else if (mappedSubject.equals(mappedObject)) {
                        droppedFacts++;
                        continue;
                    }
                }
            }
            copy.put("subjectMentionId", mappedSubject);
            copy.put("objectMentionId", mappedObject == null ? "" : mappedObject);
            copy.put("factKind", factKind);
            copy.put("predicate", blankTo(stringValue(copy.get("predicate")), stringValue(copy.get("relationType"))));
            if ("attribute_fact".equals(factKind) && stringValue(copy.get("value")).isBlank()) {
                copy.put("value", firstNonBlank(
                        stringValue(copy.get("object")),
                        stringValue(copy.get("attributeValue")),
                        stringValue(copy.get("statement"))
                ));
            }
            result.add(copy);
        }
        return new FactBindingResult(
                result,
                Math.max(0, entities.size() - autoEntitiesBefore),
                droppedFacts,
                droppedSubjectMissing,
                droppedObjectMissing
        );
    }

    private boolean relationLooksLikeAttribute(Map<String, Object> fact) {
        String objectType = normalizeProvider(stringValue(fact.get("objectType")));
        if (List.of("text", "time", "date", "number", "numeric", "value").contains(objectType)) {
            return true;
        }
        if (looksLikeScalarObject(stringValue(fact.get("object")))) {
            return true;
        }
        String predicate = normalizeEntityName(blankTo(stringValue(fact.get("predicate")), stringValue(fact.get("relationType"))));
        return predicateLooksLikeAttribute(predicate);
    }

    private boolean candidateObjectRepeatsSubjectOrPredicate(Map<String, Object> fact) {
        String object = firstNonBlankFrom(fact, List.of("object", "objectName", "target", "targetName"));
        if (object.isBlank()) {
            return false;
        }
        String normalizedObject = normalizeEntityName(object);
        String normalizedSubject = normalizeEntityName(firstNonBlankFrom(fact, List.of("subject", "subjectName", "source", "sourceName")));
        String normalizedPredicate = normalizeEntityName(blankTo(stringValue(fact.get("predicate")), stringValue(fact.get("relationType"))));
        return (!normalizedSubject.isBlank() && normalizedObject.equals(normalizedSubject))
                || (!normalizedPredicate.isBlank() && normalizedObject.equals(normalizedPredicate));
    }

    private String normalizeCandidateFactKind(Map<String, Object> fact) {
        String factKind = normalizeFactKind(stringValue(fact.get("factKind")));
        String predicate = normalizeEntityName(blankTo(stringValue(fact.get("predicate")), stringValue(fact.get("relationType"))));
        if ("identity_fact".equals(factKind) && !predicateLooksLikeIdentity(predicate)) {
            return "relation_fact";
        }
        if ("attribute_fact".equals(factKind) && objectLooksLikeEntityEndpoint(fact) && !predicateLooksLikeAttribute(predicate)) {
            return "relation_fact";
        }
        return factKind;
    }

    private boolean predicateLooksLikeIdentity(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return false;
        }
        return predicate.contains("别名")
                || predicate.contains("又称")
                || predicate.contains("简称")
                || predicate.contains("全称")
                || predicate.contains("命名")
                || predicate.contains("更名")
                || predicate.contains("同名")
                || predicate.contains("sameas")
                || predicate.contains("alias");
    }

    private boolean objectLooksLikeEntityEndpoint(Map<String, Object> fact) {
        String object = stringValue(fact.get("object"));
        if (object.isBlank() || looksLikeScalarObject(object)) {
            return false;
        }
        String objectType = normalizeProvider(stringValue(fact.get("objectType")));
        return !List.of("text", "time", "date", "number", "numeric", "value").contains(objectType);
    }

    private boolean predicateLooksLikeAttribute(String predicate) {
        if (predicate.isBlank()) {
            return false;
        }
        return predicate.contains("时间")
                || predicate.contains("日期")
                || predicate.contains("编号")
                || predicate.contains("名称")
                || predicate.contains("版本")
                || predicate.contains("数值")
                || predicate.contains("数量")
                || predicate.contains("主题")
                || predicate.contains("状态");
    }

    private boolean looksLikeScalarObject(String value) {
        String normalized = stringValue(value);
        if (normalized.isBlank()) {
            return false;
        }
        String compact = Normalizer.normalize(normalized, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        if (compact.length() > 40) {
            return true;
        }
        return compact.matches("^\\d+(\\.\\d+)?(%|％|个|项|名|所|次|篇|位|人|元|万元|亿元|年|月|日|左右|以上|以下)?$")
                || compact.matches(".*\\d+(\\.\\d+)?(万|亿)?(名|个|项|所|次|篇|位|人|元|万元|亿元|%|％).*")
                || compact.matches("^[第]?[一二三四五六七八九十百千万亿\\d]+(次|个|项|名|所|年|月|日|元|万元|亿元|%|％)(左右|以上|以下)?$");
    }

    private Map<String, String> entityNameIndex(List<Map<String, Object>> entities) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Map<String, Object> entity : entities) {
            String mentionId = stringValue(entity.get("mentionId"));
            String name = stringValue(entity.get("name"));
            if (!mentionId.isBlank() && !name.isBlank()) {
                index.putIfAbsent(normalizeEntityName(name), mentionId);
            }
        }
        return index;
    }

    private String resolveFactEndpoint(
            Map<String, Object> fact,
            Map<String, String> mentionIdMap,
            Map<String, String> nameToMentionId,
            List<Map<String, Object>> entities,
            int batchIndex,
            Map<String, Object> document,
            String idKey,
            List<String> nameKeys,
            List<String> typeKeys,
            boolean createMissingEntity
    ) {
        String rawId = stringValue(fact.get(idKey));
        String mapped = mentionIdMap.get(rawId);
        if (mapped != null) {
            return mapped;
        }
        mapped = nameToMentionId.get(normalizeEntityName(rawId));
        if (mapped != null) {
            return mapped;
        }
        String endpointName = firstNonBlankFrom(fact, nameKeys);
        mapped = nameToMentionId.get(normalizeEntityName(endpointName));
        if (mapped != null) {
            return mapped;
        }
        if (!createMissingEntity || endpointName.isBlank()) {
            return null;
        }
        Map<String, Object> autoEntity = new LinkedHashMap<>();
        autoEntity.put("name", endpointName);
        autoEntity.put("type", blankTo(firstNonBlankFrom(fact, typeKeys), "Other"));
        if (isSourceArtifactEntity(autoEntity, document)) {
            return null;
        }
        String mentionId = "b" + (batchIndex + 1) + "_auto_" + (entities.size() + 1);
        autoEntity.put("mentionId", mentionId);
        autoEntity.put("definition", "");
        autoEntity.put("chunkId", stringValue(fact.get("chunkId")));
        autoEntity.put("sourceSpan", stringValue(fact.get("sourceSpan")));
        autoEntity.put("confidence", Math.min(0.7d, Math.max(0.45d, doubleValue(fact.get("confidence")))));
        autoEntity.put("createdFromRelation", true);
        entities.add(autoEntity);
        nameToMentionId.putIfAbsent(normalizeEntityName(endpointName), mentionId);
        return mentionId;
    }

    private String firstNonBlankFrom(Map<String, Object> map, List<String> keys) {
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeEntityName(String value) {
        return Normalizer.normalize(stringValue(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private record FactBindingResult(
            List<Map<String, Object>> facts,
            int autoEntities,
            int droppedFacts,
            int droppedSubjectMissing,
            int droppedObjectMissing
    ) {
    }

    private record ChunkSelectionResult(List<Map<String, Object>> chunks, Map<String, Object> summary) {
    }

    private record ChunkCandidate(Map<String, Object> chunk, int score, int chunkNo) {
    }

    private record EntityUsageMark(int factEndpointEntities, int contextEntities) {
    }

    private EntityUsageMark markEntitiesUsedByFacts(List<Map<String, Object>> entities, List<Map<String, Object>> facts) {
        Set<String> usedMentionIds = new HashSet<>();
        for (Map<String, Object> fact : facts) {
            String subjectMentionId = stringValue(fact.get("subjectMentionId"));
            if (!subjectMentionId.isBlank()) {
                usedMentionIds.add(subjectMentionId);
            }
            String factKind = normalizeFactKind(stringValue(fact.get("factKind")));
            String objectMentionId = stringValue(fact.get("objectMentionId"));
            if (!objectMentionId.isBlank() && !"attribute_fact".equals(factKind)) {
                usedMentionIds.add(objectMentionId);
            }
        }
        int factEndpointEntities = 0;
        int contextEntities = 0;
        for (Map<String, Object> entity : entities) {
            String mentionId = stringValue(entity.get("mentionId"));
            boolean factEndpoint = !mentionId.isBlank() && usedMentionIds.contains(mentionId);
            entity.put("factEndpoint", factEndpoint);
            entity.put("entityRole", factEndpoint ? "fact_endpoint" : "context_mention");
            if (factEndpoint) {
                factEndpointEntities++;
            } else {
                contextEntities++;
            }
        }
        return new EntityUsageMark(factEndpointEntities, contextEntities);
    }

    private List<Map<String, Object>> dedupeFacts(List<Map<String, Object>> facts) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> fact : facts) {
            String key = normalizeFactKind(stringValue(fact.get("factKind"))) + "|"
                    + stringValue(fact.get("subjectMentionId")) + "|"
                    + normalizeProvider(blankTo(stringValue(fact.get("predicate")), stringValue(fact.get("relationType")))) + "|"
                    + stringValue(fact.get("objectMentionId")) + "|"
                    + stringValue(fact.get("object")) + "|"
                    + stringValue(fact.get("value")) + "|"
                    + stringValue(fact.get("chunkId")) + "|"
                    + stringValue(fact.get("statement"));
            if (seen.add(key)) {
                result.add(fact);
            }
        }
        return result;
    }

    private boolean isRelationFact(Map<String, Object> fact) {
        return "relation_fact".equals(normalizeFactKind(stringValue(fact.get("factKind"))));
    }

    private boolean isAttributeFact(Map<String, Object> fact) {
        return "attribute_fact".equals(normalizeFactKind(stringValue(fact.get("factKind"))));
    }

    private boolean isTransitionFact(Map<String, Object> fact) {
        return "transition_fact".equals(normalizeFactKind(stringValue(fact.get("factKind"))));
    }

    private String normalizeFactKind(String factKind) {
        String normalized = normalizeProvider(factKind).replace("-", "_");
        return switch (normalized) {
            case "attribute", "attr", "property", "attribute_fact" -> "attribute_fact";
            case "transition", "evolution", "transition_fact" -> "transition_fact";
            case "identity", "alias", "same_as", "identity_fact" -> "identity_fact";
            case "rule", "rule_fact" -> "rule_fact";
            case "claim", "claim_fact" -> "claim_fact";
            case "task", "task_fact" -> "task_fact";
            case "event", "event_fact" -> "event_fact";
            default -> "relation_fact";
        };
    }

    private String resolveExtractionProfile(AppProperties.KnowledgeGraph config, Map<String, Object> document) {
        String configured = config.extractionProfile() == null || config.extractionProfile().isBlank()
                ? "auto"
                : config.extractionProfile().trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(configured)) {
            return configured;
        }
        String haystack = (stringValue(document.get("docType")) + " " + stringValue(document.get("title")) + " "
                + stringValue(document.get("sourceFilename"))).toLowerCase(Locale.ROOT);
        if (containsAny(haystack, config.policyProfileKeywords())) {
            return "policy";
        }
        if (containsAny(haystack, config.speechProfileKeywords())) {
            return "speech";
        }
        if (containsAny(haystack, config.reportProfileKeywords())) {
            return "report";
        }
        if (containsAny(haystack, config.projectProfileKeywords())) {
            return "project";
        }
        return "default";
    }

    private boolean containsAny(String haystack, String keywords) {
        if (haystack == null || haystack.isBlank() || keywords == null || keywords.isBlank()) {
            return false;
        }
        for (String keyword : keywords.split("[,，;；|]")) {
            String item = keyword.trim().toLowerCase(Locale.ROOT);
            if (!item.isBlank() && haystack.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private String callOpenAiCompatible(AppProperties.ExtractionLlm llm, String prompt) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("temperature", 0);
        if (llm.maxCompletionTokens() > 0) {
            body.put("max_tokens", llm.maxCompletionTokens());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是知识图谱抽取器，只能返回 JSON 对象。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(trimBaseUrl(llm.baseUrl()) + "/chat/completions", body, llm.apiKey(), llm.connectTimeoutSeconds(), llm.requestTimeoutSeconds());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM response missing message content");
        }
        return content.asText();
    }

    private String callOllama(AppProperties.ExtractionLlm llm, String prompt) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是知识图谱抽取器，只能返回 JSON 对象。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(trimOllamaBaseUrl(llm), body, null, llm.connectTimeoutSeconds(), llm.requestTimeoutSeconds());
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Ollama response missing message content");
        }
        return content.asText();
    }

    private JsonNode sendJson(String url, Map<String, Object> body, String apiKey, int connectTimeoutSeconds, int requestTimeoutSeconds) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, requestTimeoutSeconds)).toMillis());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        if (apiKey != null && !apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        byte[] requestBody = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        try (var output = connection.getOutputStream()) {
            output.write(requestBody);
        }
        int status = connection.getResponseCode();
        String responseBody;
        try (InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
            responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + " " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private Map<String, Object> parseJsonObject(String raw) throws IOException {
        String trimmed = raw == null ? "" : raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            trimmed = trimmed.substring(first, last + 1);
        }
        trimmed = repairExtractionJsonEnvelope(trimmed);
        try {
            return objectMapper.readValue(trimmed, new TypeReference<>() {
            });
        } catch (IOException firstFailure) {
            String repaired = repairExtractionJsonEnvelope(trimmed);
            if (!repaired.equals(trimmed)) {
                return objectMapper.readValue(repaired, new TypeReference<>() {
                });
            }
            throw firstFailure;
        }
    }

    private String repairExtractionJsonEnvelope(String raw) {
        String repaired = raw == null ? "" : raw;
        String previous;
        do {
            previous = repaired;
            repaired = repaired.replaceAll("]]\\s*,\\s*\"(triples|facts|relations|attributes|events)\"\\s*:", "], \"$1\":");
            repaired = repaired.replaceAll("]\\s*}\\s*]\\s*,\\s*\"(triples|facts|relations|attributes|events)\"\\s*:", "], \"$1\":");
            repaired = repaired.replaceAll("]\\s*}\\s*,\\s*\"(triples|facts|relations|attributes|events)\"\\s*:", "], \"$1\":");
            repaired = repaired.replaceAll("]\\s*}\\s*,\\s*\\{\\s*\"(triples|facts|relations|attributes|events)\"\\s*:", "], \"$1\":");
            repaired = repaired.replaceAll("(:\\s*-?\\d+(?:\\.\\d+)?)\"\\s*([,}])", "$1$2");
        } while (!previous.equals(repaired));
        return repaired;
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize graph extraction payload: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapFromObject(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private boolean parseFailure(Exception ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof JsonProcessingException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private boolean llmEnabled(AppProperties.ExtractionLlm llm) {
        return !normalizeProvider(llm.provider()).equals("disabled")
                && llm.baseUrl() != null && !llm.baseUrl().isBlank()
                && llm.model() != null && !llm.model().isBlank();
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "disabled" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trimBaseUrl(String baseUrl) {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    private String trimOllamaBaseUrl(AppProperties.ExtractionLlm llm) {
        String raw = trimBaseUrl(llm.baseUrl());
        if (raw.endsWith("/v1")) {
            raw = raw.substring(0, raw.length() - 3);
        }
        return raw + "/api/chat";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double doubleValue(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? 0.0d : Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
