package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeGraphExtractionService {

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
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        if (config == null || config.extractionLlm() == null || !llmEnabled(config.extractionLlm())) {
            throw new IllegalStateException("Knowledge graph extraction LLM is disabled or not configured.");
        }
        Map<String, Object> document = loadDocument(docId);
        List<Map<String, Object>> chunks = loadChunks(docId, Math.max(1, config.maxChunksPerDocument()));
        List<Map<String, Object>> units = loadKnowledgeUnits(docId, Math.max(0, config.maxKnowledgeUnitsPerDocument()));
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> batchSummaries = new ArrayList<>();
        int batchSize = Math.max(1, config.chunkBatchSize());
        int batchCount = (int) Math.ceil((double) chunks.size() / batchSize);
        int extractedChunkCount = 0;
        int failedBatchCount = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int batchIndex = i / batchSize;
            List<Map<String, Object>> batchChunks = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            List<Map<String, Object>> batchUnits = filterUnitsForChunks(units, batchChunks);
            try {
                Map<String, Object> extracted = requestExtraction(config.extractionLlm(), document, batchChunks, batchUnits, batchIndex + 1, batchCount);
                Map<String, String> mentionIdMap = new LinkedHashMap<>();
                List<Map<String, Object>> batchEntities = prefixEntities(listOfMaps(extracted.get("entities")), batchIndex, mentionIdMap);
                List<Map<String, Object>> batchRelations = prefixRelations(listOfMaps(extracted.get("relations")), mentionIdMap);
                entities.addAll(batchEntities);
                relations.addAll(batchRelations);
                events.addAll(listOfMaps(extracted.get("events")));
                extractedChunkCount += batchChunks.size();
                batchSummaries.add(Map.of(
                        "batchNo", batchIndex + 1,
                        "status", "success",
                        "chunkCount", batchChunks.size(),
                        "entityMentions", batchEntities.size(),
                        "relations", batchRelations.size()
                ));
            } catch (Exception ex) {
                failedBatchCount++;
                batchSummaries.add(Map.of(
                        "batchNo", batchIndex + 1,
                        "status", "failed",
                        "chunkCount", batchChunks.size(),
                        "error", truncate(ex.getMessage(), 500)
                ));
            }
        }
        if (!chunks.isEmpty() && extractedChunkCount == 0) {
            throw new IllegalStateException("Knowledge graph extraction failed for all batches.");
        }
        relations = dedupeRelations(relations);
        Map<String, Object> localGraph = new LinkedHashMap<>();
        localGraph.put("graphVersion", graphVersion());
        localGraph.put("sourceFileId", sourceFileId.toString());
        localGraph.put("docId", docId.toString());
        localGraph.put("document", document);
        localGraph.put("entities", entities);
        localGraph.put("relations", relations);
        localGraph.put("events", events);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategy", "chunk_batch_local_graph_document_merge_then_global_fusion");
        metadata.put("model", config.extractionLlm().model());
        metadata.put("provider", normalizeProvider(config.extractionLlm().provider()));
        metadata.put("chunkCount", chunks.size());
        metadata.put("extractedChunkCount", extractedChunkCount);
        metadata.put("failedBatchCount", failedBatchCount);
        metadata.put("batchCount", batchCount);
        metadata.put("chunkBatchSize", batchSize);
        metadata.put("batchSummaries", batchSummaries);
        metadata.put("knowledgeUnitCount", units.size());
        metadata.put("entityMentions", entities.size());
        metadata.put("relations", relations.size());
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        localGraph.put("metadata", metadata);
        return localGraph;
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

    private Map<String, Object> requestExtraction(
            AppProperties.ExtractionLlm llm,
            Map<String, Object> document,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> units,
            int batchNo,
            int batchCount
    ) {
        try {
            String prompt = buildPrompt(document, chunks, units, batchNo, batchCount);
            String raw = switch (normalizeProvider(llm.provider())) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(llm, prompt);
                case "ollama" -> callOllama(llm, prompt);
                default -> throw new IllegalStateException("Unsupported knowledge graph LLM provider: " + llm.provider());
            };
            return parseJsonObject(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Knowledge graph extraction failed: " + ex.getMessage(), ex);
        }
    }

    private String buildPrompt(Map<String, Object> document, List<Map<String, Object>> chunks, List<Map<String, Object>> units, int batchNo, int batchCount) throws JsonProcessingException {
        return """
                你是长文本知识图谱抽取器。请只返回一个 JSON 对象，不要 Markdown。
                目标：从当前 chunk 批次中抽取“局部图候选”，不要跨文档合并。后续系统会做文档级合并和全局融合。
                当前批次：%s / %s。

                必须返回字段：
                {
                  "entities": [
                    {
                      "mentionId": "稳定字符串，建议 e1/e2...",
                      "name": "实体名",
                      "type": "Organization|Person|Location|Project|Policy|Document|Indicator|Concept|Event|System|Other",
                      "definition": "该文档语境下含义，无法判断则空字符串",
                      "validFrom": "YYYY-MM-DD 或空字符串",
                      "validTo": "YYYY-MM-DD 或空字符串",
                      "chunkId": "证据chunkId",
                      "knowledgeUnitId": "可为空",
                      "sourceSpan": "可为空",
                      "confidence": 0.0
                    }
                  ],
                  "relations": [
                    {
                      "subjectMentionId": "entities中的mentionId",
                      "objectMentionId": "entities中的mentionId",
                      "relationType": "belongs_to|located_in|responsible_for|defines|requires|replaces|refers_to|part_of|causes|affects|measured_by|same_as|related_to",
                      "statement": "忠于原文的关系陈述",
                      "validFrom": "YYYY-MM-DD 或空字符串",
                      "validTo": "YYYY-MM-DD 或空字符串",
                      "chunkId": "证据chunkId",
                      "knowledgeUnitId": "可为空",
                      "sourceSpan": "可为空",
                      "confidence": 0.0
                    }
                  ],
                  "events": []
                }

                规则：
                1. 不要编造实体或关系。
                2. 同名实体如果在不同时间阶段含义不同，保留不同 mention，并写 validFrom/validTo/definition。
                3. 每个实体和关系必须带 chunkId 作为证据。
                4. 关系应连接 mention，不要直接做全局归一化。
                5. 当前批次内尽量完整覆盖所有 chunk：机构、人员、系统、项目、政策、指标、关键概念、约束、能力、时间阶段都应抽取。

                文档：
                %s

                chunks：
                %s

                knowledge_units：
                %s
                """.formatted(
                batchNo,
                batchCount,
                objectMapper.writeValueAsString(document),
                objectMapper.writeValueAsString(chunks),
                objectMapper.writeValueAsString(units)
        );
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

    private List<Map<String, Object>> prefixEntities(List<Map<String, Object>> entities, int batchIndex, Map<String, String> mentionIdMap) {
        List<Map<String, Object>> result = new ArrayList<>();
        int fallbackIndex = 0;
        for (Map<String, Object> entity : entities) {
            Map<String, Object> copy = new LinkedHashMap<>(entity);
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

    private List<Map<String, Object>> dedupeRelations(List<Map<String, Object>> relations) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> relation : relations) {
            String key = stringValue(relation.get("subjectMentionId")) + "|"
                    + normalizeProvider(stringValue(relation.get("relationType"))) + "|"
                    + stringValue(relation.get("objectMentionId")) + "|"
                    + stringValue(relation.get("chunkId")) + "|"
                    + stringValue(relation.get("statement"));
            if (seen.add(key)) {
                result.add(relation);
            }
        }
        return result;
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
        return objectMapper.readValue(trimmed, new TypeReference<>() {
        });
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
}
