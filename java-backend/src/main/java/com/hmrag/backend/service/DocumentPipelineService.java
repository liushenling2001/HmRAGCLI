package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPipelineService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final RobustDocumentParser robustDocumentParser;
    private final AiExtractionService aiExtractionService;
    private final EmbeddingService embeddingService;
    private final DocumentProfileService documentProfileService;

    public DocumentPipelineService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            RobustDocumentParser robustDocumentParser,
            AiExtractionService aiExtractionService,
            EmbeddingService embeddingService,
            DocumentProfileService documentProfileService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.robustDocumentParser = robustDocumentParser;
        this.aiExtractionService = aiExtractionService;
        this.embeddingService = embeddingService;
        this.documentProfileService = documentProfileService;
    }

    public UUID materializeParseArtifacts(SourceFile file) {
        return materializeParseArtifacts(file, StageProgress.NOOP);
    }

    public UUID materializeParseArtifacts(SourceFile file, StageProgress stageProgress) {
        StageProgress progress = stageProgress == null ? StageProgress.NOOP : stageProgress;
        ParsedDocument parsed = parseSourceFile(file, progress);
        UUID docId = upsertDocument(file, parsed);
        List<UUID> chunkIds = recreateChunks(file, docId, parsed, progress);
        refreshDocumentProfile(docId, "parse");
        file.setDocId(docId);
        file.setParseStatus("success");
        file.setProcessingStage("parse");
        file.setErrorMessage(null);
        return chunkIds.isEmpty() ? null : chunkIds.get(0);
    }

    public void materializeKnowledgeUnit(SourceFile file) {
        materializeKnowledgeUnit(file, StageProgress.NOOP);
    }

    public void materializeKnowledgeUnit(SourceFile file, StageProgress stageProgress) {
        StageProgress progress = stageProgress == null ? StageProgress.NOOP : stageProgress;
        throwIfCancelled();
        UUID docId = file.getDocId();
        if (docId == null) {
            throw new IllegalStateException("Document is missing for source file " + file.getId());
        }

        List<Map<String, Object>> chunks = jdbcTemplate.query(
                """
                SELECT id, title, content, page_no, chunk_type
                FROM chunks
                WHERE doc_id = :docId
                ORDER BY chunk_no ASC
                """,
                new MapSqlParameterSource("docId", docId),
                (rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", UUID.fromString(rs.getString("id")));
                    row.put("title", rs.getString("title"));
                    row.put("content", rs.getString("content"));
                    int pageNo = rs.getInt("page_no");
                    row.put("pageNo", rs.wasNull() ? null : pageNo);
                    row.put("chunkType", rs.getString("chunk_type"));
                    return row;
                }
        );
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Chunk is missing for source file " + file.getId());
        }
        List<Map<String, Object>> targets = selectExtractionTargets(chunks, file);
        if (targets.isEmpty()) {
            throw new IllegalStateException("No extraction targets were selected for source file " + file.getId());
        }
        progress.report(0, targets.size());

        jdbcTemplate.update("DELETE FROM knowledge_units WHERE doc_id = :docId", new MapSqlParameterSource("docId", docId));

        int inserted = 0;
        int processed = 0;
        for (Map<String, Object> chunk : targets) {
            progress.report(processed, targets.size());
            throwIfCancelled();
            String content = (String) chunk.get("content");
            if (content == null || content.isBlank()) {
                processed++;
                progress.report(processed, targets.size());
                continue;
            }
            String title = (String) chunk.get("title");
            String chunkType = (String) chunk.get("chunkType");
            AiExtractionService.ExtractionResult extraction = aiExtractionService.extract(file, title, content, chunkType);
            Map<String, Object> fields = new HashMap<>();
            fields.put("searchTitle", extraction.title() == null || extraction.title().isBlank() ? title : extraction.title());
            fields.put("relativePath", file.getRelativePath());
            fields.put("fileExt", file.getFileExt());
            fields.put("chunkType", chunkType);
            fields.putAll(extraction.metadata());

            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_units (
                        id, doc_id, chunk_id, unit_type, title, content, normalized_text,
                        subject, indicator, status, priority, confidence, source_span, source_page, fields_json,
                        created_at, updated_at
                    ) VALUES (
                        :id, :docId, :chunkId, :unitType, :title, :content, :normalizedText,
                        :subject, :indicator, :status, :priority, :confidence, :sourceSpan, :sourcePage, CAST(:fieldsJson AS jsonb),
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("docId", docId)
                            .addValue("chunkId", chunk.get("id"))
                            .addValue("unitType", extraction.unitType())
                            .addValue("title", extraction.title() == null || extraction.title().isBlank() ? title : extraction.title())
                            .addValue("content", content)
                            .addValue("normalizedText", extraction.normalizedText() == null || extraction.normalizedText().isBlank() ? normalize(content) : extraction.normalizedText())
                            .addValue("subject", extraction.subject() == null || extraction.subject().isBlank() ? deriveSubject(file, title) : extraction.subject())
                            .addValue("indicator", extraction.indicator() == null || extraction.indicator().isBlank() ? deriveIndicator(content) : extraction.indicator())
                            .addValue("status", "reference")
                            .addValue("priority", 0)
                            .addValue("confidence", extraction.metadata().containsKey("aiEnabled") && Boolean.TRUE.equals(extraction.metadata().get("aiEnabled")) ? 0.72d : 0.45d)
                            .addValue("sourceSpan", "0-" + content.length())
                            .addValue("sourcePage", chunk.get("pageNo"))
                            .addValue("fieldsJson", json(fields))
            );
            inserted++;
            processed++;
            progress.report(processed, targets.size());
        }

        if (inserted == 0) {
            throw new IllegalStateException("No knowledge units were materialized for source file " + file.getId());
        }
        refreshDocumentProfile(docId, "extract");
        file.setExtractStatus("success");
        file.setProcessingStage("extract");
        file.setErrorMessage(null);
    }

    public EmbeddingService.EmbeddingStats materializeEmbeddings(SourceFile file) {
        return materializeEmbeddings(file, StageProgress.NOOP);
    }

    public EmbeddingService.EmbeddingStats materializeEmbeddings(SourceFile file, StageProgress stageProgress) {
        StageProgress progress = stageProgress == null ? StageProgress.NOOP : stageProgress;
        throwIfCancelled();
        EmbeddingService.EmbeddingStats stats = embeddingService.materializeEmbeddings(file, progress::report);
        file.setIndexStatus("success");
        file.setProcessingStage("vector");
        file.setErrorMessage(null);
        return stats;
    }

    private ParsedDocument parseSourceFile(SourceFile file, StageProgress stageProgress) {
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase();
        String title = baseName(file.getFileName());
        RobustDocumentParser.ParsedContent parsed = robustDocumentParser.parse(file, stageProgress::report);
        String docType = mapDocType(ext);
        boolean isDevDoc = isDevelopmentDoc(file);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("relativePath", file.getRelativePath() == null ? file.getFilePath() : file.getRelativePath());
        metadata.put("fileExt", ext);
        metadata.put("quickIngest", false);
        metadata.put("previewText", truncate(parsed.content(), 400));
        metadata.put("parserMode", parsed.parserMode());
        metadata.put("fallback", parsed.fallback());
        if (parsed.fallbackReason() != null) {
            metadata.put("fallbackReason", parsed.fallbackReason());
        }
        metadata.putAll(parsed.metadata());
        metadata.put("docOverview", buildDocOverview(file, parsed.chunks(), parsed.content()));
        return new ParsedDocument(title, parsed.content(), parsed.chunks(), docType, isDevDoc, isDevDoc ? "development" : "business", metadata);
    }

    private UUID upsertDocument(SourceFile file, ParsedDocument parsed) {
        UUID existingId = jdbcTemplate.query(
                """
                SELECT id
                FROM documents
                WHERE source_file = :sourceFile
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("sourceFile", file.getFilePath()),
                rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null
        );

        if (existingId != null) {
            jdbcTemplate.update(
                    """
                    UPDATE documents
                    SET title = :title,
                        doc_type = :docType,
                        source_filename = :sourceFilename,
                        file_hash = :fileHash,
                        is_dev_doc = :isDevDoc,
                        doc_domain = :docDomain,
                        parse_status = 'success',
                        metadata_json = CAST(:metadataJson AS jsonb),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", existingId)
                            .addValue("title", parsed.title())
                            .addValue("docType", parsed.docType())
                            .addValue("sourceFilename", file.getFileName())
                            .addValue("fileHash", file.getFileHash())
                            .addValue("isDevDoc", parsed.isDevDoc())
                            .addValue("docDomain", parsed.docDomain())
                            .addValue("metadataJson", json(parsed.metadata()))
            );
            return existingId;
        }

        UUID docId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO documents (
                    id, title, doc_type, source_file, source_filename, status, language,
                    file_hash, is_canonical, duplicate_count, is_dev_doc, doc_domain,
                    parse_status, metadata_json, created_at, updated_at
                ) VALUES (
                    :id, :title, :docType, :sourceFile, :sourceFilename, 'reference', 'zh',
                    :fileHash, true, 0, :isDevDoc, :docDomain,
                    'success', CAST(:metadataJson AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", docId)
                        .addValue("title", parsed.title())
                        .addValue("docType", parsed.docType())
                        .addValue("sourceFile", file.getFilePath())
                        .addValue("sourceFilename", file.getFileName())
                        .addValue("fileHash", file.getFileHash())
                        .addValue("isDevDoc", parsed.isDevDoc())
                        .addValue("docDomain", parsed.docDomain())
                        .addValue("metadataJson", json(parsed.metadata()))
        );
        return docId;
    }

    private List<UUID> recreateChunks(SourceFile file, UUID docId, ParsedDocument parsed, StageProgress stageProgress) {
        jdbcTemplate.update("DELETE FROM chunks WHERE doc_id = :docId", new MapSqlParameterSource("docId", docId));

        List<RobustDocumentParser.ParsedChunk> allSourceChunks = parsed.chunks().isEmpty()
                ? List.of(new RobustDocumentParser.ParsedChunk(parsed.title(), parsed.content(), "paragraph", null, null))
                : parsed.chunks();
        int chunkLimit = maxChunkCount(file);
        List<RobustDocumentParser.ParsedChunk> sourceChunks = selectChunksForIndexing(allSourceChunks, chunkLimit);
        int total = Math.max(1, sourceChunks.size());
        stageProgress.report(0, total);

        List<UUID> ids = new java.util.ArrayList<>();
        int chunkNo = 0;
        int processed = 0;
        for (RobustDocumentParser.ParsedChunk sourceChunk : sourceChunks) {
            String content = sourceChunk.content();
            if (content == null || content.isBlank()) {
                processed++;
                stageProgress.report(processed, total);
                continue;
            }
            chunkNo++;
            UUID chunkId = UUID.randomUUID();
            ids.add(chunkId);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("searchTitle", sourceChunk.title());
            metadata.put("quickIngest", false);
            metadata.put("chunkType", sourceChunk.chunkType());
            jdbcTemplate.update(
                    """
                    INSERT INTO chunks (
                        id, doc_id, chunk_no, chunk_type, title, content, page_no, token_count, metadata_json, created_at
                    ) VALUES (
                        :id, :docId, :chunkNo, :chunkType, :title, :content, :pageNo, :tokenCount, CAST(:metadataJson AS jsonb), CURRENT_TIMESTAMP
                    )
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", chunkId)
                            .addValue("docId", docId)
                            .addValue("chunkNo", chunkNo)
                            .addValue("chunkType", sourceChunk.chunkType())
                            .addValue("title", sourceChunk.title())
                            .addValue("content", content)
                            .addValue("pageNo", sourceChunk.pageNo())
                            .addValue("tokenCount", estimateTokens(content))
                            .addValue("metadataJson", json(metadata))
            );
            processed++;
            stageProgress.report(processed, total);
        }
        return ids;
    }

    private int maxChunkCount(SourceFile file) {
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase();
        return switch (ext) {
            case ".doc", ".docx" -> positiveOrDefault(appProperties.ingest().parseChunkMaxDoc(), 120);
            case ".pdf" -> positiveOrDefault(appProperties.ingest().parseChunkMaxPdf(), 140);
            default -> positiveOrDefault(appProperties.ingest().parseChunkMaxDefault(), 100);
        };
    }

    private List<RobustDocumentParser.ParsedChunk> selectChunksForIndexing(List<RobustDocumentParser.ParsedChunk> allChunks, int limit) {
        if (allChunks == null || allChunks.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        if (allChunks.size() <= safeLimit) {
            return allChunks;
        }

        int size = allChunks.size();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();

        int headCount = Math.min(safeLimit, Math.max(1, (int) Math.ceil(safeLimit * 0.40d)));
        for (int i = 0; i < headCount; i++) {
            selected.add(i);
        }

        int tailQuota = Math.max(1, (int) Math.ceil(safeLimit * 0.20d));
        int tailCount = Math.min(safeLimit - selected.size(), tailQuota);
        for (int i = 0; i < tailCount; i++) {
            selected.add(size - 1 - i);
        }

        int remaining = safeLimit - selected.size();
        for (int i = 0; i < remaining; i++) {
            int idx = (int) Math.round(((i + 1.0d) * (size + 1.0d) / (remaining + 1.0d)) - 1.0d);
            idx = Math.max(0, Math.min(size - 1, idx));
            selected.add(idx);
        }

        return selected.stream()
                .sorted()
                .limit(safeLimit)
                .map(allChunks::get)
                .toList();
    }

    private Map<String, Object> buildDocOverview(SourceFile file, List<RobustDocumentParser.ParsedChunk> chunks, String fullContent) {
        List<RobustDocumentParser.ParsedChunk> source = chunks == null ? List.of() : chunks;
        List<RobustDocumentParser.ParsedChunk> sampled = sampleOverviewChunks(file, source);
        String merged = sampled.isEmpty()
                ? (fullContent == null ? "" : fullContent)
                : sampled.stream()
                .map(RobustDocumentParser.ParsedChunk::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        List<String> sections = sampled.stream()
                .map(RobustDocumentParser.ParsedChunk::title)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(16)
                .toList();
        List<String> keywords = topKeywords(merged, 12);
        List<String> entities = detectEntities(merged, 10);
        List<String> conclusions = detectConclusions(sampled, 6);
        String summary = summarizeMapReduce(sampled, merged);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("summary", summary);
        overview.put("sections", sections);
        overview.put("keyTopics", keywords.stream().limit(8).toList());
        overview.put("keywords", keywords);
        overview.put("entities", entities);
        overview.put("timeRange", detectTimeRange(merged));
        overview.put("conclusions", conclusions);
        overview.put("metadata", Map.of(
                "strategy", "key_chunk_sampling_map_reduce",
                "sampledChunks", sampled.size(),
                "totalChunks", source.size(),
                "generatedAt", OffsetDateTime.now().toString()
        ));
        return overview;
    }

    private List<RobustDocumentParser.ParsedChunk> sampleOverviewChunks(SourceFile file, List<RobustDocumentParser.ParsedChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        int limit = overviewTargetLimit(file, chunks.size());
        int safeLimit = Math.max(1, Math.min(limit, chunks.size()));
        if (chunks.size() <= safeLimit) {
            return chunks;
        }

        int size = chunks.size();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();

        // 1) Guarantee front coverage (intro/requirements preface often appears here)
        int headCount = Math.min(safeLimit, Math.max(2, (int) Math.ceil(safeLimit * 0.25d)));
        for (int i = 0; i < headCount; i++) {
            selected.add(i);
        }

        // 2) Guarantee tail coverage (conclusion/appendix/score tables often appear here)
        int tailCount = Math.min(safeLimit - selected.size(), Math.max(1, (int) Math.ceil(safeLimit * 0.15d)));
        for (int i = 0; i < tailCount; i++) {
            selected.add(size - 1 - i);
        }

        // 3) Evenly sample middle positions to avoid concentrating in one section
        int remainingForUniform = Math.max(0, safeLimit - selected.size());
        for (int i = 0; i < remainingForUniform; i++) {
            int idx = (int) Math.round(((i + 1.0d) * (size + 1.0d) / (remainingForUniform + 1.0d)) - 1.0d);
            idx = Math.max(0, Math.min(size - 1, idx));
            selected.add(idx);
            if (selected.size() >= safeLimit) {
                break;
            }
        }

        // 4) Fill any remaining slots with high-information chunks
        if (selected.size() < safeLimit) {
            List<Integer> ranked = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                ranked.add(i);
            }
            ranked.sort((left, right) -> Double.compare(
                    chunkScore(toChunkMap(chunks.get(right))),
                    chunkScore(toChunkMap(chunks.get(left)))
            ));
            for (Integer idx : ranked) {
                if (selected.size() >= safeLimit) {
                    break;
                }
                selected.add(idx);
            }
        }

        // Keep document order for map-reduce summary coherence.
        return selected.stream()
                .sorted()
                .limit(safeLimit)
                .map(chunks::get)
                .toList();
    }

    private int overviewTargetLimit(SourceFile file, int totalChunks) {
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase();
        int cap = switch (ext) {
            case ".doc", ".docx" -> positiveOrDefault(appProperties.ingest().overviewTargetMaxDoc(), 50);
            case ".pdf" -> positiveOrDefault(appProperties.ingest().overviewTargetMaxPdf(), 70);
            default -> positiveOrDefault(appProperties.ingest().overviewTargetMaxDefault(), 40);
        };
        double ratio = positiveOrDefault(appProperties.ingest().overviewTargetRatio(), 0.10d);
        int proportional = (int) Math.ceil(Math.max(1, totalChunks) * ratio);
        int minFloor = Math.min(positiveOrDefault(appProperties.ingest().overviewTargetMin(), 8), Math.max(1, totalChunks));
        int limit = Math.max(proportional, minFloor);
        limit = Math.min(limit, cap);
        return Math.min(limit, Math.max(1, totalChunks));
    }

    private Map<String, Object> toChunkMap(RobustDocumentParser.ParsedChunk chunk) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", chunk.title());
        map.put("content", chunk.content());
        map.put("chunkType", chunk.chunkType());
        return map;
    }

    private String summarizeMapReduce(List<RobustDocumentParser.ParsedChunk> sampled, String merged) {
        if (sampled.isEmpty()) {
            return truncate(merged, 300);
        }
        List<String> mapSummaries = new ArrayList<>();
        int batchSize = 8;
        for (int i = 0; i < sampled.size(); i += batchSize) {
            int to = Math.min(sampled.size(), i + batchSize);
            String joined = sampled.subList(i, to).stream()
                    .map(chunk -> truncate(chunk.content(), 120))
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
            if (!joined.isBlank()) {
                mapSummaries.add(truncate(joined, 220));
            }
        }
        String reduced = mapSummaries.stream().limit(4).reduce((a, b) -> a + "；" + b).orElse("");
        return truncate(reduced.isBlank() ? merged : reduced, 360);
    }

    private List<String> topKeywords(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String token : text.replace('\n', ' ').split("[，。；：、,\\s]+")) {
            String clean = token.trim();
            if (clean.length() < 2 || clean.length() > 14) {
                continue;
            }
            freq.merge(clean, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    private List<String> detectEntities(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}(系统|平台|中心|委员会|学校|大学|学院|公司|集团))");
        Matcher matcher = pattern.matcher(text);
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        while (matcher.find() && entities.size() < limit) {
            entities.add(matcher.group(1));
        }
        return new ArrayList<>(entities);
    }

    private List<String> detectConclusions(List<RobustDocumentParser.ParsedChunk> chunks, int limit) {
        return chunks.stream()
                .map(RobustDocumentParser.ParsedChunk::content)
                .filter(content -> content != null && !content.isBlank())
                .filter(content -> content.contains("结论") || content.contains("建议") || content.contains("应") || content.contains("要求") || content.contains("目标") || content.contains("实现"))
                .map(content -> truncate(content, 120))
                .distinct()
                .limit(limit)
                .toList();
    }

    private String detectTimeRange(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Pattern yearPattern = Pattern.compile("(19|20)\\d{2}");
        Matcher matcher = yearPattern.matcher(text);
        List<Integer> years = new ArrayList<>();
        while (matcher.find()) {
            years.add(Integer.parseInt(matcher.group()));
        }
        if (years.isEmpty()) {
            return null;
        }
        years.sort(Comparator.naturalOrder());
        int start = years.get(0);
        int end = years.get(years.size() - 1);
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    private List<Map<String, Object>> selectExtractionTargets(List<Map<String, Object>> chunks, SourceFile file) {
        int limit = extractionTargetLimit(file, chunks.size());
        return chunks.stream()
                .sorted((left, right) -> Double.compare(chunkScore(right), chunkScore(left)))
                .limit(Math.max(1, limit))
                .toList();
    }

    private int extractionTargetLimit(SourceFile file, int totalChunks) {
        String ext = file.getFileExt() == null ? "" : file.getFileExt().toLowerCase();
        int maxCap = switch (ext) {
            case ".doc", ".docx" -> positiveOrDefault(appProperties.ingest().extractTargetMaxDoc(), 60);
            case ".pdf" -> positiveOrDefault(appProperties.ingest().extractTargetMaxPdf(), 80);
            default -> positiveOrDefault(appProperties.ingest().extractTargetMaxDefault(), 50);
        };
        double ratio = positiveOrDefault(appProperties.ingest().extractTargetRatio(), 0.10d);
        int proportional = (int) Math.ceil(Math.max(1, totalChunks) * ratio);
        int minFloor = Math.min(positiveOrDefault(appProperties.ingest().extractTargetMin(), 8), Math.max(1, totalChunks));
        int limit = Math.max(proportional, minFloor);
        limit = Math.min(limit, maxCap);
        return Math.min(limit, Math.max(1, totalChunks));
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private double positiveOrDefault(double value, double fallback) {
        return value > 0.0d ? value : fallback;
    }

    private double chunkScore(Map<String, Object> chunk) {
        String title = String.valueOf(chunk.getOrDefault("title", ""));
        String content = String.valueOf(chunk.getOrDefault("content", ""));
        String chunkType = String.valueOf(chunk.getOrDefault("chunkType", ""));
        int len = content.length();
        double score = 0.0;

        if (!title.isBlank()) {
            score += 1.2;
        }
        if ("table_row".equalsIgnoreCase(chunkType)) {
            score += 1.1;
        } else if ("list".equalsIgnoreCase(chunkType)) {
            score += 0.8;
        } else {
            score += 0.4;
        }
        if (len >= 60 && len <= 700) {
            score += 1.0;
        } else if (len > 700) {
            score += 0.3;
        }

        return score;
    }

    private String mapDocType(String ext) {
        return switch (ext) {
            case ".pdf", ".doc", ".docx" -> "rule";
            case ".xls", ".xlsx" -> "excel";
            case ".md" -> "report";
            case ".txt" -> "notice";
            default -> "unknown";
        };
    }

    private boolean isDevelopmentDoc(SourceFile file) {
        String path = ((file.getRelativePath() == null ? "" : file.getRelativePath()) + " " + file.getFileName()).toLowerCase();
        return path.contains("api") || path.contains("schema") || path.contains("design") || path.contains("dev");
    }

    private String inferUnitType(SourceFile file, String chunkType) {
        if ("table_row".equals(chunkType)) {
            return "table_record";
        }
        return switch (mapDocType(file.getFileExt() == null ? "" : file.getFileExt().toLowerCase())) {
            case "report" -> "summary";
            case "rule" -> "rule";
            default -> "statement";
        };
    }

    private String deriveSubject(SourceFile file, String title) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return baseName(file.getFileName());
    }

    private String deriveIndicator(String content) {
        if (content == null) {
            return null;
        }
        if (content.contains("标准")) {
            return "标准";
        }
        if (content.contains("要求")) {
            return "要求";
        }
        if (content.contains("数据")) {
            return "数据";
        }
        if (content.contains("|")) {
            return "表格记录";
        }
        return null;
    }

    private String normalize(String text) {
        return text == null ? null : text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON", ex);
        }
    }

    private void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
    }

    private void refreshDocumentProfile(UUID docId, String stage) {
        if (documentProfileService == null || docId == null) {
            return;
        }
        try {
            documentProfileService.refreshProfile(docId);
        } catch (Exception ex) {
            log.warn("Document profile refresh skipped: docId={}, stage={}, error={}", docId, stage, ex.getMessage());
        }
    }

    private record ParsedDocument(
            String title,
            String content,
            List<RobustDocumentParser.ParsedChunk> chunks,
            String docType,
            boolean isDevDoc,
            String docDomain,
            Map<String, Object> metadata
    ) {
    }

    @FunctionalInterface
    public interface StageProgress {
        StageProgress NOOP = (completed, total) -> {
        };

        void report(int completed, int total);
    }
}
