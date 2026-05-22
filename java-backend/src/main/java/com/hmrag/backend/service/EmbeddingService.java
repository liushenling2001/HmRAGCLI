package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.SourceFile;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class EmbeddingService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final DowngradeApprovalService downgradeApprovalService;

    public EmbeddingService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            DowngradeApprovalService downgradeApprovalService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.downgradeApprovalService = downgradeApprovalService;
    }

    public EmbeddingStats materializeEmbeddings(SourceFile file) {
        return materializeEmbeddings(file, ProgressListener.NOOP);
    }

    public EmbeddingStats materializeEmbeddings(SourceFile file, ProgressListener progressListener) {
        UUID docId = file.getDocId();
        if (docId == null) {
            throw new IllegalStateException("Document is missing for source file " + file.getId());
        }

        List<VectorTarget> chunkTargets = jdbcTemplate.query(
                """
                SELECT id, title, content, chunk_type
                FROM chunks
                WHERE doc_id = :docId
                ORDER BY chunk_no ASC
                """,
                new MapSqlParameterSource("docId", docId),
                (rs, rowNum) -> new VectorTarget(
                        UUID.fromString(rs.getString("id")),
                        combineChunkText(rs.getString("title"), rs.getString("chunk_type"), rs.getString("content")),
                        rs.getString("title"),
                        "chunk"
                )
        );
        chunkTargets = selectVectorTargets(chunkTargets, file, "chunk");

        List<VectorTarget> unitTargets = jdbcTemplate.query(
                """
                SELECT id, title, content, normalized_text, subject, indicator
                FROM knowledge_units
                WHERE doc_id = :docId
                ORDER BY created_at ASC
                """,
                new MapSqlParameterSource("docId", docId),
                (rs, rowNum) -> new VectorTarget(
                        UUID.fromString(rs.getString("id")),
                        combineKnowledgeUnitText(
                                rs.getString("title"),
                                rs.getString("subject"),
                                rs.getString("indicator"),
                                rs.getString("normalized_text"),
                                rs.getString("content")
                        ),
                        rs.getString("title"),
                        "knowledge_unit"
                )
        );
        unitTargets = selectVectorTargets(unitTargets, file, "knowledge_unit");

        ProgressListener listener = progressListener == null ? ProgressListener.NOOP : progressListener;
        int total = chunkTargets.size() + unitTargets.size();
        listener.onProgress(0, total);
        int completed = 0;
        completed = embedIntoTable("chunk_embeddings", "chunk_id", chunkTargets, listener, completed, total, file);
        embedIntoTable("knowledge_unit_embeddings", "knowledge_unit_id", unitTargets, listener, completed, total, file);
        return new EmbeddingStats(chunkTargets.size(), unitTargets.size(), providerLabel(), modelLabel());
    }

    private List<VectorTarget> selectVectorTargets(List<VectorTarget> targets, SourceFile file, String kind) {
        if (targets.isEmpty()) {
            return List.of();
        }
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase(Locale.ROOT);
        int cap = switch (ext) {
            case ".doc", ".docx" -> "chunk".equals(kind)
                    ? positiveOrDefault(appProperties.embedding().vectorTargetMaxChunkDoc(), 80)
                    : positiveOrDefault(appProperties.embedding().vectorTargetMaxUnitDoc(), 40);
            case ".pdf" -> "chunk".equals(kind)
                    ? positiveOrDefault(appProperties.embedding().vectorTargetMaxChunkPdf(), 120)
                    : positiveOrDefault(appProperties.embedding().vectorTargetMaxUnitPdf(), 60);
            default -> "chunk".equals(kind)
                    ? positiveOrDefault(appProperties.embedding().vectorTargetMaxChunkDefault(), 70)
                    : positiveOrDefault(appProperties.embedding().vectorTargetMaxUnitDefault(), 35);
        };
        double ratio = positiveOrDefault(appProperties.embedding().vectorTargetRatio(), 0.10d);
        int proportional = (int) Math.ceil(targets.size() * ratio);
        int minSetting = "chunk".equals(kind)
                ? positiveOrDefault(appProperties.embedding().vectorTargetMinChunk(), 8)
                : positiveOrDefault(appProperties.embedding().vectorTargetMinUnit(), 4);
        int minFloor = Math.min(minSetting, targets.size());
        int limit = Math.max(proportional, minFloor);
        limit = Math.min(limit, cap);
        limit = Math.min(limit, targets.size());
        return targets.stream()
                .sorted((left, right) -> Double.compare(vectorTargetScore(right), vectorTargetScore(left)))
                .limit(Math.max(1, limit))
                .toList();
    }

    private double vectorTargetScore(VectorTarget target) {
        if (target == null) {
            return 0.0;
        }
        String text = target.text() == null ? "" : target.text();
        String title = target.title() == null ? "" : target.title();
        int len = text.length();
        double score = 0.0;
        if (!title.isBlank()) {
            score += 1.0;
        }
        if (len >= 80 && len <= 900) {
            score += 1.2;
        } else if (len > 900) {
            score += 0.4;
        } else if (len >= 30) {
            score += 0.6;
        }
        if ("knowledge_unit".equals(target.kind())) {
            score += 0.5;
        }
        return score;
    }

    public List<Double> embedQuery(String text) {
        List<List<Double>> vectors = embedTexts(List.of(text == null ? "" : text));
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }

    private int embedIntoTable(
            String tableName,
            String fkColumn,
            List<VectorTarget> targets,
            ProgressListener listener,
            int completed,
            int total,
            SourceFile file
    ) {
        if (targets.isEmpty()) {
            return completed;
        }
        int batchSize = Math.max(1, appProperties.embedding().batchSize());
        for (int from = 0; from < targets.size(); from += batchSize) {
            throwIfCancelled();
            listener.onProgress(completed, total);
            int to = Math.min(targets.size(), from + batchSize);
            List<VectorTarget> batch = targets.subList(from, to);
            List<List<Double>> vectors = embedTexts(batch.stream().map(VectorTarget::text).toList(), file);
            for (int i = 0; i < batch.size(); i++) {
                throwIfCancelled();
                VectorTarget target = batch.get(i);
                if (i >= vectors.size()) {
                    throw new IllegalStateException("Embedding response size mismatch: expected " + batch.size() + " but got " + vectors.size());
                }
                List<Double> vector = vectors.get(i);
                if (vector == null || vector.isEmpty()) {
                    throw new IllegalStateException("Embedding vector is empty for target " + target.id());
                }
                upsertEmbedding(tableName, fkColumn, target.id(), vector);
                completed++;
                listener.onProgress(completed, total);
            }
        }
        return completed;
    }

    private void upsertEmbedding(String tableName, String fkColumn, UUID targetId, List<Double> vector) {
        String sql = """
                INSERT INTO %s (id, %s, embedding_model, embedding_provider, dimensions, embedding_vector, created_at, updated_at)
                VALUES (:id, :targetId, :embeddingModel, :embeddingProvider, :dimensions, CAST(:vectorLiteral AS vector), :now, :now)
                ON CONFLICT (%s)
                DO UPDATE SET
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_provider = EXCLUDED.embedding_provider,
                    dimensions = EXCLUDED.dimensions,
                    embedding_vector = EXCLUDED.embedding_vector,
                    updated_at = EXCLUDED.updated_at
                """.formatted(tableName, fkColumn, fkColumn);
        String vectorLiteral = vectorLiteral(vector);
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("targetId", targetId)
                        .addValue("embeddingModel", modelLabel())
                        .addValue("embeddingProvider", providerLabel())
                        .addValue("dimensions", vector.size())
                        .addValue("vectorLiteral", vectorLiteral)
                        .addValue("now", OffsetDateTime.now())
        );
    }

    private List<List<Double>> embedTexts(List<String> texts) {
        return embedTexts(texts, null);
    }

    private List<List<Double>> embedTexts(List<String> texts, SourceFile file) {
        List<String> normalized = texts.stream()
                .map(text -> truncate(text == null ? "" : text.trim(), 6000))
                .toList();
        String provider = provider();
        if ("disabled".equals(provider) || provider.isBlank()) {
            if (file != null && downgradeApprovalService.isEmbeddingApproved(file.getDataSourceId())) {
                return fallbackEmbedTexts(normalized);
            }
            throw new ManualApprovalRequiredException("Embedding is disabled or not configured. Manual approval is required before downgrade.");
        }
        try {
            return switch (provider) {
                case "ollama" -> ollamaEmbeddings(normalized);
                case "openai_compatible", "openai-compatible" -> openAiCompatibleEmbeddings(normalized);
                default -> throw new IllegalStateException("Unsupported embedding provider: " + provider);
            };
        } catch (Exception ex) {
            if (file != null && downgradeApprovalService.isEmbeddingApproved(file.getDataSourceId())) {
                return fallbackEmbedTexts(normalized);
            }
            throw new ManualApprovalRequiredException("Embedding request failed. Manual approval is required before downgrade. " + ex.getMessage());
        }
    }

    private List<List<Double>> openAiCompatibleEmbeddings(List<String> texts) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appProperties.embedding().model());
        body.put("input", texts);
        JsonNode root = sendEmbeddingJson(baseUrl() + "/embeddings", body, appProperties.embedding().apiKey());
        List<List<Double>> vectors = new ArrayList<>();
        List<JsonNode> items = new ArrayList<>();
        root.path("data").forEach(items::add);
        items.sort((left, right) -> Integer.compare(left.path("index").asInt(0), right.path("index").asInt(0)));
        for (JsonNode item : items) {
            vectors.add(toVector(item.path("embedding")));
        }
        return vectors;
    }

    private List<List<Double>> ollamaEmbeddings(List<String> texts) throws IOException, InterruptedException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", appProperties.embedding().model());
            body.put("input", texts);
            JsonNode root = sendEmbeddingJson(baseUrl() + "/api/embed", body, null);
            List<List<Double>> vectors = new ArrayList<>();
            root.path("embeddings").forEach(node -> vectors.add(toVector(node)));
            if (!vectors.isEmpty()) {
                return vectors;
            }
        } catch (Exception ignored) {
        }

        List<List<Double>> vectors = new ArrayList<>();
        for (String text : texts) {
            List<Double> vector = null;
            Exception lastError = null;
            for (String candidate : truncationCandidates(text)) {
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", appProperties.embedding().model());
                    body.put("prompt", candidate);
                    JsonNode root = sendEmbeddingJson(baseUrl() + "/api/embeddings", body, null);
                    vector = toVector(root.path("embedding"));
                    if (!vector.isEmpty()) {
                        break;
                    }
                } catch (Exception ex) {
                    lastError = ex;
                }
            }
            if (vector == null || vector.isEmpty()) {
                if (lastError instanceof IOException io) {
                    throw io;
                }
                if (lastError instanceof InterruptedException ie) {
                    throw ie;
                }
                throw new IllegalStateException(lastError == null ? "Ollama embedding request failed" : lastError.getMessage(), lastError);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private JsonNode sendEmbeddingJson(String url, Map<String, Object> body, String apiKey) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, appProperties.embedding().connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, appProperties.embedding().requestTimeoutSeconds())).toMillis());
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
            throw new IllegalStateException("Embedding request failed: HTTP " + status + " " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
    }

    private List<List<Double>> fallbackEmbedTexts(List<String> texts) {
        int dims = Math.max(8, appProperties.embedding().fallbackDimensions());
        List<List<Double>> vectors = new ArrayList<>();
        for (String text : texts) {
            double[] raw = new double[dims];
            String normalized = text == null ? "" : text;
            int limit = Math.min(normalized.length(), 2048);
            for (int i = 0; i < limit; i++) {
                raw[i % dims] += (normalized.charAt(i) % 97) / 97.0;
            }
            double norm = 0.0;
            for (double value : raw) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm == 0.0) {
                norm = 1.0;
            }
            List<Double> vector = new ArrayList<>(dims);
            for (double value : raw) {
                vector.add(value / norm);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private List<String> truncationCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        String normalized = text == null ? "" : text;
        candidates.add(normalized);
        for (int limit : List.of(1800, 1400, 1000, 700)) {
            if (normalized.length() > limit) {
                candidates.add(normalized.substring(0, limit));
            }
        }
        return candidates.stream().distinct().toList();
    }

    private List<Double> toVector(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asDouble()));
        return values;
    }

    private String combineKnowledgeUnitText(String title, String subject, String indicator, String normalizedText, String content) {
        return joinNonBlank(title, subject, indicator, normalizedText, content);
    }

    private String combineChunkText(String title, String chunkType, String content) {
        return joinNonBlank(title, chunkType, content);
    }

    private String joinNonBlank(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value.trim());
        }
        return truncate(builder.toString(), 6000);
    }

    private String vectorLiteral(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return "[]";
        }
        return "[" + vector.stream()
                .map(value -> Double.toString(value == null ? 0.0d : value))
                .reduce((left, right) -> left + "," + right)
                .orElse("") + "]";
    }

    private String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String provider() {
        return appProperties.embedding().provider() == null
                ? "disabled"
                : appProperties.embedding().provider().trim().toLowerCase(Locale.ROOT);
    }

    private String providerLabel() {
        return switch (provider()) {
            case "openai-compatible" -> "openai_compatible";
            case "" -> "fallback";
            default -> provider().equals("disabled") ? "fallback" : provider();
        };
    }

    private String modelLabel() {
        String model = appProperties.embedding().model();
        return model == null || model.isBlank() ? "fallback-charhash" : model;
    }

    private String baseUrl() {
        String raw = appProperties.embedding().baseUrl() == null ? "" : appProperties.embedding().baseUrl().replaceAll("/+$", "");
        if ("ollama".equals(provider()) && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private double positiveOrDefault(double value, double fallback) {
        return value > 0.0d ? value : fallback;
    }

    public record EmbeddingStats(
            int chunkCount,
            int knowledgeUnitCount,
            String provider,
            String model
    ) {
    }

    private record VectorTarget(UUID id, String text, String title, String kind) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NOOP = (completed, total) -> {
        };

        void onProgress(int completed, int total);
    }
}
