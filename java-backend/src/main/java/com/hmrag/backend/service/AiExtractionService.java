package com.hmrag.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.SourceFile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiExtractionService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final DowngradeApprovalService downgradeApprovalService;

    public AiExtractionService(AppProperties appProperties, ObjectMapper objectMapper, DowngradeApprovalService downgradeApprovalService) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.downgradeApprovalService = downgradeApprovalService;
    }

    public ExtractionResult extract(SourceFile file, String title, String content, String chunkType) {
        ExtractionResult fallback = heuristicExtract(file, title, content, chunkType);
        if (!isEnabled()) {
            if (downgradeApprovalService.isLlmApproved(file.getDataSourceId())) {
                return approvedFallback(fallback, "LLM_DISABLED_OR_NOT_CONFIGURED");
            }
            throw new ManualApprovalRequiredException("LLM is disabled or not configured. Manual approval is required before downgrade.");
        }
        try {
            ExtractionResult ai = requestAiExtraction(file, title, content, chunkType);
            return ai.mergeFallback(fallback);
        } catch (Exception ex) {
            if (downgradeApprovalService.isLlmApproved(file.getDataSourceId())) {
                return approvedFallback(fallback, ex.getMessage());
            }
            throw new ManualApprovalRequiredException("LLM extraction failed. Manual approval is required before downgrade. " + ex.getMessage());
        }
    }

    private ExtractionResult approvedFallback(ExtractionResult fallback, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(fallback.metadata());
        metadata.put("aiFallbackReason", reason);
        metadata.put("aiProvider", provider());
        metadata.put("aiFallback", true);
        metadata.put("aiApprovedDowngrade", true);
        return new ExtractionResult(
                fallback.unitType(),
                fallback.title(),
                fallback.subject(),
                fallback.indicator(),
                fallback.normalizedText(),
                metadata
        );
    }

    private ExtractionResult requestAiExtraction(SourceFile file, String title, String content, String chunkType) throws IOException, InterruptedException {
        throwIfCancelled();
        String payload = prompt(file, title, content, chunkType);
        String provider = provider();
        String raw = switch (provider) {
            case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(payload);
            case "ollama" -> callOllama(payload);
            default -> throw new IllegalStateException("Unsupported llm provider: " + provider);
        };
        Map<String, Object> parsed = parseJsonObject(raw);
        String unitType = textValue(parsed.get("unitType"));
        String aiTitle = textValue(parsed.get("title"));
        String subject = textValue(parsed.get("subject"));
        String indicator = textValue(parsed.get("indicator"));
        String summary = textValue(parsed.get("summary"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aiProvider", provider);
        metadata.put("aiModel", appProperties.llm().model());
        metadata.put("aiEnabled", true);
        if (parsed.get("keywords") instanceof List<?> keywords && !keywords.isEmpty()) {
            metadata.put("keywords", new ArrayList<>(keywords));
        }
        return new ExtractionResult(unitType, aiTitle, subject, indicator, normalize(summary), metadata);
    }

    private String callOpenAiCompatible(String prompt) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appProperties.llm().model());
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是文档结构抽取器，只能返回一个JSON对象，不要返回Markdown。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, appProperties.llm().apiKey());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM response missing message content");
        }
        return content.asText();
    }

    private String callOllama(String prompt) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appProperties.llm().model());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是文档结构抽取器，只能返回一个JSON对象，不要返回Markdown。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Ollama response missing message content");
        }
        return content.asText();
    }

    private JsonNode sendJson(String url, Map<String, Object> body, String apiKey) throws IOException, InterruptedException {
        throwIfCancelled();
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, appProperties.llm().connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, appProperties.llm().requestTimeoutSeconds())).toMillis());
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
            throw new IllegalStateException("LLM request failed: HTTP " + status + " " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
    }

    private ExtractionResult heuristicExtract(SourceFile file, String title, String content, String chunkType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aiEnabled", false);
        metadata.put("relativePath", file.getRelativePath());
        metadata.put("chunkType", chunkType);
        metadata.put("keywords", heuristicKeywords(content));
        return new ExtractionResult(
                inferUnitType(file, chunkType),
                title,
                deriveSubject(file, title),
                deriveIndicator(content),
                normalize(content),
                metadata
        );
    }

    private String prompt(SourceFile file, String title, String content, String chunkType) {
        String body = truncate(content, 2200);
        return """
                请从下面的中文办公文档片段中抽取结构信息，并返回一个 JSON 对象。
                只允许这些字段：unitType,title,subject,indicator,summary,keywords
                要求：
                1. unitType 只能取 rule, statement, summary, table_record
                2. title 最多 80 字
                3. summary 最多 180 字，保留原意，不要编造
                4. keywords 返回最多 6 个关键词数组
                5. 如果无法判断 indicator，就返回空字符串

                文件名：%s
                相对路径：%s
                chunkType：%s
                原始标题：%s
                内容：
                %s
                """.formatted(
                safe(file.getFileName()),
                safe(file.getRelativePath()),
                safe(chunkType),
                safe(title),
                body
        );
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

    private List<String> heuristicKeywords(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        String[] tokens = normalized.split("[，。；：、\\s|]+");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokens) {
            String clean = token.trim();
            if (clean.length() < 2 || clean.length() > 12) {
                continue;
            }
            counts.merge(clean, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(6)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String inferUnitType(SourceFile file, String chunkType) {
        if ("table_row".equals(chunkType)) {
            return "table_record";
        }
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".md" -> "summary";
            case ".doc", ".docx", ".pdf" -> "rule";
            default -> "statement";
        };
    }

    private String deriveSubject(SourceFile file, String title) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        String fileName = file.getFileName();
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String deriveIndicator(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        if (content.contains("标准")) {
            return "标准";
        }
        if (content.contains("要求")) {
            return "要求";
        }
        if (content.contains("期限")) {
            return "期限";
        }
        if (content.contains("金额")) {
            return "金额";
        }
        if (content.contains("|")) {
            return "表格记录";
        }
        return null;
    }

    private boolean isEnabled() {
        return !provider().equals("disabled")
                && !baseUrl().isBlank()
                && !appProperties.llm().model().isBlank();
    }

    private String provider() {
        return appProperties.llm().provider() == null
                ? "disabled"
                : appProperties.llm().provider().trim().toLowerCase(Locale.ROOT);
    }

    private String baseUrl() {
        String raw = appProperties.llm().baseUrl() == null ? "" : appProperties.llm().baseUrl().replaceAll("/+$", "");
        if ("ollama".equals(provider()) && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public record ExtractionResult(
            String unitType,
            String title,
            String subject,
            String indicator,
            String normalizedText,
            Map<String, Object> metadata
    ) {
        ExtractionResult mergeFallback(ExtractionResult fallback) {
            Map<String, Object> mergedMetadata = new LinkedHashMap<>(fallback.metadata());
            if (metadata != null) {
                mergedMetadata.putAll(metadata);
            }
            return new ExtractionResult(
                    blankTo(fallback.unitType(), unitType),
                    blankTo(fallback.title(), title),
                    blankTo(fallback.subject(), subject),
                    blankTo(fallback.indicator(), indicator),
                    blankTo(fallback.normalizedText(), normalizedText),
                    mergedMetadata
            );
        }

        private static String blankTo(String fallback, String current) {
            return current == null || current.isBlank() ? fallback : current;
        }
    }
}
