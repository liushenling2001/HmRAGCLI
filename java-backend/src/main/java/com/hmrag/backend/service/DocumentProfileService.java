package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentProfileService {

    private static final String PROFILE_VERSION = "rule-profile-v1";
    private static final int CHUNK_SAMPLE_LIMIT = 240;
    private static final int KNOWLEDGE_UNIT_SAMPLE_LIMIT = 160;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentProfileService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> refreshProfile(UUID docId) {
        if (jdbcTemplate == null || docId == null) {
            return Map.of();
        }
        Map<String, Object> document = loadDocument(docId);
        List<Map<String, Object>> chunks = loadChunks(docId);
        List<Map<String, Object>> units = loadKnowledgeUnits(docId);
        Map<String, Object> profile = buildProfile(document, chunks, units);
        Map<String, Object> metadata = parseMap(String.valueOf(document.getOrDefault("metadataJson", "{}")));
        metadata.put("documentProfile", profile);
        jdbcTemplate.update(
                """
                UPDATE documents
                SET metadata_json = CAST(:metadataJson AS jsonb),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :docId
                """,
                new MapSqlParameterSource()
                        .addValue("docId", docId)
                        .addValue("metadataJson", json(metadata))
        );
        return profile;
    }

    public Map<String, Object> buildProfile(
            Map<String, Object> document,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> knowledgeUnits
    ) {
        Map<String, Object> doc = document == null ? Map.of() : document;
        List<Map<String, Object>> chunkRows = chunks == null ? List.of() : chunks;
        List<Map<String, Object>> unitRows = knowledgeUnits == null ? List.of() : knowledgeUnits;

        int chunkCount = intValue(doc.get("chunkCount"));
        if (chunkCount <= 0) {
            chunkCount = chunkRows.stream().mapToInt(row -> Math.max(1, intValue(row.get("totalChunks")))).max().orElse(chunkRows.size());
        }
        int unitCount = intValue(doc.get("knowledgeUnitCount"));
        if (unitCount <= 0) {
            unitCount = unitRows.stream().mapToInt(row -> Math.max(1, intValue(row.get("totalKnowledgeUnits")))).max().orElse(unitRows.size());
        }

        LinkedHashSet<String> headings = new LinkedHashSet<>();
        int tableChunks = 0;
        int listChunks = 0;
        int narrativeChunks = 0;
        int digitChars = 0;
        int contentChars = 0;
        StringBuilder text = new StringBuilder();
        appendText(text, doc.get("title"));
        appendText(text, doc.get("sourceFilename"));
        appendText(text, doc.get("sourceFile"));
        appendText(text, doc.get("sourceOrg"));
        appendText(text, doc.get("author"));
        for (Map<String, Object> chunk : chunkRows) {
            String title = trimToNull(stringValue(chunk.get("title")));
            if (title != null) {
                headings.add(title);
                appendText(text, title);
            }
            String chunkType = stringValue(chunk.get("chunkType")).toLowerCase(Locale.ROOT);
            if (chunkType.contains("table")) {
                tableChunks++;
            } else if (chunkType.contains("list")) {
                listChunks++;
            } else {
                narrativeChunks++;
            }
            String content = stringValue(chunk.get("content"));
            contentChars += content.length();
            digitChars += countDigits(content);
            appendText(text, truncate(content, 800));
        }
        int subjectCount = 0;
        int indicatorCount = 0;
        int valueCount = 0;
        for (Map<String, Object> unit : unitRows) {
            if (trimToNull(stringValue(unit.get("subject"))) != null) {
                subjectCount++;
            }
            if (trimToNull(stringValue(unit.get("indicator"))) != null) {
                indicatorCount++;
            }
            if (trimToNull(stringValue(unit.get("valueText"))) != null
                    || trimToNull(stringValue(unit.get("unitName"))) != null) {
                valueCount++;
            }
            appendText(text, unit.get("title"));
            appendText(text, unit.get("subject"));
            appendText(text, unit.get("indicator"));
            appendText(text, unit.get("content"));
        }

        int sampleChunkCount = Math.max(1, chunkRows.size());
        double tableRatio = round(tableChunks * 1.0d / sampleChunkCount);
        double headingRatio = round(headings.size() * 1.0d / sampleChunkCount);
        double numericDensity = contentChars <= 0 ? 0.0d : round(digitChars * 1.0d / contentChars);
        int averageChunkChars = sampleChunkCount <= 0 ? 0 : contentChars / sampleChunkCount;

        String haystack = normalizeForMatch(text.toString());
        Map<String, Integer> scores = scoreDocumentTypes(haystack, doc, tableRatio, numericDensity, headingRatio, chunkCount);
        String docType = chooseDocType(scores);
        String structureType = chooseStructureType(docType, tableRatio, headingRatio, averageChunkChars, narrativeChunks, listChunks);
        String knowledgeDensity = chooseKnowledgeDensity(chunkCount, unitCount, scores, numericDensity, tableRatio);
        String graphSuitability = chooseGraphSuitability(docType, structureType, knowledgeDensity, scores);
        String recommendedStrategy = recommendedStrategy(docType, graphSuitability);
        double confidence = confidence(scores, tableRatio, headingRatio, chunkCount, unitCount);

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("chunkCount", chunkCount);
        signals.put("sampledChunkCount", chunkRows.size());
        signals.put("knowledgeUnitCount", unitCount);
        signals.put("tableRatio", tableRatio);
        signals.put("headingRatio", headingRatio);
        signals.put("numericDensity", numericDensity);
        signals.put("averageChunkChars", averageChunkChars);
        signals.put("headingCount", headings.size());
        signals.put("subjectCount", subjectCount);
        signals.put("indicatorCount", indicatorCount);
        signals.put("valueCount", valueCount);
        signals.put("scores", scores);
        signals.put("sectionSamples", headings.stream().limit(18).toList());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("version", PROFILE_VERSION);
        profile.put("docType", docType);
        profile.put("structureType", structureType);
        profile.put("knowledgeDensity", knowledgeDensity);
        profile.put("graphSuitability", graphSuitability);
        profile.put("recommendedStrategy", recommendedStrategy);
        profile.put("confidence", confidence);
        profile.put("llmReviewRequired", confidence < 0.62d && !"evidence_only".equals(recommendedStrategy));
        profile.put("signals", signals);
        profile.put("routingNotes", routingNotes(docType, structureType, graphSuitability, recommendedStrategy));
        profile.put("generatedAt", OffsetDateTime.now().toString());
        return profile;
    }

    private Map<String, Object> loadDocument(UUID docId) {
        return jdbcTemplate.query(
                """
                SELECT d.id, d.title, d.doc_type, d.source_file, d.source_filename,
                       d.source_org, d.author, d.metadata_json::text AS metadata_json,
                       (SELECT count(*) FROM chunks c WHERE c.doc_id = d.id) AS chunk_count,
                       (SELECT count(*) FROM knowledge_units ku WHERE ku.doc_id = d.id) AS knowledge_unit_count
                FROM documents d
                WHERE d.id = :docId
                LIMIT 1
                """,
                new MapSqlParameterSource("docId", docId),
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException("Document not found for profile: " + docId);
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("docType", rs.getString("doc_type"));
                    item.put("sourceFile", rs.getString("source_file"));
                    item.put("sourceFilename", rs.getString("source_filename"));
                    item.put("sourceOrg", rs.getString("source_org"));
                    item.put("author", rs.getString("author"));
                    item.put("metadataJson", rs.getString("metadata_json"));
                    item.put("chunkCount", rs.getInt("chunk_count"));
                    item.put("knowledgeUnitCount", rs.getInt("knowledge_unit_count"));
                    return item;
                }
        );
    }

    private List<Map<String, Object>> loadChunks(UUID docId) {
        return jdbcTemplate.query(
                """
                SELECT chunk_no, chunk_type, title, left(content, 1600) AS content,
                       count(*) OVER () AS total_chunks
                FROM chunks
                WHERE doc_id = :docId
                ORDER BY chunk_no ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("docId", docId).addValue("limit", CHUNK_SAMPLE_LIMIT),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkNo", rs.getInt("chunk_no"));
                    item.put("chunkType", rs.getString("chunk_type"));
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("totalChunks", rs.getInt("total_chunks"));
                    return item;
                }
        );
    }

    private List<Map<String, Object>> loadKnowledgeUnits(UUID docId) {
        return jdbcTemplate.query(
                """
                SELECT unit_type, title, content, subject, indicator, value_text, unit_name,
                       count(*) OVER () AS total_knowledge_units
                FROM knowledge_units
                WHERE doc_id = :docId
                ORDER BY created_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("docId", docId).addValue("limit", KNOWLEDGE_UNIT_SAMPLE_LIMIT),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("unitType", rs.getString("unit_type"));
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("subject", rs.getString("subject"));
                    item.put("indicator", rs.getString("indicator"));
                    item.put("valueText", rs.getString("value_text"));
                    item.put("unitName", rs.getString("unit_name"));
                    item.put("totalKnowledgeUnits", rs.getInt("total_knowledge_units"));
                    return item;
                }
        );
    }

    private Map<String, Integer> scoreDocumentTypes(
            String haystack,
            Map<String, Object> document,
            double tableRatio,
            double numericDensity,
            double headingRatio,
            int chunkCount
    ) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("paper", termScore(haystack, List.of("摘要", "关键词", "参考文献", "实验", "算法", "模型", "方法", "研究对象", "结论", "文献综述", "学位论文")));
        scores.put("regulation", termScore(haystack, List.of("办法", "条例", "规定", "细则", "适用范围", "责任", "应当", "不得", "生效", "条款", "管理办法", "实施")));
        scores.put("project_plan", termScore(haystack, List.of("建设内容", "建设目标", "项目", "系统", "平台", "模块", "接口", "性能", "验收", "一期", "二期", "升级", "应用单位")));
        scores.put("speech", termScore(haystack, List.of("同志们", "会议", "强调", "指出", "部署", "贯彻", "落实", "讲话", "报告会", "精神")));
        scores.put("report", termScore(haystack, List.of("调研", "分析", "报告", "现状", "建议", "问题", "对策", "评估", "总结")));
        scores.put("technical_spec", termScore(haystack, List.of("接口", "架构", "数据库", "吞吐", "响应时间", "安全", "性能指标", "功能要求", "技术路线", "部署")));
        int tableScore = 0;
        String docType = stringValue(document.get("docType")).toLowerCase(Locale.ROOT);
        String filename = stringValue(document.get("sourceFilename")).toLowerCase(Locale.ROOT);
        if ("excel".equals(docType) || filename.endsWith(".xls") || filename.endsWith(".xlsx")) {
            tableScore += 5;
        }
        tableScore += tableRatio >= 0.35d ? 4 : tableRatio >= 0.18d ? 2 : 0;
        tableScore += numericDensity >= 0.18d ? 2 : numericDensity >= 0.08d ? 1 : 0;
        tableScore += termScore(haystack, List.of("合计", "金额", "单价", "数量", "指标", "清单", "明细", "表格"));
        scores.put("table", tableScore);
        if (chunkCount >= 20 && headingRatio >= 0.18d) {
            scores.merge("report", 1, Integer::sum);
        }
        return scores;
    }

    private String chooseDocType(Map<String, Integer> scores) {
        List<Map.Entry<String, Integer>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .toList();
        if (ranked.isEmpty() || ranked.get(0).getValue() < 2) {
            return "unknown";
        }
        String top = ranked.get(0).getKey();
        if ("table".equals(top) && ranked.get(0).getValue() >= 4) {
            return "table";
        }
        return top;
    }

    private String chooseStructureType(String docType, double tableRatio, double headingRatio, int averageChunkChars, int narrativeChunks, int listChunks) {
        if ("table".equals(docType) || tableRatio >= 0.35d) {
            return "table_heavy";
        }
        if (headingRatio >= 0.18d || listChunks >= 4) {
            return "sectioned";
        }
        if (averageChunkChars >= 180 && narrativeChunks >= listChunks) {
            return "narrative";
        }
        return "mixed";
    }

    private String chooseKnowledgeDensity(int chunkCount, int unitCount, Map<String, Integer> scores, double numericDensity, double tableRatio) {
        int topScore = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (chunkCount >= 30 || unitCount >= 12 || topScore >= 8 || (tableRatio >= 0.25d && numericDensity >= 0.08d)) {
            return "high";
        }
        if (chunkCount >= 8 || unitCount >= 4 || topScore >= 4) {
            return "medium";
        }
        return "low";
    }

    private String chooseGraphSuitability(String docType, String structureType, String knowledgeDensity, Map<String, Integer> scores) {
        if ("low".equals(knowledgeDensity) && ("unknown".equals(docType) || "narrative".equals(structureType))) {
            return "evidence_only";
        }
        if ("speech".equals(docType)) {
            return "weak";
        }
        if (List.of("project_plan", "regulation", "paper", "technical_spec").contains(docType)
                && !"low".equals(knowledgeDensity)) {
            return "strong";
        }
        if ("table".equals(docType) || "report".equals(docType)) {
            return "moderate";
        }
        return scores.values().stream().mapToInt(Integer::intValue).max().orElse(0) >= 4 ? "moderate" : "weak";
    }

    private String recommendedStrategy(String docType, String graphSuitability) {
        if ("evidence_only".equals(graphSuitability)) {
            return "evidence_only";
        }
        return switch (docType) {
            case "paper" -> "paper_extraction";
            case "regulation" -> "regulation_extraction";
            case "project_plan" -> "project_extraction";
            case "speech" -> "speech_summary";
            case "table" -> "table_attribute_first";
            case "technical_spec" -> "technical_spec_extraction";
            case "report" -> "report_extraction";
            default -> "default_extraction";
        };
    }

    private double confidence(Map<String, Integer> scores, double tableRatio, double headingRatio, int chunkCount, int unitCount) {
        List<Integer> ranked = scores.values().stream().sorted(Comparator.reverseOrder()).toList();
        int top = ranked.isEmpty() ? 0 : ranked.get(0);
        int second = ranked.size() < 2 ? 0 : ranked.get(1);
        double value = 0.42d
                + Math.min(0.28d, top / 24.0d)
                + Math.min(0.16d, Math.max(0, top - second) / 16.0d)
                + (chunkCount >= 8 ? 0.04d : 0.0d)
                + (unitCount >= 4 ? 0.04d : 0.0d)
                + (tableRatio >= 0.35d || headingRatio >= 0.18d ? 0.04d : 0.0d);
        return round(Math.max(0.30d, Math.min(0.94d, value)));
    }

    private List<String> routingNotes(String docType, String structureType, String graphSuitability, String recommendedStrategy) {
        List<String> notes = new ArrayList<>();
        notes.add("docType=" + docType);
        notes.add("structureType=" + structureType);
        notes.add("graphSuitability=" + graphSuitability);
        notes.add("recommendedStrategy=" + recommendedStrategy);
        if ("table_attribute_first".equals(recommendedStrategy)) {
            notes.add("表格/数值优先作为属性或指标，不直接扩成实体网络");
        }
        if ("speech_summary".equals(recommendedStrategy)) {
            notes.add("讲话稿优先抽主题、观点和任务部署，弱化实体间硬关系");
        }
        if ("evidence_only".equals(recommendedStrategy)) {
            notes.add("证据价值大于建图价值，默认仅抽高置信事实");
        }
        return notes;
    }

    private int termScore(String text, List<String> terms) {
        int score = 0;
        String value = text == null ? "" : text;
        for (String term : terms) {
            String item = normalizeForMatch(term);
            if (!item.isBlank() && value.contains(item)) {
                score++;
            }
        }
        return score;
    }

    private void appendText(StringBuilder builder, Object value) {
        String text = trimToNull(stringValue(value));
        if (text != null) {
            builder.append(' ').append(text);
        }
    }

    private int countDigits(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private String normalizeForMatch(String value) {
        return stringValue(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-·、，。,.()/（）【】\\[\\]《》:：;；]+", "");
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = trimToNull(value == null ? null : String.valueOf(value));
        if (text == null) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> parseMap(String json) {
        String text = trimToNull(json);
        if (text == null) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize document profile JSON", ex);
        }
    }
}
