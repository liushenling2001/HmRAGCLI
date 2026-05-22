package com.hmrag.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.web.dto.QueryDtos;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final Pattern SEARCH_TOKEN_SPLITTER = Pattern.compile("[\\s,，。；;、！？!？/\\\\|()（）]+");
    private static final Pattern CJK_RUN = Pattern.compile("\\p{IsHan}{2,}");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final KnowledgeGraphStoreClient knowledgeGraphStoreClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final Executor queryTaskExecutor;
    private volatile Boolean documentsSearchTsvExists;
    private volatile Boolean chunksSearchTsvExists;
    private volatile Boolean knowledgeUnitsSearchTsvExists;

    public QueryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            EmbeddingService embeddingService,
            KnowledgeGraphStoreClient knowledgeGraphStoreClient,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            @Qualifier("queryTaskExecutor") Executor queryTaskExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.knowledgeGraphStoreClient = knowledgeGraphStoreClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.queryTaskExecutor = queryTaskExecutor;
    }

    @Transactional(readOnly = true, timeout = 15)
    public QueryDtos.SearchResponse search(String keyword, boolean excludeDevDocs, int page, int pageSize) {
        return search(keyword, excludeDevDocs, page, pageSize, "both", null, null);
    }

    @Transactional(readOnly = true, timeout = 15)
    public QueryDtos.SearchResponse search(
            String keyword,
            boolean excludeDevDocs,
            int page,
            int pageSize,
            String hop,
            Integer topKDocs,
            Integer topKEvidencePerDoc
    ) {
        applySearchTimeouts();
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(topKDocs != null ? topKDocs : pageSize, 1);
        int evidenceLimitPerDoc = Math.max(topKEvidencePerDoc != null ? topKEvidencePerDoc : safePageSize, 1);
        String normalizedHop = normalizeHop(hop);
        String compactKeyword = compact(normalizedKeyword);
        String tightKeyword = tight(normalizedKeyword);
        AppProperties.Query queryConfig = appProperties.query();

        List<String> keywordVariants = buildKeywordVariants(normalizedKeyword);
        int keywordLimit = Math.max(
                safePageSize * positive(queryConfig.keywordLimitMultiplier(), 6),
                positive(queryConfig.keywordLimitBase(), 80)
        );
        List<QueryDtos.SearchItem> keywordItems = new ArrayList<>();
        for (String variant : keywordVariants) {
            keywordItems.addAll(keywordSearch(variant, excludeDevDocs, keywordLimit));
        }
        List<QueryDtos.SearchItem> lexicalItems = keywordItems;
        long vectorCandidateLimit = Math.max(
                safePageSize * (long) positive(queryConfig.vectorCandidateMultiplier(), 3),
                positive(queryConfig.vectorCandidateBase(), 40)
        );
        List<UUID> vectorCandidateDocIds = lexicalItems.stream()
                .map(QueryDtos.SearchItem::docId)
                .filter(id -> id != null)
                .distinct()
                .limit(vectorCandidateLimit)
                .toList();
        int vectorLimit = Math.max(
                safePageSize * positive(queryConfig.vectorLimitMultiplier(), 5),
                positive(queryConfig.vectorLimitBase(), 60)
        );
        List<QueryDtos.SearchItem> vectorItems = normalizedKeyword.isBlank()
                ? List.of()
                : vectorSearchWithTimeout(normalizedKeyword, excludeDevDocs, vectorLimit, vectorCandidateDocIds);
        List<QueryDtos.SearchItem> semanticItems = new ArrayList<>(vectorItems);
        semanticItems.addAll(graphSearch(normalizedKeyword, excludeDevDocs, Math.max(20, safePageSize * 4)));
        List<QueryDtos.SearchItem> merged = merge(lexicalItems, semanticItems, normalizedKeyword, compactKeyword, tightKeyword);
        List<QueryDtos.DocHit> allDocHits = aggregateDocHits(merged);
        int from = Math.min((safePage - 1) * safePageSize, allDocHits.size());
        int to = Math.min(from + safePageSize, allDocHits.size());
        List<QueryDtos.DocHit> pagedDocHits = allDocHits.subList(from, to);

        Set<UUID> visibleDocIds = pagedDocHits.stream().map(QueryDtos.DocHit::docId).collect(Collectors.toSet());
        List<QueryDtos.SearchItem> evidenceHits = merged.stream()
                .filter(item -> item.docId() != null && visibleDocIds.contains(item.docId()))
                .limit(Math.max(12, (long) evidenceLimitPerDoc * Math.max(1, pagedDocHits.size())))
                .toList();
        if ("first".equals(normalizedHop)) {
            return new QueryDtos.SearchResponse(
                    pagedDocHits,
                    List.of(),
                    List.of(),
                    allDocHits.size(),
                    safePage,
                    safePageSize
            );
        }
        if ("second".equals(normalizedHop)) {
            int evidenceFrom = Math.min((safePage - 1) * safePageSize, merged.size());
            int evidenceTo = Math.min(evidenceFrom + safePageSize, merged.size());
            List<QueryDtos.SearchItem> pagedEvidence = merged.subList(evidenceFrom, evidenceTo);
            return new QueryDtos.SearchResponse(
                    List.of(),
                    pagedEvidence,
                    pagedEvidence,
                    merged.size(),
                    safePage,
                    safePageSize
            );
        }
        return new QueryDtos.SearchResponse(
                pagedDocHits,
                evidenceHits,
                evidenceHits,
                allDocHits.size(),
                safePage,
                safePageSize
        );
    }

    @Transactional(readOnly = true, timeout = 15)
    public QueryDtos.QAQueryResponse answer(String query, boolean excludeDevDocs, int topK) {
        applySearchTimeouts();
        QueryDtos.SearchResponse search = search(query, excludeDevDocs, 1, Math.max(topK, 1));
        List<QueryDtos.SearchItem> items = search.evidenceHits();
        String queryType = detectQueryType(query);
        if (items.isEmpty()) {
            return new QueryDtos.QAQueryResponse(
                    queryType,
                    "当前知识库中未检索到直接匹配结果。",
                    null,
                    List.of(),
                    null
            );
        }

        QueryDtos.SearchItem top = items.get(0);
        QueryDtos.DocOverview docOverview = search.docHits().isEmpty() ? null : search.docHits().get(0).overview();
        List<QueryDtos.CitationItem> citations = items.stream()
                .limit(3)
                .map(item -> new QueryDtos.CitationItem(
                        item.docId(),
                        item.sourceFile(),
                        item.sourceFilename(),
                        item.relativePath(),
                        item.unitId(),
                        item.chunkId(),
                        item.title(),
                        item.sourceSpan(),
                        item.pageNo()
                ))
                .toList();

        List<String> summaryPoints = items.stream()
                .limit(3)
                .map(item -> truncate(item.content(), 120))
                .toList();

        QueryDtos.StructuredAnswer structured = new QueryDtos.StructuredAnswer(
                top.subject(),
                null,
                truncate(docOverview != null && docOverview.summary() != null ? docOverview.summary() : top.content(), 180),
                null,
                top.indicator(),
                null,
                null,
                null,
                null,
                summaryPoints
        );

        return new QueryDtos.QAQueryResponse(
                queryType,
                docOverview != null && docOverview.summary() != null ? docOverview.summary() : top.content(),
                structured,
                citations,
                docOverview
        );
    }

    @Transactional(readOnly = true, timeout = 15)
    public QueryDtos.DocumentOverviewResponse documentOverview(UUID docId) {
        applySearchTimeouts();
        QueryDtos.DocHit hit = loadDocHit(docId, 0d, 0);
        return new QueryDtos.DocumentOverviewResponse(
                hit.docId(),
                hit.docTitle(),
                hit.sourceFile(),
                hit.sourceFilename(),
                hit.relativePath(),
                hit.overview()
        );
    }

    @Transactional(readOnly = true, timeout = 15)
    public DocumentDownload resolveDocumentDownload(UUID docId) {
        applySearchTimeouts();
        Map<String, Object> document = jdbcTemplate.query(
                """
                SELECT d.id, d.source_file, d.source_filename
                FROM documents d
                WHERE d.id = :docId
                LIMIT 1
                """,
                new MapSqlParameterSource("docId", docId),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sourceFile", rs.getString("source_file"));
                    item.put("sourceFilename", rs.getString("source_filename"));
                    return item;
                }
        );
        if (document == null) {
            throw new EntityNotFoundException("文档不存在: " + docId);
        }
        String sourceFile = String.valueOf(document.get("sourceFile") == null ? "" : document.get("sourceFile")).trim();
        if (sourceFile.isBlank()) {
            throw new EntityNotFoundException("文档未记录原始文件路径: " + docId);
        }

        Path filePath;
        try {
            filePath = Paths.get(sourceFile).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("原始文件路径非法: " + sourceFile);
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new EntityNotFoundException("原始文件不存在: " + filePath);
        }

        List<String> roots = jdbcTemplate.query(
                """
                SELECT DISTINCT ds.root_path
                FROM source_files sf
                JOIN data_sources ds ON ds.id = sf.data_source_id
                WHERE sf.doc_id = :docId
                  AND ds.root_path IS NOT NULL
                  AND ds.root_path <> ''
                """,
                new MapSqlParameterSource("docId", docId),
                (rs, rowNum) -> rs.getString("root_path")
        );
        if (!roots.isEmpty()) {
            boolean underKnownRoot = roots.stream().anyMatch(root -> isUnderRoot(filePath, root));
            if (!underKnownRoot) {
                throw new IllegalArgumentException("文件不在受控数据源目录内，拒绝下载");
            }
        }

        String preferredName = String.valueOf(document.get("sourceFilename") == null ? "" : document.get("sourceFilename")).trim();
        String fileName = preferredName.isBlank() ? filePath.getFileName().toString() : preferredName;
        return new DocumentDownload(filePath, fileName);
    }

    private List<QueryDtos.SearchItem> keywordSearch(String keyword, boolean excludeDevDocs, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        boolean chunkSearch = hasColumn("chunks", "search_tsv");
        boolean unitSearch = hasColumn("knowledge_units", "search_tsv");
        AppProperties.Query queryConfig = appProperties.query();
        int candidateDocLimit = Math.max(
                positive(queryConfig.candidateDocLimitBase(), 40),
                limit / positive(queryConfig.candidateDocLimitDivisor(), 2)
        );
        List<UUID> candidateDocIds = fetchFastCandidateDocIds(keyword, excludeDevDocs, candidateDocLimit, chunkSearch, unitSearch);
        if (candidateDocIds.isEmpty()) {
            return fallbackDocumentKeywordSearch(keyword, excludeDevDocs, Math.max(positive(queryConfig.fallbackLimitBase(), 20), limit));
        }

        int eachLimit = Math.max(positive(queryConfig.evidenceLimitBase(), 20), limit);
        List<QueryDtos.SearchItem> results = new ArrayList<>(eachLimit * 3);
        results.addAll(fetchKeywordDocumentEvidence(keyword, excludeDevDocs, eachLimit, candidateDocIds));
        results.addAll(fetchKeywordKnowledgeUnitEvidence(keyword, excludeDevDocs, eachLimit, candidateDocIds, unitSearch));
        results.addAll(fetchKeywordChunkEvidence(keyword, excludeDevDocs, eachLimit, candidateDocIds, chunkSearch));
        return results.stream()
                .sorted(Comparator.comparingDouble(QueryDtos.SearchItem::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<QueryDtos.SearchItem> phraseSearch(String keyword, boolean excludeDevDocs, int limit) {
        return List.of();
    }

    private List<UUID> fetchFastCandidateDocIds(String keyword, boolean excludeDevDocs, int limit, boolean chunkSearch, boolean unitSearch) {
        boolean documentSearch = hasColumn("documents", "search_tsv");
        QueryTsExpressions tsExpressions = buildTsExpressions(keyword);
        String lowered = keyword == null ? "" : keyword.toLowerCase();
        String compactKeyword = compact(lowered);
        String tightKeyword = tight(lowered);
        List<String> tokenPatterns = buildTokenPatterns(keyword);
        List<TextField> documentFields = documentTextFields();
        List<TextField> knowledgeUnitFields = knowledgeUnitTextFields();
        List<TextField> chunkFields = chunkTextFields();
        AppProperties.Query queryConfig = appProperties.query();
        int scanLimit = Math.max(
                positive(queryConfig.candidateScanBase(), 120),
                limit * positive(queryConfig.candidateScanMultiplier(), 8)
        );
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("excludeDevDocs", excludeDevDocs)
                .addValue("plainTsQuery", tsExpressions.plainQuery())
                .addValue("orTsQuery", tsExpressions.anyQuery())
                .addValue("scanLimit", scanLimit)
                .addValue("limit", Math.max(10, limit))
                .addValue("pattern", "%" + lowered + "%")
                .addValue("compactPattern", "%" + compactKeyword + "%")
                .addValue("tightPattern", "%" + tightKeyword + "%");
        for (int i = 0; i < tokenPatterns.size(); i++) {
            params.addValue("tokenPattern" + i, tokenPatterns.get(i));
        }
        List<String> branches = new ArrayList<>();
        branches.add("""
                (
                    SELECT d.id AS doc_id, (
                        """ + scoreSum(
                        searchTsvRank("d", documentSearch, 3.2, 1.6),
                        lexicalScore(documentFields, tokenPatterns.size())
                ) + """
                    ) AS score
                    FROM documents d
                    LEFT JOIN source_files sf ON sf.doc_id = d.id
                    WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                      AND (
                          """ + anyCondition(
                        searchTsvCondition("d", documentSearch),
                        lexicalCondition(documentFields, tokenPatterns.size())
                ) + """
                      )
                    LIMIT :scanLimit
                )
                """);
        branches.add("""
                (
                    SELECT ku.doc_id, (
                        """ + scoreSum(
                        searchTsvRank("ku", unitSearch, 2.0, 1.2),
                        lexicalScore(knowledgeUnitFields, tokenPatterns.size())
                ) + """
                    ) AS score
                    FROM knowledge_units ku
                    JOIN documents d ON d.id = ku.doc_id
                    WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                      AND (
                          """ + anyCondition(
                        searchTsvCondition("ku", unitSearch),
                        lexicalCondition(knowledgeUnitFields, tokenPatterns.size())
                ) + """
                      )
                    LIMIT :scanLimit
                )
                """);
        branches.add("""
                (
                    SELECT c.doc_id, (
                        """ + scoreSum(
                        searchTsvRank("c", chunkSearch, 1.8, 1.1),
                        lexicalScore(chunkFields, tokenPatterns.size())
                ) + """
                    ) AS score
                    FROM chunks c
                    JOIN documents d ON d.id = c.doc_id
                    WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                      AND (
                          """ + anyCondition(
                        searchTsvCondition("c", chunkSearch),
                        lexicalCondition(chunkFields, tokenPatterns.size())
                ) + """
                      )
                    LIMIT :scanLimit
                )
                """);
        String sql = """
                WITH doc_hits AS (
                """ + String.join("\nUNION ALL\n", branches) + """
                )
                SELECT doc_id
                FROM doc_hits
                GROUP BY doc_id
                ORDER BY MAX(score) DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> uuid(rs.getString("doc_id")));
    }

    private List<TextField> documentTextFields() {
        return List.of(
                new TextField("d.title", 8.0, 3.0, true),
                new TextField("d.source_filename", 8.0, 3.0, true),
                new TextField("d.source_file", 6.0, 2.0, true),
                new TextField("sf.relative_path", 8.0, 3.0, true),
                new TextField("sf.file_path", 6.0, 2.0, true)
        );
    }

    private List<TextField> knowledgeUnitTextFields() {
        return List.of(
                new TextField("ku.title", 3.0, 1.6, true),
                new TextField("ku.subject", 2.4, 1.3, true),
                new TextField("ku.indicator", 2.0, 1.1, true),
                new TextField("ku.normalized_text", 2.2, 1.2, false),
                new TextField("ku.content", 2.4, 1.4, false)
        );
    }

    private List<TextField> chunkTextFields() {
        return List.of(
                new TextField("c.title", 2.4, 1.4, true),
                new TextField("c.content", 2.0, 1.2, false)
        );
    }

    private String searchTsvCondition(String alias, boolean enabled) {
        if (!enabled) {
            return null;
        }
        return "COALESCE(" + alias + ".search_tsv, ''::tsvector) @@ to_tsquery('simple', :orTsQuery)";
    }

    private String searchTsvRank(String alias, boolean enabled, double plainWeight, double anyWeight) {
        if (!enabled) {
            return null;
        }
        return "ts_rank_cd(COALESCE(" + alias + ".search_tsv, ''::tsvector), plainto_tsquery('simple', :plainTsQuery)) * " + plainWeight
                + " + ts_rank_cd(COALESCE(" + alias + ".search_tsv, ''::tsvector), to_tsquery('simple', :orTsQuery)) * " + anyWeight;
    }

    private String lexicalCondition(List<TextField> fields, int tokenPatternCount) {
        List<String> conditions = new ArrayList<>();
        for (TextField field : fields) {
            conditions.add(likeCondition(field.expression(), ":pattern"));
            if (field.tightMatch()) {
                conditions.add(tightCondition(field.expression(), ":tightPattern"));
            }
        }
        for (int i = 0; i < tokenPatternCount; i++) {
            for (TextField field : fields) {
                conditions.add(likeCondition(field.expression(), ":tokenPattern" + i));
            }
        }
        return anyCondition(conditions.toArray(String[]::new));
    }

    private String lexicalScore(List<TextField> fields, int tokenPatternCount) {
        List<String> scores = new ArrayList<>();
        for (TextField field : fields) {
            scores.add(likeScore(field.expression(), ":pattern", field.phraseWeight()));
            if (field.tightMatch()) {
                scores.add(tightScore(field.expression(), ":tightPattern", field.phraseWeight() * 0.5));
            }
        }
        for (int i = 0; i < tokenPatternCount; i++) {
            for (TextField field : fields) {
                scores.add(likeScore(field.expression(), ":tokenPattern" + i, field.tokenWeight()));
            }
        }
        return scoreSum(scores.toArray(String[]::new));
    }

    private String likeCondition(String expression, String parameter) {
        return "lower(COALESCE(" + expression + ", '')) LIKE " + parameter;
    }

    private String tightCondition(String expression, String parameter) {
        return "regexp_replace(lower(COALESCE(" + expression + ", '')), '[[:space:][:punct:]]+', '', 'g') LIKE " + parameter;
    }

    private String likeScore(String expression, String parameter, double weight) {
        return "CASE WHEN " + likeCondition(expression, parameter) + " THEN " + weight + " ELSE 0.0 END";
    }

    private String tightScore(String expression, String parameter, double weight) {
        return "CASE WHEN " + tightCondition(expression, parameter) + " THEN " + weight + " ELSE 0.0 END";
    }

    private String anyCondition(String... expressions) {
        List<String> enabled = Arrays.stream(expressions)
                .filter(expr -> expr != null && !expr.isBlank() && !"false".equalsIgnoreCase(expr.trim()))
                .toList();
        return enabled.isEmpty() ? "false" : String.join("\nOR ", enabled);
    }

    private String scoreSum(String... expressions) {
        List<String> enabled = Arrays.stream(expressions)
                .filter(expr -> expr != null && !expr.isBlank() && !"0.0".equals(expr.trim()))
                .toList();
        return enabled.isEmpty() ? "0.0" : String.join("\n+ ", enabled);
    }

    private List<QueryDtos.SearchItem> fetchKeywordKnowledgeUnitEvidence(
            String keyword,
            boolean excludeDevDocs,
            int limit,
            List<UUID> candidateDocIds,
            boolean unitSearch
    ) {
        if (candidateDocIds == null || candidateDocIds.isEmpty()) {
            return List.of();
        }
        QueryTsExpressions tsExpressions = buildTsExpressions(keyword);
        List<String> tokenPatterns = buildTokenPatterns(keyword);
        List<TextField> fields = knowledgeUnitTextFields();
        String sql = """
                SELECT
                    'knowledge_unit' AS kind,
                    CASE
                        WHEN lower(COALESCE(ku.title, '')) LIKE :pattern OR lower(COALESCE(d.title, '')) LIKE :pattern THEN 'title'
                        WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern OR lower(COALESCE(sf.relative_path, '')) LIKE :pattern THEN 'filename'
                        WHEN lower(COALESCE(ku.content, '')) LIKE :pattern OR lower(COALESCE(ku.normalized_text, '')) LIKE :pattern THEN 'content'
                        ELSE 'fulltext'
                    END AS match_type,
                    (
                        """ + scoreSum(
                        searchTsvRank("ku", unitSearch, 2.6, 1.2),
                        lexicalScore(fields, tokenPatterns.size()),
                        "CASE WHEN lower(COALESCE(d.title, '')) LIKE :pattern THEN 0.7 ELSE 0.0 END",
                        "CASE WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern THEN 0.5 ELSE 0.0 END",
                        "CASE WHEN lower(COALESCE(sf.relative_path, '')) LIKE :pattern THEN 0.5 ELSE 0.0 END"
                ) + """
                    ) AS score,
                    d.id AS doc_id,
                    d.source_file,
                    d.source_filename,
                    sf.relative_path,
                    ku.chunk_id,
                    ku.id AS unit_id,
                    d.title AS doc_title,
                    d.doc_type,
                    COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                    COALESCE(d.doc_domain, 'business') AS doc_domain,
                    COALESCE(ku.title, d.title) AS title,
                    ku.content,
                    ku.source_page AS page_no,
                    ku.source_span,
                    ku.subject,
                    ku.indicator,
                    'keyword' AS tag
                FROM knowledge_units ku
                JOIN documents d ON d.id = ku.doc_id
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                  AND d.id IN (:candidateDocIds)
                  AND (
                      """ + anyCondition(
                        searchTsvCondition("ku", unitSearch),
                        lexicalCondition(fields, tokenPatterns.size())
                ) + """
                  )
                ORDER BY score DESC
                LIMIT :limit
                """;
        MapSqlParameterSource params = baseKeywordParams(keyword, excludeDevDocs, tsExpressions, tokenPatterns)
                .addValue("candidateDocIds", candidateDocIds)
                .addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> mapSearchItem(rs, keyword));
    }

    private List<QueryDtos.SearchItem> fetchKeywordChunkEvidence(
            String keyword,
            boolean excludeDevDocs,
            int limit,
            List<UUID> candidateDocIds,
            boolean chunkSearch
    ) {
        if (candidateDocIds == null || candidateDocIds.isEmpty()) {
            return List.of();
        }
        QueryTsExpressions tsExpressions = buildTsExpressions(keyword);
        List<String> tokenPatterns = buildTokenPatterns(keyword);
        List<TextField> fields = chunkTextFields();
        String sql = """
                SELECT
                    'chunk' AS kind,
                    CASE
                        WHEN lower(COALESCE(c.title, '')) LIKE :pattern OR lower(COALESCE(d.title, '')) LIKE :pattern THEN 'title'
                        WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern OR lower(COALESCE(sf.relative_path, '')) LIKE :pattern THEN 'filename'
                        WHEN lower(COALESCE(c.content, '')) LIKE :pattern THEN 'content'
                        ELSE 'fulltext'
                    END AS match_type,
                    (
                        """ + scoreSum(
                        searchTsvRank("c", chunkSearch, 2.2, 1.0),
                        lexicalScore(fields, tokenPatterns.size()),
                        "CASE WHEN lower(COALESCE(d.title, '')) LIKE :pattern THEN 0.6 ELSE 0.0 END",
                        "CASE WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern THEN 0.4 ELSE 0.0 END",
                        "CASE WHEN lower(COALESCE(sf.relative_path, '')) LIKE :pattern THEN 0.4 ELSE 0.0 END"
                ) + """
                    ) AS score,
                    d.id AS doc_id,
                    d.source_file,
                    d.source_filename,
                    sf.relative_path,
                    c.id AS chunk_id,
                    NULL::uuid AS unit_id,
                    d.title AS doc_title,
                    d.doc_type,
                    COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                    COALESCE(d.doc_domain, 'business') AS doc_domain,
                    COALESCE(c.title, d.title) AS title,
                    c.content,
                    c.page_no,
                    CASE
                        WHEN c.start_offset IS NOT NULL OR c.end_offset IS NOT NULL THEN concat_ws('-', c.start_offset, c.end_offset)
                        ELSE NULL
                    END AS source_span,
                    NULL::varchar AS subject,
                    NULL::varchar AS indicator,
                    'keyword' AS tag
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                  AND d.id IN (:candidateDocIds)
                  AND (
                      """ + anyCondition(
                        searchTsvCondition("c", chunkSearch),
                        lexicalCondition(fields, tokenPatterns.size())
                ) + """
                  )
                ORDER BY score DESC
                LIMIT :limit
                """;
        MapSqlParameterSource params = baseKeywordParams(keyword, excludeDevDocs, tsExpressions, tokenPatterns)
                .addValue("candidateDocIds", candidateDocIds)
                .addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> mapSearchItem(rs, keyword));
    }

    private List<QueryDtos.SearchItem> fetchKeywordDocumentEvidence(
            String keyword,
            boolean excludeDevDocs,
            int limit,
            List<UUID> candidateDocIds
    ) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        if (candidateDocIds != null && candidateDocIds.isEmpty()) {
            return List.of();
        }
        List<String> tokenPatterns = buildTokenPatterns(keyword);
        String lowered = keyword.toLowerCase();
        String candidateFilter = candidateDocIds == null ? "" : "  AND d.id IN (:candidateDocIds)\n";
        StringBuilder matchBuilder = new StringBuilder("""
                lower(COALESCE(d.title, '')) LIKE :pattern
                OR lower(COALESCE(d.source_filename, '')) LIKE :pattern
                OR lower(COALESCE(d.source_file, '')) LIKE :pattern
                OR lower(COALESCE(sf.relative_path, '')) LIKE :pattern
                OR lower(COALESCE(sf.file_path, '')) LIKE :pattern
                OR regexp_replace(lower(COALESCE(d.title, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern
                OR regexp_replace(lower(COALESCE(d.source_filename, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern
                OR regexp_replace(lower(COALESCE(d.source_file, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern
                OR regexp_replace(lower(COALESCE(sf.relative_path, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern
                OR regexp_replace(lower(COALESCE(sf.file_path, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern
                """);
        for (int i = 0; i < tokenPatterns.size(); i++) {
            matchBuilder.append("""
                    
                      OR lower(COALESCE(d.title, '')) LIKE :tokenPattern""").append(i).append("""
                    
                      OR lower(COALESCE(d.source_filename, '')) LIKE :tokenPattern""").append(i).append("""
                    
                      OR lower(COALESCE(d.source_file, '')) LIKE :tokenPattern""").append(i).append("""
                    
                      OR lower(COALESCE(sf.relative_path, '')) LIKE :tokenPattern""").append(i);
            matchBuilder.append("""
                    
                      OR lower(COALESCE(sf.file_path, '')) LIKE :tokenPattern""").append(i);
        }
        String sql = """
                WITH document_hits AS (
                    SELECT DISTINCT ON (d.id)
                        'document' AS kind,
                        'filename' AS match_type,
                        (
                            CASE WHEN lower(COALESCE(d.title, '')) LIKE :pattern THEN 8.0 ELSE 0.0 END +
                            CASE WHEN lower(COALESCE(d.source_filename, '')) LIKE :pattern THEN 8.0 ELSE 0.0 END +
                            CASE WHEN lower(COALESCE(d.source_file, '')) LIKE :pattern THEN 6.0 ELSE 0.0 END +
                            CASE WHEN lower(COALESCE(sf.relative_path, '')) LIKE :pattern THEN 8.0 ELSE 0.0 END +
                            CASE WHEN lower(COALESCE(sf.file_path, '')) LIKE :pattern THEN 6.0 ELSE 0.0 END +
                            CASE WHEN regexp_replace(lower(COALESCE(d.title, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern THEN 4.0 ELSE 0.0 END +
                            CASE WHEN regexp_replace(lower(COALESCE(d.source_filename, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern THEN 4.0 ELSE 0.0 END +
                            CASE WHEN regexp_replace(lower(COALESCE(d.source_file, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern THEN 3.0 ELSE 0.0 END +
                            CASE WHEN regexp_replace(lower(COALESCE(sf.relative_path, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern THEN 4.0 ELSE 0.0 END +
                            CASE WHEN regexp_replace(lower(COALESCE(sf.file_path, '')), '[[:space:][:punct:]]+', '', 'g') LIKE :tightPattern THEN 3.0 ELSE 0.0 END
                        )::double precision AS score,
                        d.id AS doc_id,
                        d.source_file,
                        d.source_filename,
                        sf.relative_path,
                        NULL::uuid AS chunk_id,
                        NULL::uuid AS unit_id,
                        d.title AS doc_title,
                        d.doc_type,
                        COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                        COALESCE(d.doc_domain, 'business') AS doc_domain,
                        d.title AS title,
                        COALESCE((d.metadata_json->>'previewText'), '') AS content,
                        NULL::int AS page_no,
                        NULL::varchar AS source_span,
                        NULL::varchar AS subject,
                        NULL::varchar AS indicator,
                        'keyword' AS tag,
                        d.updated_at
                    FROM documents d
                    LEFT JOIN source_files sf ON sf.doc_id = d.id
                    WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                """ + candidateFilter + """
                      AND (
                          """ + matchBuilder + """
                      )
                    ORDER BY d.id, score DESC, d.updated_at DESC NULLS LAST
                )
                SELECT kind, match_type, score, doc_id, source_file, source_filename, relative_path,
                       chunk_id, unit_id, doc_title, doc_type, is_dev_doc, doc_domain, title, content,
                       page_no, source_span, subject, indicator, tag
                FROM document_hits
                ORDER BY score DESC, updated_at DESC NULLS LAST
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("excludeDevDocs", excludeDevDocs)
                .addValue("pattern", "%" + lowered + "%")
                .addValue("tightPattern", "%" + tight(lowered) + "%")
                .addValue("limit", limit);
        if (candidateDocIds != null) {
            params.addValue("candidateDocIds", candidateDocIds);
        }
        for (int i = 0; i < tokenPatterns.size(); i++) {
            params.addValue("tokenPattern" + i, tokenPatterns.get(i));
        }
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> mapSearchItem(rs, keyword));
    }

    private List<QueryDtos.SearchItem> fallbackDocumentKeywordSearch(String keyword, boolean excludeDevDocs, int limit) {
        return fetchKeywordDocumentEvidence(keyword, excludeDevDocs, limit, null);
    }

    private List<QueryDtos.SearchItem> vectorSearch(String keyword, boolean excludeDevDocs, int limit, List<UUID> candidateDocIds) {
        List<Double> queryVector = embeddingService.embedQuery(keyword);
        if (queryVector.isEmpty()) {
            return List.of();
        }
        String queryVectorLiteral = vectorLiteral(queryVector);
        Map<String, VectorRow> merged = new LinkedHashMap<>();
        if (candidateDocIds != null && !candidateDocIds.isEmpty()) {
            addVectorRows(merged, fetchKnowledgeUnitVectorRows(keyword, excludeDevDocs, candidateDocIds, queryVectorLiteral, Math.max(limit * 2, limit)));
            addVectorRows(merged, fetchChunkVectorRows(keyword, excludeDevDocs, candidateDocIds, queryVectorLiteral, Math.max(limit * 2, limit)));
        }
        if (merged.size() < limit) {
            AppProperties.Query queryConfig = appProperties.query();
            addVectorRows(merged, fetchKnowledgeUnitVectorRows(
                    keyword,
                    excludeDevDocs,
                    null,
                    queryVectorLiteral,
                    positive(queryConfig.vectorIndependentKnowledgeUnitScanLimit(), 400)
            ));
            addVectorRows(merged, fetchChunkVectorRows(
                    keyword,
                    excludeDevDocs,
                    null,
                    queryVectorLiteral,
                    positive(queryConfig.vectorIndependentChunkScanLimit(), 600)
            ));
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(VectorRow::score).reversed())
                .limit(limit)
                .map(row -> row.item())
                .toList();
    }

    private List<QueryDtos.SearchItem> vectorSearchWithTimeout(
            String keyword,
            boolean excludeDevDocs,
            int limit,
            List<UUID> candidateDocIds
    ) {
        int timeoutSeconds = Math.max(1, appProperties.query().vectorTimeoutSeconds());
        try {
            return java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> vectorSearch(keyword, excludeDevDocs, limit, candidateDocIds), queryTaskExecutor)
                    .completeOnTimeout(List.of(), timeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                        log.warn("Vector search degraded to lexical-only: {}", cause.getMessage());
                        return List.of();
                    })
                    .join();
        } catch (Exception ex) {
            log.warn("Vector search degraded to lexical-only: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<QueryDtos.SearchItem> graphSearch(String keyword, boolean excludeDevDocs, int limit) {
        if (keyword == null || keyword.isBlank() || !knowledgeGraphStoreClient.isConfigured()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> facts = knowledgeGraphStoreClient.searchFacts(keyword, Math.max(limit * 2, limit));
            if (facts.isEmpty()) {
                return List.of();
            }
            return mapGraphFactsToSearchItems(keyword, facts, excludeDevDocs, limit);
        } catch (Exception ex) {
            log.warn("Knowledge graph search degraded: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<QueryDtos.SearchItem> mapGraphFactsToSearchItems(
            String keyword,
            List<Map<String, Object>> facts,
            boolean excludeDevDocs,
            int limit
    ) {
        List<UUID> docIds = facts.stream()
                .map(item -> tryUuid(stringValue(item.get("docId"))))
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<UUID> chunkIds = facts.stream()
                .map(item -> tryUuid(stringValue(item.get("chunkId"))))
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<String, Map<String, Object>> documents = loadGraphDocuments(docIds, excludeDevDocs);
        Map<String, Map<String, Object>> chunks = loadGraphChunks(chunkIds);
        List<QueryDtos.SearchItem> items = new ArrayList<>();
        for (Map<String, Object> fact : facts) {
            String docIdText = stringValue(fact.get("docId"));
            String chunkIdText = stringValue(fact.get("chunkId"));
            Map<String, Object> chunk = chunks.get(chunkIdText);
            if ((docIdText == null || docIdText.isBlank()) && chunk != null) {
                docIdText = stringValue(chunk.get("docId"));
            }
            Map<String, Object> document = documents.get(docIdText);
            UUID docId = tryUuid(docIdText);
            if (docId == null || document == null) {
                continue;
            }
            UUID chunkId = tryUuid(chunkIdText);
            String relationType = nullSafeString(fact.get("relationType"), "related_to");
            String title = "图谱事实: " + nullSafeString(fact.get("subject"), "未知实体")
                    + " - " + relationType + " - "
                    + nullSafeString(fact.get("object"), "未知实体");
            String statement = nullSafeString(fact.get("statement"), "");
            String graphText = title + (statement.isBlank() ? "" : "。" + statement);
            String chunkContent = chunk == null ? "" : nullSafeString(chunk.get("content"), "");
            String content = chunkContent.isBlank()
                    ? graphText
                    : graphText + "\n来源片段: " + truncate(chunkContent.replaceAll("\\s+", " ").trim(), 260);
            double confidence = numberValue(fact.get("confidence"));
            double tokenScore = numberValue(fact.get("tokenScore"));
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            tags.add("graph");
            tags.add(relationType);
            items.add(new QueryDtos.SearchItem(
                    "graph_fact",
                    "graph",
                    4.2d + Math.min(3.0d, tokenScore) * 0.35d + Math.max(0.0d, confidence),
                    docId,
                    stringValue(document.get("sourceFile")),
                    stringValue(document.get("sourceFilename")),
                    stringValue(document.get("relativePath")),
                    chunkId,
                    tryUuid(stringValue(fact.get("knowledgeUnitId"))),
                    stringValue(document.get("docTitle")),
                    stringValue(document.get("docType")),
                    Boolean.TRUE.equals(document.get("isDevDoc")),
                    stringValue(document.get("docDomain")),
                    title,
                    content,
                    snippet(keyword, content),
                    intValue(chunk == null ? null : chunk.get("pageNo")),
                    firstNonBlank(stringValue(fact.get("sourceSpan")), chunk == null ? null : stringValue(chunk.get("sourceSpan"))),
                    stringValue(fact.get("subject")),
                    relationType,
                    new ArrayList<>(tags)
            ));
        }
        return items.stream()
                .sorted(Comparator.comparingDouble(QueryDtos.SearchItem::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private Map<String, Map<String, Object>> loadGraphDocuments(List<UUID> docIds, boolean excludeDevDocs) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT d.id AS doc_id,
                       d.source_file,
                       d.source_filename,
                       sf.relative_path,
                       d.title AS doc_title,
                       d.doc_type,
                       COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                       COALESCE(d.doc_domain, 'business') AS doc_domain
                FROM documents d
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE d.id IN (:docIds)
                  AND (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                """,
                new MapSqlParameterSource()
                        .addValue("docIds", docIds)
                        .addValue("excludeDevDocs", excludeDevDocs),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", rs.getObject("doc_id", UUID.class).toString());
                    item.put("sourceFile", rs.getString("source_file"));
                    item.put("sourceFilename", rs.getString("source_filename"));
                    item.put("relativePath", rs.getString("relative_path"));
                    item.put("docTitle", rs.getString("doc_title"));
                    item.put("docType", rs.getString("doc_type"));
                    item.put("isDevDoc", rs.getBoolean("is_dev_doc"));
                    item.put("docDomain", rs.getString("doc_domain"));
                    return item;
                });
        return rows.stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("docId")),
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    private Map<String, Map<String, Object>> loadGraphChunks(List<UUID> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT c.id AS chunk_id,
                       c.doc_id,
                       c.title,
                       c.content,
                       c.page_no,
                       CASE
                           WHEN c.start_offset IS NOT NULL OR c.end_offset IS NOT NULL THEN concat_ws('-', c.start_offset, c.end_offset)
                           ELSE NULL
                       END AS source_span
                FROM chunks c
                WHERE c.id IN (:chunkIds)
                """,
                new MapSqlParameterSource().addValue("chunkIds", chunkIds),
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", rs.getObject("chunk_id", UUID.class).toString());
                    item.put("docId", rs.getObject("doc_id", UUID.class).toString());
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("pageNo", valueOrNull(rs, "page_no"));
                    item.put("sourceSpan", rs.getString("source_span"));
                    return item;
                });
        return rows.stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("chunkId")),
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    private List<VectorRow> fetchVectorRows(
            String sql,
            String keyword,
            boolean excludeDevDocs,
            List<UUID> candidateDocIds,
            Integer scanLimit,
            String queryVectorLiteral
    ) {
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("excludeDevDocs", excludeDevDocs)
                        .addValue("candidateDocIds", candidateDocIds)
                        .addValue("scanLimit", scanLimit)
                        .addValue("queryVector", queryVectorLiteral),
                (rs, rowNum) -> {
                    double similarity = rs.getDouble("similarity");
                    if (similarity <= 0.05d) {
                        return null;
                    }
                    QueryDtos.SearchItem item = new QueryDtos.SearchItem(
                            rs.getString("kind"),
                            rs.getString("match_type"),
                            similarity * 3.0d,
                            uuid(rs.getString("doc_id")),
                            rs.getString("source_file"),
                            rs.getString("source_filename"),
                            rs.getString("relative_path"),
                            uuid(rs.getString("chunk_id")),
                            uuid(rs.getString("unit_id")),
                            rs.getString("doc_title"),
                            rs.getString("doc_type"),
                            rs.getBoolean("is_dev_doc"),
                            rs.getString("doc_domain"),
                            rs.getString("title"),
                            rs.getString("content"),
                            snippet(keyword, rs.getString("content")),
                            valueOrNull(rs, "page_no"),
                            rs.getString("source_span"),
                            rs.getString("subject"),
                            rs.getString("indicator"),
                            List.of("vector")
                    );
                    return new VectorRow(item, similarity * 3.0d);
                }
        ).stream().filter(item -> item != null).toList();
    }

    private List<VectorRow> fetchKnowledgeUnitVectorRows(
            String keyword,
            boolean excludeDevDocs,
            List<UUID> candidateDocIds,
            String queryVectorLiteral,
            Integer scanLimit
    ) {
        boolean restrictToCandidates = candidateDocIds != null && !candidateDocIds.isEmpty();
        String sql = """
                SELECT
                    'knowledge_unit' AS kind,
                    'vector' AS match_type,
                    1 - (kue.embedding_vector <=> CAST(:queryVector AS vector)) AS similarity,
                    d.id AS doc_id,
                    d.source_file,
                    d.source_filename,
                    sf.relative_path,
                    ku.chunk_id,
                    ku.id AS unit_id,
                    d.title AS doc_title,
                    d.doc_type,
                    COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                    COALESCE(d.doc_domain, 'business') AS doc_domain,
                    COALESCE(ku.title, d.title) AS title,
                    ku.content,
                    ku.source_page AS page_no,
                    ku.source_span,
                    ku.subject,
                    ku.indicator
                FROM knowledge_unit_embeddings kue
                JOIN knowledge_units ku ON ku.id = kue.knowledge_unit_id
                JOIN documents d ON d.id = ku.doc_id
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                  AND kue.embedding_vector IS NOT NULL
                """ + (restrictToCandidates ? "\n  AND d.id IN (:candidateDocIds)" : "") + """
                ORDER BY kue.embedding_vector <=> CAST(:queryVector AS vector)
                LIMIT :scanLimit
                """;
        return fetchVectorRows(sql, keyword, excludeDevDocs, candidateDocIds, normalizeVectorScanLimit(scanLimit), queryVectorLiteral);
    }

    private List<VectorRow> fetchChunkVectorRows(
            String keyword,
            boolean excludeDevDocs,
            List<UUID> candidateDocIds,
            String queryVectorLiteral,
            Integer scanLimit
    ) {
        boolean restrictToCandidates = candidateDocIds != null && !candidateDocIds.isEmpty();
        String sql = """
                SELECT
                    'chunk' AS kind,
                    'vector' AS match_type,
                    1 - (ce.embedding_vector <=> CAST(:queryVector AS vector)) AS similarity,
                    d.id AS doc_id,
                    d.source_file,
                    d.source_filename,
                    sf.relative_path,
                    c.id AS chunk_id,
                    NULL::uuid AS unit_id,
                    d.title AS doc_title,
                    d.doc_type,
                    COALESCE(d.is_dev_doc, false) AS is_dev_doc,
                    COALESCE(d.doc_domain, 'business') AS doc_domain,
                    COALESCE(c.title, d.title) AS title,
                    c.content,
                    c.page_no,
                    CASE
                        WHEN c.start_offset IS NOT NULL OR c.end_offset IS NOT NULL THEN concat_ws('-', c.start_offset, c.end_offset)
                        ELSE NULL
                    END AS source_span,
                    NULL::varchar AS subject,
                    NULL::varchar AS indicator
                FROM chunk_embeddings ce
                JOIN chunks c ON c.id = ce.chunk_id
                JOIN documents d ON d.id = c.doc_id
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE (:excludeDevDocs = false OR COALESCE(d.is_dev_doc, false) = false)
                  AND ce.embedding_vector IS NOT NULL
                """ + (restrictToCandidates ? "\n  AND d.id IN (:candidateDocIds)" : "") + """
                ORDER BY ce.embedding_vector <=> CAST(:queryVector AS vector)
                LIMIT :scanLimit
                """;
        return fetchVectorRows(sql, keyword, excludeDevDocs, candidateDocIds, normalizeVectorScanLimit(scanLimit), queryVectorLiteral);
    }

    private void addVectorRows(Map<String, VectorRow> merged, List<VectorRow> rows) {
        for (VectorRow row : rows) {
            String key = key(row.item());
            VectorRow existing = merged.get(key);
            if (existing == null || row.score() > existing.score()) {
                merged.put(key, row);
            }
        }
    }

    private List<QueryDtos.SearchItem> merge(
            List<QueryDtos.SearchItem> keywordItems,
            List<QueryDtos.SearchItem> vectorItems,
            String keyword,
            String compactKeyword,
            String tightKeyword
    ) {
        Map<String, QueryDtos.SearchItem> merged = new LinkedHashMap<>();
        for (QueryDtos.SearchItem item : keywordItems) {
            String key = key(item);
            QueryDtos.SearchItem existing = merged.get(key);
            if (existing == null || item.score() > existing.score()) {
                merged.put(key, item);
            }
        }
        for (QueryDtos.SearchItem item : vectorItems) {
            String key = key(item);
            QueryDtos.SearchItem existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
                continue;
            }
            double score = existing.score() * 1.12d + item.score() * 0.22d;
            LinkedHashSet<String> tags = new LinkedHashSet<>(existing.tags());
            tags.addAll(item.tags());
            String matchType = tags.contains("keyword") && tags.contains("vector") ? "hybrid" : item.matchType();
            merged.put(key, new QueryDtos.SearchItem(
                    existing.kind(),
                    matchType,
                    score,
                    existing.docId(),
                    existing.sourceFile(),
                    existing.sourceFilename(),
                    existing.relativePath(),
                    existing.chunkId(),
                    existing.unitId(),
                    existing.docTitle(),
                    existing.docType(),
                    existing.isDevDoc(),
                    existing.docDomain(),
                    existing.title(),
                    existing.content(),
                    existing.snippet(),
                    existing.pageNo(),
                    existing.sourceSpan(),
                    existing.subject(),
                    existing.indicator(),
                    new ArrayList<>(tags)
            ));
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return merged.values().stream()
                .sorted(
                        Comparator.comparingInt((QueryDtos.SearchItem item) ->
                                        exactPhrasePriority(item, normalizedKeyword, compactKeyword, tightKeyword)
                                ).reversed()
                                .thenComparing(Comparator.comparingInt((QueryDtos.SearchItem item) -> lexicalPriority(item, normalizedKeyword)).reversed())
                                .thenComparing(Comparator.comparingDouble(QueryDtos.SearchItem::score).reversed())
                )
                .toList();
    }

    private int exactPhrasePriority(QueryDtos.SearchItem item, String rawKeyword, String compactKeyword, String tightKeyword) {
        if (rawKeyword == null || rawKeyword.isBlank()) {
            return 0;
        }
        int priority = 0;
        priority += exactPhraseHitScore(item.title(), rawKeyword, compactKeyword, tightKeyword) * 3;
        priority += exactPhraseHitScore(item.content(), rawKeyword, compactKeyword, tightKeyword) * 4;
        priority += exactPhraseHitScore(item.sourceFilename(), rawKeyword, compactKeyword, tightKeyword) * 2;
        priority += exactPhraseHitScore(item.relativePath(), rawKeyword, compactKeyword, tightKeyword);
        return priority;
    }

    private int exactPhraseHitScore(String value, String rawKeyword, String compactKeyword, String tightKeyword) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String lowered = value.toLowerCase();
        String loweredKeyword = rawKeyword.toLowerCase();
        if (!loweredKeyword.isBlank() && lowered.contains(loweredKeyword)) {
            return 3;
        }
        if (!compactKeyword.isBlank() && compact(lowered).contains(compactKeyword)) {
            return 2;
        }
        if (!tightKeyword.isBlank() && tight(lowered).contains(tightKeyword)) {
            return 1;
        }
        return 0;
    }

    private int lexicalPriority(QueryDtos.SearchItem item, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return 0;
        }
        String lowered = keyword.toLowerCase();
        String compact = lowered.replaceAll("\\s+", "");
        int priority = 0;
        if (containsRawOrCompact(item.title(), lowered, compact)) {
            priority += 4;
        }
        if (containsRawOrCompact(item.content(), lowered, compact)) {
            priority += 4;
        }
        if (containsRawOrCompact(item.sourceFilename(), lowered, compact)) {
            priority += 2;
        }
        if (containsRawOrCompact(item.relativePath(), lowered, compact)) {
            priority += 1;
        }
        if (item.tags() != null && item.tags().contains("keyword")) {
            priority += 3;
        }
        if ("fulltext".equals(item.matchType()) || "title".equals(item.matchType()) || "filename".equals(item.matchType())) {
            priority += 2;
        }
        return priority;
    }

    private boolean containsRawOrCompact(String value, String loweredKeyword, String compactKeyword) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lowered = value.toLowerCase();
        if (!loweredKeyword.isBlank() && lowered.contains(loweredKeyword)) {
            return true;
        }
        if (compactKeyword.isBlank()) {
            String tightKeywordOnly = tight(loweredKeyword);
            if (tightKeywordOnly.isBlank()) {
                return false;
            }
            String tightValueOnly = tight(lowered);
            return tightValueOnly.contains(tightKeywordOnly);
        }
        String compact = compact(lowered);
        if (compact.contains(compactKeyword)) {
            return true;
        }
        String tightKeyword = tight(loweredKeyword);
        if (tightKeyword.isBlank()) {
            return false;
        }
        String tightValue = tight(lowered);
        return tightValue.contains(tightKeyword);
    }

    private List<QueryDtos.DocHit> aggregateDocHits(List<QueryDtos.SearchItem> evidenceHits) {
        Map<UUID, List<QueryDtos.SearchItem>> grouped = evidenceHits.stream()
                .filter(item -> item.docId() != null)
                .collect(Collectors.groupingBy(QueryDtos.SearchItem::docId, LinkedHashMap::new, Collectors.toList()));
        List<QueryDtos.DocHit> hits = new ArrayList<>();
        for (Map.Entry<UUID, List<QueryDtos.SearchItem>> entry : grouped.entrySet()) {
            UUID docId = entry.getKey();
            List<QueryDtos.SearchItem> items = entry.getValue();
            double score = items.stream()
                    .sorted(Comparator.comparingDouble(QueryDtos.SearchItem::score).reversed())
                    .limit(3)
                    .mapToDouble(QueryDtos.SearchItem::score)
                    .sum();
            hits.add(loadDocHit(docId, score, items.size()));
        }
        return hits.stream().sorted(Comparator.comparingDouble(QueryDtos.DocHit::score).reversed()).toList();
    }

    private QueryDtos.DocHit loadDocHit(UUID docId, double score, int hitCount) {
        return jdbcTemplate.query(
                """
                SELECT d.id, d.title, d.source_file, d.source_filename, sf.relative_path, d.metadata_json::text AS metadata_json
                FROM documents d
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE d.id = :docId
                ORDER BY sf.created_at DESC NULLS LAST
                LIMIT 1
                """,
                new MapSqlParameterSource("docId", docId),
                rs -> rs.next()
                        ? new QueryDtos.DocHit(
                        uuid(rs.getString("id")),
                        rs.getString("title"),
                        rs.getString("source_file"),
                        rs.getString("source_filename"),
                        rs.getString("relative_path"),
                        score,
                        hitCount,
                        parseDocOverview(rs.getString("metadata_json"))
                )
                        : new QueryDtos.DocHit(docId, null, null, null, null, score, hitCount, null)
        );
    }

    @SuppressWarnings("unchecked")
    private QueryDtos.DocOverview parseDocOverview(String metadataJsonRaw) {
        if (metadataJsonRaw == null || metadataJsonRaw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJsonRaw, new TypeReference<>() {
            });
            Object raw = metadata.get("docOverview");
            if (!(raw instanceof Map<?, ?> overviewMapAny)) {
                return null;
            }
            Map<String, Object> overview = (Map<String, Object>) overviewMapAny;
            return new QueryDtos.DocOverview(
                    stringValue(overview.get("summary")),
                    stringList(overview.get("sections")),
                    stringList(overview.get("keyTopics")),
                    stringList(overview.get("keywords")),
                    stringList(overview.get("entities")),
                    stringValue(overview.get("timeRange")),
                    stringList(overview.get("conclusions")),
                    overview
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String nullSafeString(Object value, String fallback) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? fallback : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0.0d;
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String key(QueryDtos.SearchItem item) {
        if ("graph_fact".equals(item.kind())) {
            String text = nullSafeString(item.title(), "") + "|" + nullSafeString(item.content(), "");
            return item.kind() + ":" + Integer.toHexString(text.hashCode());
        }
        UUID id = item.unitId() != null ? item.unitId() : item.chunkId();
        return item.kind() + ":" + (id == null ? Integer.toHexString(nullSafeString(item.content(), "").hashCode()) : id);
    }

    private MapSqlParameterSource baseKeywordParams(
            String keyword,
            boolean excludeDevDocs,
            QueryTsExpressions tsExpressions,
            List<String> tokenPatterns
    ) {
        String lowered = keyword.toLowerCase();
        String compact = compact(lowered);
        String tight = tight(lowered);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pattern", "%" + lowered + "%")
                .addValue("compactPattern", "%" + compact + "%")
                .addValue("tightPattern", "%" + tight + "%")
                .addValue("plainTsQuery", tsExpressions.plainQuery())
                .addValue("orTsQuery", tsExpressions.anyQuery())
                .addValue("excludeDevDocs", excludeDevDocs);
        for (int i = 0; i < tokenPatterns.size(); i++) {
            params.addValue("tokenPattern" + i, tokenPatterns.get(i));
        }
        return params;
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String tight(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private List<String> buildKeywordVariants(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of("");
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String normalized = keyword.replace('“', ' ').replace('”', ' ').replace('"', ' ').trim();
        variants.add(normalized);
        Pattern splitter = Pattern.compile("[；;。！？!?,，、/\\\\|]+");
        for (String part : splitter.split(normalized)) {
            String clause = part == null ? "" : part.trim();
            if (clause.length() >= 4) {
                variants.add(clause);
            }
        }
        if (variants.size() > 6) {
            return variants.stream().limit(6).toList();
        }
        return new ArrayList<>(variants);
    }

    private QueryTsExpressions buildTsExpressions(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        List<String> tokens = splitSearchTokens(normalized);
        if (tokens.isEmpty()) {
            return new QueryTsExpressions(normalized, quoteTsTerm(normalized.isBlank() ? " " : normalized));
        }
        String anyQuery = tokens.stream()
                .map(this::quoteTsTerm)
                .collect(Collectors.joining(" | "));
        return new QueryTsExpressions(normalized, anyQuery);
    }

    private List<String> buildTokenPatterns(String keyword) {
        return splitSearchTokens(keyword).stream()
                .filter(token -> token.length() >= 2)
                .limit(6)
                .map(token -> "%" + token.toLowerCase() + "%")
                .toList();
    }

    private List<String> splitSearchTokens(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String raw : SEARCH_TOKEN_SPLITTER.split(keyword.trim())) {
            String token = raw == null ? "" : raw.trim();
            if (token.isBlank()) {
                continue;
            }
            tokens.add(token);
            addCjkSubTokens(tokens, token);
            if (tokens.size() >= 12) {
                break;
            }
        }
        return tokens.stream().limit(12).toList();
    }

    private void addCjkSubTokens(Set<String> tokens, String token) {
        var matcher = CJK_RUN.matcher(token);
        while (matcher.find() && tokens.size() < 12) {
            String run = matcher.group();
            if (run.length() <= 4) {
                addCjkWindows(tokens, run, 2);
                continue;
            }
            addCjkWindows(tokens, run, 4);
            addCjkWindows(tokens, run, 2);
        }
    }

    private void addCjkWindows(Set<String> tokens, String text, int width) {
        if (text.length() < width) {
            return;
        }
        for (int i = 0; i + width <= text.length() && tokens.size() < 12; i++) {
            tokens.add(text.substring(i, i + width));
        }
    }

    private String quoteTsTerm(String token) {
        String normalized = token == null ? "" : token.trim().replace("'", "''");
        return "'" + normalized + "'";
    }

    private QueryDtos.SearchItem mapSearchItem(ResultSet rs, String keyword) throws SQLException {
        String content = rs.getString("content");
        return new QueryDtos.SearchItem(
                rs.getString("kind"),
                rs.getString("match_type"),
                rs.getDouble("score"),
                uuid(rs.getString("doc_id")),
                rs.getString("source_file"),
                rs.getString("source_filename"),
                rs.getString("relative_path"),
                uuid(rs.getString("chunk_id")),
                uuid(rs.getString("unit_id")),
                rs.getString("doc_title"),
                rs.getString("doc_type"),
                rs.getBoolean("is_dev_doc"),
                rs.getString("doc_domain"),
                rs.getString("title"),
                content,
                snippet(keyword, content),
                valueOrNull(rs, "page_no"),
                rs.getString("source_span"),
                rs.getString("subject"),
                rs.getString("indicator"),
                List.of(rs.getString("tag"))
        );
    }

    private Integer valueOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private UUID uuid(String raw) {
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    private UUID tryUuid(String raw) {
        try {
            return raw == null || raw.isBlank() || "unknown".equalsIgnoreCase(raw) ? null : UUID.fromString(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private String snippet(String keyword, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return null;
        }
        String lowered = text.toLowerCase();
        String query = keyword == null ? "" : keyword.trim().toLowerCase();
        int radius = 90;
        if (!query.isBlank()) {
            int idx = lowered.indexOf(query);
            if (idx >= 0) {
                int start = Math.max(0, idx - radius);
                int end = Math.min(text.length(), idx + query.length() + radius);
                return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
            }
        }
        return truncate(text, radius * 2);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String detectQueryType(String query) {
        if (query == null) {
            return "document_lookup";
        }
        if (containsAny(query, "标准", "规定", "要求", "条件", "可以吗", "怎么办")) {
            return "rule_qa";
        }
        if (containsAny(query, "讲话", "强调", "重点", "任务")) {
            return "speech_summary";
        }
        if (containsAny(query, "同比", "环比", "多少", "增长", "数据")) {
            return "data_qa";
        }
        if (containsAny(query, "对比", "变化", "趋势", "关系", "近年")) {
            return "comparative_analysis";
        }
        return "document_lookup";
    }

    private String normalizeHop(String hop) {
        String normalized = hop == null ? "both" : hop.trim().toLowerCase();
        if (!List.of("first", "second", "both").contains(normalized)) {
            return "both";
        }
        return normalized;
    }

    private boolean hasColumn(String tableName, String columnName) {
        return switch (tableName) {
            case "documents" -> cachedColumnExists("documents", columnName, documentsSearchTsvExists, value -> documentsSearchTsvExists = value);
            case "chunks" -> cachedColumnExists("chunks", columnName, chunksSearchTsvExists, value -> chunksSearchTsvExists = value);
            case "knowledge_units" -> cachedColumnExists("knowledge_units", columnName, knowledgeUnitsSearchTsvExists, value -> knowledgeUnitsSearchTsvExists = value);
            default -> columnExists(tableName, columnName);
        };
    }

    private boolean cachedColumnExists(String tableName, String columnName, Boolean cached, java.util.function.Consumer<Boolean> setter) {
        if (cached != null) {
            return cached;
        }
        boolean exists = columnExists(tableName, columnName);
        setter.accept(exists);
        return exists;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = :tableName
                  AND column_name = :columnName
                """,
                new MapSqlParameterSource()
                        .addValue("tableName", tableName)
                        .addValue("columnName", columnName),
                Integer.class
        );
        return count != null && count > 0;
    }

    private String fulltextMatch(String alias, boolean enabled) {
        if (!enabled) {
            return null;
        }
        return "COALESCE(" + alias + ".search_tsv, ''::tsvector) @@ plainto_tsquery('simple', :tsQuery)";
    }

    private String fulltextRank(String alias, boolean enabled, double weight) {
        if (!enabled) {
            return null;
        }
        return "ts_rank_cd(COALESCE(" + alias + ".search_tsv, ''::tsvector), plainto_tsquery('simple', :tsQuery)) * " + weight;
    }

    private String fulltextCondition(List<String> expressions) {
        List<String> enabled = expressions.stream().filter(expr -> expr != null && !expr.isBlank()).toList();
        if (enabled.isEmpty()) {
            return "false";
        }
        return String.join(" OR ", enabled);
    }

    private String fulltextRankSum(List<String> expressions) {
        List<String> enabled = expressions.stream().filter(expr -> expr != null && !expr.isBlank()).toList();
        if (enabled.isEmpty()) {
            return "0.0";
        }
        return String.join(" + ", enabled);
    }

    private boolean containsAny(String query, String... terms) {
        for (String term : terms) {
            if (query.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private int normalizeVectorScanLimit(Integer scanLimit) {
        if (scanLimit == null) {
            return 100;
        }
        return Math.max(1, scanLimit);
    }

    private String vectorLiteral(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return "[]";
        }
        return "[" + vector.stream()
                .map(value -> Double.toString(value == null ? 0.0d : value))
                .collect(Collectors.joining(",")) + "]";
    }

    private void applySearchTimeouts() {
        int statementTimeoutSeconds = Math.max(1, appProperties.query().statementTimeoutSeconds());
        int lockTimeoutSeconds = Math.max(1, appProperties.query().lockTimeoutSeconds());
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL statement_timeout = '" + statementTimeoutSeconds + "s'");
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL lock_timeout = '" + lockTimeoutSeconds + "s'");
    }

    private record VectorRow(QueryDtos.SearchItem item, double score) {
    }

    private record QueryTsExpressions(String plainQuery, String anyQuery) {
    }

    private record TextField(String expression, double phraseWeight, double tokenWeight, boolean tightMatch) {
    }

    private boolean isUnderRoot(Path filePath, String rootPathRaw) {
        if (rootPathRaw == null || rootPathRaw.isBlank()) {
            return false;
        }
        try {
            Path root = Paths.get(rootPathRaw).toAbsolutePath().normalize();
            return filePath.startsWith(root);
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    public record DocumentDownload(
            Path path,
            String fileName
    ) {
    }
}
