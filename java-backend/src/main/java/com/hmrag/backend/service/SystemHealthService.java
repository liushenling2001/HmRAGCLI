package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public SystemHealthService(JdbcTemplate jdbcTemplate, AppProperties appProperties, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> health() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(backendCheck());
        checks.add(databaseCheck());
        checks.add(llmCheck());
        checks.add(embeddingCheck());
        boolean ok = checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("ok")));
        return Map.of(
                "status", ok ? "ok" : "warn",
                "checks", checks
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logHealthWarnings() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) health().get("checks");
        for (Map<String, Object> check : checks) {
            if (!Boolean.TRUE.equals(check.get("ok"))) {
                log.warn("System health warning [{}]: {}", check.get("name"), check.getOrDefault("error", check.get("detail")));
            }
        }
    }

    private Map<String, Object> backendCheck() {
        return Map.of("name", "backend", "ok", true, "detail", Map.of("mode", "java"));
    }

    private Map<String, Object> databaseCheck() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Map.of("name", "database", "ok", Integer.valueOf(1).equals(value), "detail", Map.of("reachable", true));
        } catch (Exception ex) {
            return check("database", false, "Database connection failed: " + ex.getMessage(), Map.of("reachable", false));
        }
    }

    private Map<String, Object> llmCheck() {
        String provider = normalizeProvider(appProperties.llm().provider());
        String baseUrl = trim(appProperties.llm().baseUrl());
        String model = trim(appProperties.llm().model());
        if ("disabled".equals(provider) || provider.isBlank()) {
            return check("llm", false, "LLM is disabled. Extraction will use heuristic fallback.", baseDetail(provider, baseUrl, model, false));
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            return check("llm", false, "LLM config is incomplete. base-url/model is missing; heuristic fallback will be used.", baseDetail(provider, baseUrl, model, false));
        }
        try {
            ProbeResult probe = modelProbe(provider, baseUrl, model);
            int status = probe.httpStatus();
            boolean ok = status >= 200 && status < 300;
            Map<String, Object> detail = baseDetail(provider, baseUrl, model, ok);
            detail.put("httpStatus", status);
            detail.put("modelFound", probe.modelFound());
            if (ok) {
                return Map.of("name", "llm", "ok", true, "detail", detail);
            }
            return check("llm", false, "LLM endpoint probe failed: HTTP " + status, detail);
        } catch (Exception ex) {
            return check("llm", false, "LLM endpoint is unreachable: " + ex.getMessage(), baseDetail(provider, baseUrl, model, false));
        }
    }

    private Map<String, Object> embeddingCheck() {
        String provider = normalizeProvider(appProperties.embedding().provider());
        String baseUrl = trim(appProperties.embedding().baseUrl());
        String model = trim(appProperties.embedding().model());
        boolean failOnError = appProperties.embedding().failOnError();
        if ("disabled".equals(provider) || provider.isBlank()) {
            return check("embedding", false, "Embedding is disabled. Search will use fallback vectors / lexical recall.", baseDetail(provider, baseUrl, model, false));
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            return check("embedding", false, "Embedding config is incomplete. base-url/model is missing; fallback vectors will be used.", baseDetail(provider, baseUrl, model, false));
        }
        try {
            ProbeResult probe = modelProbe(provider, baseUrl, model);
            int status = probe.httpStatus();
            boolean ok = status >= 200 && status < 300;
            Map<String, Object> detail = baseDetail(provider, baseUrl, model, ok);
            detail.put("httpStatus", status);
            detail.put("modelFound", probe.modelFound());
            detail.put("failOnError", failOnError);
            if (ok) {
                return Map.of("name", "embedding", "ok", true, "detail", detail);
            }
            return check("embedding", false, "Embedding endpoint probe failed: HTTP " + status, detail);
        } catch (Exception ex) {
            return check("embedding", false, "Embedding endpoint is unreachable: " + ex.getMessage(), baseDetail(provider, baseUrl, model, false));
        }
    }

    private ProbeResult modelProbe(String provider, String baseUrl, String model) throws IOException, InterruptedException {
        String url = resolveProbeUrl(provider, baseUrl);
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(probeTimeoutSeconds()).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(probeTimeoutSeconds()).toMillis());
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        int responseCode = connection.getResponseCode();
        String responseBody;
        try (InputStream stream = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream()) {
            responseBody = stream == null ? "" : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        boolean modelFound = responseCode >= 200
                && responseCode < 300
                && containsModel(responseBody, model);
        int status = modelFound ? responseCode : 503;
        return new ProbeResult(status, modelFound);
    }

    private String resolveProbeUrl(String provider, String baseUrl) {
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        if ("ollama".equals(provider)) {
            if (normalizedBase.endsWith("/v1")) {
                normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 3);
            }
            return normalizedBase + "/api/tags";
        }
        return normalizedBase + "/models";
    }

    private int probeTimeoutSeconds() {
        return 5;
    }

    private boolean containsModel(String responseBody, String model) {
        if (responseBody == null || responseBody.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return false;
            }
            for (JsonNode item : data) {
                if (model.equals(item.path("id").asText())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, Object> baseDetail(String provider, String baseUrl, String model, boolean reachable) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("provider", provider);
        detail.put("baseUrl", baseUrl);
        detail.put("model", model);
        detail.put("reachable", reachable);
        return detail;
    }

    private Map<String, Object> check(String name, boolean ok, String error, Map<String, Object> detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("ok", ok);
        item.put("error", error);
        item.put("detail", detail);
        return item;
    }

    private record ProbeResult(int httpStatus, boolean modelFound) {
    }
}
