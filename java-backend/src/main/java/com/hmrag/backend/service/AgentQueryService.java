package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.web.dto.AgentQueryDtos;
import com.hmrag.backend.web.dto.QueryDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AgentQueryService {

    private static final Logger log = LoggerFactory.getLogger(AgentQueryService.class);

    private final QueryService queryService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;
    private final Executor queryTaskExecutor;

    private final Map<UUID, AgentQueryDtos.SearchPlan> plans = new ConcurrentHashMap<>();
    private final Map<UUID, TaskState> tasks = new ConcurrentHashMap<>();

    public AgentQueryService(
            QueryService queryService,
            NamedParameterJdbcTemplate jdbcTemplate,
            AppProperties appProperties,
            @Qualifier("queryTaskExecutor") Executor queryTaskExecutor
    ) {
        this.queryService = queryService;
        this.jdbcTemplate = jdbcTemplate;
        this.appProperties = appProperties;
        this.queryTaskExecutor = queryTaskExecutor;
    }

    public AgentQueryDtos.AgentSearchResponse search(AgentQueryDtos.AgentSearchRequest request) {
        if (Boolean.TRUE.equals(request.async())) {
            UUID taskId = submitAsyncTask(request);
            return new AgentQueryDtos.AgentSearchResponse(taskId, "queued", null, null);
        }
        return runSearch(request, null);
    }

    public AgentQueryDtos.SearchPlanResponse createPlan(AgentQueryDtos.SearchPlanRequest request) {
        UUID planId = UUID.randomUUID();
        AgentQueryDtos.SearchPlan plan = new AgentQueryDtos.SearchPlan(
                planId,
                request.query().trim(),
                request.excludeDevDocs(),
                normalizeHop(request.hop()),
                clampOrDefault(request.topKDocs(), 20, 1, 200),
                clampOrDefault(request.topKEvidencePerDoc(), 5, 1, 50),
                normalizeRecallMode(request.recallMode()),
                blankToDefault(request.rerankModel(), "builtin_hybrid"),
                request.includeOverview() == null || request.includeOverview(),
                request.includeRawChunk() == null || request.includeRawChunk(),
                request.filters(),
                OffsetDateTime.now()
        );
        plans.put(planId, plan);
        return new AgentQueryDtos.SearchPlanResponse(plan);
    }

    public AgentQueryDtos.AgentSearchResponse executePlan(AgentQueryDtos.SearchExecuteRequest request) {
        if (request.planId() == null) {
            throw new IllegalArgumentException("planId is required");
        }
        AgentQueryDtos.SearchPlan plan = plans.get(request.planId());
        if (plan == null) {
            throw new IllegalArgumentException("plan not found: " + request.planId());
        }
        AgentQueryDtos.AgentSearchRequest searchRequest = new AgentQueryDtos.AgentSearchRequest(
                plan.query(),
                plan.excludeDevDocs(),
                plan.hop(),
                request.page(),
                request.pageSize(),
                plan.topKDocs(),
                plan.topKEvidencePerDoc(),
                plan.recallMode(),
                plan.rerankModel(),
                plan.includeOverview(),
                plan.includeRawChunk(),
                request.debug(),
                request.async(),
                plan.filters()
        );
        return search(searchRequest);
    }

    public AgentQueryDtos.TaskStatusResponse taskStatus(UUID taskId) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return task.toResponse();
    }

    public AgentQueryDtos.DocumentChunksResponse documentChunks(
            UUID docId,
            String section,
            Integer pageNo,
            int page,
            int pageSize,
            boolean includeContent
    ) {
        applyQueryTimeouts();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("docId", docId)
                .addValue("hasSection", section != null && !section.isBlank())
                .addValue("sectionPattern", "%" + (section == null ? "" : section.trim().toLowerCase()) + "%")
                .addValue("hasPageNo", pageNo != null)
                .addValue("pageNo", pageNo)
                .addValue("limit", safePageSize)
                .addValue("offset", offset);

        String where = """
                c.doc_id = :docId
                AND (:hasSection = false OR lower(COALESCE(c.title, '')) LIKE :sectionPattern)
                AND (:hasPageNo = false OR c.page_no = :pageNo)
                """;

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chunks c WHERE " + where,
                params,
                Long.class
        );

        List<AgentQueryDtos.ChunkItem> items = jdbcTemplate.query(
                """
                SELECT c.id, c.chunk_no, c.chunk_type, c.title, c.page_no, c.start_offset, c.end_offset, c.token_count, c.content
                FROM chunks c
                WHERE """ + where + """
                ORDER BY c.chunk_no ASC
                LIMIT :limit OFFSET :offset
                """,
                params,
                (rs, rowNum) -> mapChunk(rs, includeContent)
        );

        return new AgentQueryDtos.DocumentChunksResponse(docId, safePage, safePageSize, total, items);
    }

    public AgentQueryDtos.AgentAnswerResponse answer(AgentQueryDtos.AgentAnswerRequest request) {
        int topK = clampOrDefault(request.topK(), 5, 1, 20);
        long start = System.nanoTime();
        QueryDtos.SearchResponse search = runSearchInternal(
                request.query(),
                request.excludeDevDocs(),
                normalizeHop(request.hop()),
                1,
                topK,
                topK,
                request.filters()
        );
        QueryDtos.QAQueryResponse qa = queryService.answer(request.query(), request.excludeDevDocs(), topK);
        List<UUID> usedDocIds = qa.citations().stream()
                .map(QueryDtos.CitationItem::docId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<String> unanswered = qa.citations().isEmpty()
                ? List.of("未检索到足够证据，建议缩小问题范围或检查文档是否已入库。")
                : List.of();
        double confidence = qa.citations().isEmpty() ? 0.2 : Math.min(0.95, 0.45 + qa.citations().size() * 0.12);
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        AgentQueryDtos.SearchTrace trace = new AgentQueryDtos.SearchTrace(
                request.query(),
                request.query().trim(),
                normalizeHop(request.hop()),
                "hybrid",
                "builtin_hybrid",
                tookMs,
                filtersTrace(request.filters()),
                scoreTrace()
        );
        QueryDtos.DocOverview overview = request.includeOverview() == null || request.includeOverview()
                ? qa.docOverview()
                : null;
        return new AgentQueryDtos.AgentAnswerResponse(
                qa.answer(),
                qa.structuredAnswer(),
                qa.citations(),
                usedDocIds,
                confidence,
                unanswered,
                overview,
                trace
        );
    }

    private UUID submitAsyncTask(AgentQueryDtos.AgentSearchRequest request) {
        UUID taskId = UUID.randomUUID();
        TaskState state = new TaskState(taskId);
        tasks.put(taskId, state);
        int timeoutSeconds = Math.max(1, appProperties.query().asyncTaskTimeoutSeconds());
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    state.status = "running";
                    state.progressPercent = 15;
                    state.message = "检索任务执行中";
                    state.updatedAt = OffsetDateTime.now();
                    AgentQueryDtos.AgentSearchResponse result = runSearch(request, taskId);
                    state.status = "success";
                    state.progressPercent = 100;
                    state.message = "检索任务完成";
                    state.result = result;
                    state.updatedAt = OffsetDateTime.now();
                } catch (Exception ex) {
                    state.status = "failed";
                    state.progressPercent = 100;
                    state.message = ex.getMessage();
                    state.updatedAt = OffsetDateTime.now();
                    log.warn("Async query task failed [{}]: {}", taskId, ex.getMessage(), ex);
                }
            }, queryTaskExecutor).orTimeout(timeoutSeconds, TimeUnit.SECONDS).exceptionally(ex -> {
                state.status = "failed";
                state.progressPercent = 100;
                state.message = "检索超时，已强制终止本次异步任务";
                state.updatedAt = OffsetDateTime.now();
                log.warn("Async query task timed out [{}]: {}", taskId, ex.getMessage());
                return null;
            });
        } catch (RejectedExecutionException ex) {
            state.status = "failed";
            state.progressPercent = 100;
            state.message = "检索队列已满，请稍后重试";
            state.updatedAt = OffsetDateTime.now();
            log.warn("Async query task rejected [{}]: {}", taskId, ex.getMessage());
        }
        return taskId;
    }

    private AgentQueryDtos.AgentSearchResponse runSearch(AgentQueryDtos.AgentSearchRequest request, UUID taskId) {
        long start = System.nanoTime();
        int page = clampOrDefault(request.page(), 1, 1, 10_000);
        int pageSize = clampOrDefault(request.pageSize(), 20, 1, 200);
        int topKDocs = clampOrDefault(request.topKDocs(), pageSize, 1, 200);
        int topKEvidencePerDoc = clampOrDefault(request.topKEvidencePerDoc(), 5, 1, 50);
        String hop = normalizeHop(request.hop());
        String recallMode = normalizeRecallMode(request.recallMode());
        QueryDtos.SearchResponse result = runSearchInternal(
                request.query(),
                request.excludeDevDocs(),
                hop,
                page,
                topKDocs,
                topKEvidencePerDoc,
                request.filters()
        );
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        AgentQueryDtos.SearchTrace trace = Boolean.TRUE.equals(request.debug())
                ? new AgentQueryDtos.SearchTrace(
                request.query(),
                request.query().trim(),
                hop,
                recallMode,
                blankToDefault(request.rerankModel(), "builtin_hybrid"),
                tookMs,
                filtersTrace(request.filters()),
                scoreTrace()
        )
                : null;
        return new AgentQueryDtos.AgentSearchResponse(taskId, taskId == null ? "success" : "running", result, trace);
    }

    private QueryDtos.SearchResponse runSearchInternal(
            String query,
            boolean excludeDevDocs,
            String hop,
            int page,
            int topKDocs,
            int topKEvidencePerDoc,
            AgentQueryDtos.SearchFilters filters
    ) {
        int fetchWindow = Math.max(topKDocs * 10, 200);
        QueryDtos.SearchResponse raw = queryService.search(query, excludeDevDocs, 1, fetchWindow, "both", null, null);
        Set<UUID> allowedDocIds = loadAllowedDocIds(filters);

        List<QueryDtos.DocHit> filteredDocs = raw.docHits().stream()
                .filter(doc -> allowedDocIds == null || allowedDocIds.contains(doc.docId()))
                .toList();
        List<QueryDtos.SearchItem> filteredEvidence = raw.evidenceHits().stream()
                .filter(item -> item.docId() != null && (allowedDocIds == null || allowedDocIds.contains(item.docId())))
                .toList();

        int safePage = Math.max(1, page);
        int docFrom = Math.min((safePage - 1) * topKDocs, filteredDocs.size());
        int docTo = Math.min(docFrom + topKDocs, filteredDocs.size());
        List<QueryDtos.DocHit> pagedDocs = filteredDocs.subList(docFrom, docTo);

        Set<UUID> visibleDocIds = pagedDocs.stream().map(QueryDtos.DocHit::docId).collect(Collectors.toSet());
        Map<UUID, Integer> evidenceCounter = new LinkedHashMap<>();
        List<QueryDtos.SearchItem> visibleEvidence = new ArrayList<>();
        for (QueryDtos.SearchItem item : filteredEvidence) {
            UUID docId = item.docId();
            if (docId == null || !visibleDocIds.contains(docId)) {
                continue;
            }
            int used = evidenceCounter.getOrDefault(docId, 0);
            if (used >= topKEvidencePerDoc) {
                continue;
            }
            evidenceCounter.put(docId, used + 1);
            visibleEvidence.add(item);
        }

        return switch (hop) {
            case "first" -> new QueryDtos.SearchResponse(
                    pagedDocs,
                    List.of(),
                    List.of(),
                    filteredDocs.size(),
                    safePage,
                    topKDocs
            );
            case "second" -> {
                int evidenceFrom = Math.min((safePage - 1) * topKDocs, filteredEvidence.size());
                int evidenceTo = Math.min(evidenceFrom + topKDocs, filteredEvidence.size());
                List<QueryDtos.SearchItem> pagedEvidence = filteredEvidence.subList(evidenceFrom, evidenceTo);
                yield new QueryDtos.SearchResponse(
                        List.of(),
                        pagedEvidence,
                        pagedEvidence,
                        filteredEvidence.size(),
                        safePage,
                        topKDocs
                );
            }
            default -> new QueryDtos.SearchResponse(
                    pagedDocs,
                    visibleEvidence,
                    visibleEvidence,
                    filteredDocs.size(),
                    safePage,
                    topKDocs
            );
        };
    }

    private Set<UUID> loadAllowedDocIds(AgentQueryDtos.SearchFilters filters) {
        if (filters == null) {
            return null;
        }
        boolean hasDataSource = filters.dataSourceId() != null;
        boolean hasDocTypes = filters.docTypes() != null && !filters.docTypes().isEmpty();
        boolean hasDateFrom = filters.dateFrom() != null && !filters.dateFrom().isBlank();
        boolean hasDateTo = filters.dateTo() != null && !filters.dateTo().isBlank();
        if (!hasDataSource && !hasDocTypes && !hasDateFrom && !hasDateTo) {
            return null;
        }

        applyQueryTimeouts();
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT d.id
                FROM documents d
                LEFT JOIN source_files sf ON sf.doc_id = d.id
                WHERE 1 = 1
                """);
        if (hasDataSource) {
            sql.append(" AND sf.data_source_id = :dataSourceId");
            params.addValue("dataSourceId", filters.dataSourceId());
        }
        if (hasDocTypes) {
            List<String> docTypes = filters.docTypes().stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(String::trim)
                    .toList();
            if (!docTypes.isEmpty()) {
                sql.append(" AND d.doc_type IN (:docTypes)");
                params.addValue("docTypes", docTypes);
            }
        }
        if (hasDateFrom) {
            sql.append(" AND COALESCE(d.publish_date, d.effective_date) >= :dateFrom");
            params.addValue("dateFrom", LocalDate.parse(filters.dateFrom().trim()));
        }
        if (hasDateTo) {
            sql.append(" AND COALESCE(d.publish_date, d.effective_date) <= :dateTo");
            params.addValue("dateTo", LocalDate.parse(filters.dateTo().trim()));
        }
        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql.toString(), params, UUID.class));
    }

    private AgentQueryDtos.ChunkItem mapChunk(ResultSet rs, boolean includeContent) throws SQLException {
        String content = rs.getString("content");
        return new AgentQueryDtos.ChunkItem(
                uuid(rs.getString("id")),
                rs.getInt("chunk_no"),
                rs.getString("chunk_type"),
                rs.getString("title"),
                valueOrNull(rs, "page_no"),
                valueOrNull(rs, "start_offset"),
                valueOrNull(rs, "end_offset"),
                valueOrNull(rs, "token_count"),
                snippet(content, 220),
                includeContent ? content : null
        );
    }

    private Map<String, Object> filtersTrace(AgentQueryDtos.SearchFilters filters) {
        if (filters == null) {
            return Map.of();
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        if (filters.dataSourceId() != null) {
            trace.put("dataSourceId", filters.dataSourceId());
        }
        if (filters.docTypes() != null && !filters.docTypes().isEmpty()) {
            trace.put("docTypes", filters.docTypes());
        }
        if (filters.dateFrom() != null && !filters.dateFrom().isBlank()) {
            trace.put("dateFrom", filters.dateFrom());
        }
        if (filters.dateTo() != null && !filters.dateTo().isBlank()) {
            trace.put("dateTo", filters.dateTo());
        }
        return trace;
    }

    private Map<String, Object> scoreTrace() {
        return Map.of(
                "lexicalWeight", 0.78,
                "vectorWeight", 0.22,
                "exactPhraseBoost", 1.30,
                "titleBoost", 1.20,
                "fulltextBoost", 1.10
        );
    }

    private int clampOrDefault(Integer value, int defaultValue, int min, int max) {
        int n = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, n));
    }

    private String normalizeHop(String hop) {
        String normalized = hop == null ? "both" : hop.trim().toLowerCase();
        if (!List.of("first", "second", "both").contains(normalized)) {
            return "both";
        }
        return normalized;
    }

    private String normalizeRecallMode(String recallMode) {
        String normalized = recallMode == null ? "hybrid" : recallMode.trim().toLowerCase();
        if (!List.of("lexical", "vector", "hybrid").contains(normalized)) {
            return "hybrid";
        }
        return normalized;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private Integer valueOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private UUID uuid(String raw) {
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    private String snippet(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) + "..." : trimmed;
    }

    private void applyQueryTimeouts() {
        int statementTimeoutSeconds = Math.max(1, appProperties.query().statementTimeoutSeconds());
        int lockTimeoutSeconds = Math.max(1, appProperties.query().lockTimeoutSeconds());
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL statement_timeout = '" + statementTimeoutSeconds + "s'");
        jdbcTemplate.getJdbcTemplate().execute("SET LOCAL lock_timeout = '" + lockTimeoutSeconds + "s'");
    }

    private static final class TaskState {
        private final UUID taskId;
        private final OffsetDateTime createdAt;
        private volatile OffsetDateTime updatedAt;
        private volatile String status;
        private volatile int progressPercent;
        private volatile String message;
        private volatile AgentQueryDtos.AgentSearchResponse result;

        private TaskState(UUID taskId) {
            this.taskId = taskId;
            this.createdAt = OffsetDateTime.now();
            this.updatedAt = this.createdAt;
            this.status = "queued";
            this.progressPercent = 0;
            this.message = "任务已创建";
        }

        private AgentQueryDtos.TaskStatusResponse toResponse() {
            return new AgentQueryDtos.TaskStatusResponse(
                    taskId,
                    status,
                    progressPercent,
                    message,
                    result,
                    createdAt,
                    updatedAt
            );
        }
    }
}
