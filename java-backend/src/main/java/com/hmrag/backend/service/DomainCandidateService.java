package com.hmrag.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.DomainCandidate;
import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.repository.DomainCandidateRepository;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DomainCandidateService {

    private static final List<String> ACTIVE_CANDIDATE_STATUSES = List.of("suggested", "reviewing");
    private static final List<String> INACTIVE_DOMAIN_STATUSES = List.of("archived", "deleted", "disabled");
    private static final List<String> BLOCKING_CANDIDATE_STATUSES = List.of("rejected", "accepted");

    private final DomainCandidateRepository domainCandidateRepository;
    private final DomainDefinitionRepository domainDefinitionRepository;
    private final DomainRefineJobService domainRefineJobService;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public DomainCandidateService(
            DomainCandidateRepository domainCandidateRepository,
            DomainDefinitionRepository domainDefinitionRepository,
            DomainRefineJobService domainRefineJobService,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            AppProperties appProperties,
            ObjectMapper objectMapper
    ) {
        this.domainCandidateRepository = domainCandidateRepository;
        this.domainDefinitionRepository = domainDefinitionRepository;
        this.domainRefineJobService = domainRefineJobService;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainCandidateItem> list(String status) {
        List<DomainCandidate> items = status == null || status.isBlank()
                ? domainCandidateRepository.findAllByOrderByCreatedAtDesc()
                : domainCandidateRepository.findByStatusInOrderByCreatedAtDesc(List.of(status.trim().toLowerCase(Locale.ROOT)));
        return items.stream().map(this::toItem).toList();
    }

    @Transactional
    public List<ApiDtos.DomainCandidateItem> discover(ApiDtos.DiscoverDomainCandidatesRequest request) {
        DiscoverySettings settings = resolveSettings(request);
        OffsetDateTime windowEnd = request != null && request.discoveryWindowEnd() != null
                ? request.discoveryWindowEnd()
                : OffsetDateTime.now();
        OffsetDateTime windowStart = request != null && request.discoveryWindowStart() != null
                ? request.discoveryWindowStart()
                : windowEnd.minusHours(settings.lookbackHours());
        List<String> existingDomainNames = loadExistingDomainNames();
        List<String> blockedCandidateNames = loadBlockedCandidateNames();
        List<Map<String, Object>> recentSignals = loadRecentIndexedSignals(windowStart, windowEnd, settings.maxDocuments());
        List<Map<String, Object>> clusterSeeds = buildClusterSeeds(windowStart, windowEnd, recentSignals, existingDomainNames, blockedCandidateNames, settings);
        if (recentSignals.size() < settings.minDocuments() && clusterSeeds.isEmpty()) {
            return List.of();
        }
        List<SuggestedCandidate> suggestions = suggestCandidates(recentSignals, clusterSeeds, settings.maxCandidates(), existingDomainNames, blockedCandidateNames);
        List<ApiDtos.DomainCandidateItem> created = new ArrayList<>();
        for (SuggestedCandidate suggestion : suggestions) {
            String normalizedName = trimToNull(suggestion.name());
            if (normalizedName == null) {
                continue;
            }
            if (matchesExistingDomain(normalizedName, existingDomainNames)) {
                continue;
            }
            if (matchesExistingDomain(normalizedName, blockedCandidateNames)) {
                continue;
            }
            if (domainCandidateRepository.existsByNameIgnoreCaseAndStatusIn(normalizedName, ACTIVE_CANDIDATE_STATUSES)) {
                continue;
            }
            DomainCandidate candidate = new DomainCandidate();
            candidate.setName(normalizedName);
            candidate.setDescription(trimToNull(suggestion.description()));
            candidate.setKeywordsJson(copyList(suggestion.keywords()));
            candidate.setEvidenceRefsJson(resolveEvidenceRefs(suggestion.supportingTitles(), recentSignals));
            candidate.setSourceSnapshotJson(buildSnapshot(windowStart, windowEnd, recentSignals, clusterSeeds, suggestion, settings.triggerSource()));
            candidate.setStatus("suggested");
            candidate.setTriggerSource(settings.triggerSource());
            candidate.setDiscoveryWindowStart(windowStart);
            candidate.setDiscoveryWindowEnd(windowEnd);
            created.add(toItem(domainCandidateRepository.save(candidate)));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public OffsetDateTime findEarliestIndexedAt() {
        String sql = """
                SELECT min(observed_at) AS earliest_observed_at
                FROM (
                    SELECT min(COALESCE(d.updated_at, d.created_at)) AS observed_at FROM documents d
                    UNION ALL
                    SELECT min(COALESCE(ku.updated_at, ku.created_at)) AS observed_at FROM knowledge_units ku
                    UNION ALL
                    SELECT min(COALESCE(c.created_at, d.updated_at, d.created_at)) AS observed_at
                    FROM chunks c
                    JOIN documents d ON d.id = c.doc_id
                ) earliest
                """;
        return namedParameterJdbcTemplate.getJdbcTemplate().query(sql, rs -> {
            if (!rs.next()) {
                return null;
            }
            return rs.getObject("earliest_observed_at", OffsetDateTime.class);
        });
    }

    @Transactional
    public ApiDtos.DomainCandidateItem reject(UUID id, ApiDtos.RejectDomainCandidateRequest request) {
        DomainCandidate candidate = require(id);
        candidate.setStatus("rejected");
        candidate.setReviewNote(trimToNull(request == null ? null : request.note()));
        return toItem(domainCandidateRepository.save(candidate));
    }

    @Transactional
    public ApiDtos.DomainDefinitionItem accept(UUID id, ApiDtos.AcceptDomainCandidateRequest request) {
        DomainCandidate candidate = require(id);
        if (!List.of("suggested", "reviewing").contains(normalize(candidate.getStatus()))) {
            throw new IllegalArgumentException("只有 suggested/reviewing 的候选领域可以确认: " + id);
        }
        DomainDefinition domain = new DomainDefinition();
        String name = trimToNull(request == null ? null : request.nameOverride());
        domain.setName(name == null ? candidate.getName() : name);
        domain.setDescription(trimToNull(candidate.getDescription()));
        domain.setGoal("围绕“" + domain.getName() + "”构建供智能体使用的领域知识库");
        domain.setScopeRulesJson(new HashMap<>());
        domain.setSeedQueriesJson(buildSeedQueries(candidate));
        domain.setIncludeDataSourcesJson(new ArrayList<>());
        domain.setExcludeDataSourcesJson(new ArrayList<>());
        domain.setPriority(0);
        domain.setAutoRefreshEnabled(false);
        domain.setAutoRefreshCron(null);
        domain.setActiveModelProfile(null);
        domain.setStatus("draft");
        domain.setCreatedBy("candidate-review");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("candidateId", candidate.getId());
        metadata.put("candidateTriggerSource", candidate.getTriggerSource());
        metadata.put("candidateWindowStart", candidate.getDiscoveryWindowStart() == null ? null : candidate.getDiscoveryWindowStart().toString());
        metadata.put("candidateWindowEnd", candidate.getDiscoveryWindowEnd() == null ? null : candidate.getDiscoveryWindowEnd().toString());
        metadata.put("candidateKeywords", copyList(candidate.getKeywordsJson()));
        domain.setMetadataJson(metadata);
        DomainDefinition saved = domainDefinitionRepository.save(domain);

        candidate.setStatus("accepted");
        candidate.setAcceptedDomainId(saved.getId());
        candidate.setReviewNote(trimToNull(request == null ? null : request.note()));
        domainCandidateRepository.save(candidate);

        if (Boolean.TRUE.equals(request == null ? null : request.startRefineAfterAccept())) {
            domainRefineJobService.startDomainRefine(saved.getId(), new ApiDtos.StartDomainRefineRequest(
                    "domain_refine",
                    "user",
                    null,
                    null,
                    Map.of("acceptedCandidateId", candidate.getId().toString())
            ));
        }

        return new ApiDtos.DomainDefinitionItem(
                saved.getId(),
                saved.getName(),
                saved.getDescription(),
                saved.getGoal(),
                copyMap(saved.getScopeRulesJson()),
                copyList(saved.getSeedQueriesJson()),
                copyList(saved.getIncludeDataSourcesJson()),
                copyList(saved.getExcludeDataSourcesJson()),
                saved.getPriority(),
                saved.isAutoRefreshEnabled(),
                saved.getAutoRefreshCron(),
                saved.getActiveModelProfile(),
                saved.getStatus(),
                saved.getCreatedBy(),
                copyMap(saved.getMetadataJson()),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    private DiscoverySettings resolveSettings(ApiDtos.DiscoverDomainCandidatesRequest request) {
        AppProperties.DomainKnowledge settings = appProperties.domainKnowledge();
        return new DiscoverySettings(
                Math.max(1, request != null && request.lookbackHours() != null ? request.lookbackHours() : settings.candidateDiscoveryLookbackHours()),
                Math.max(10, request != null && request.maxDocuments() != null ? request.maxDocuments() : settings.candidateDiscoveryMaxDocuments()),
                Math.max(1, request != null && request.maxCandidates() != null ? request.maxCandidates() : settings.candidateDiscoveryMaxCandidates()),
                Math.max(1, settings.candidateDiscoveryMinDocuments()),
                Math.max(4, settings.candidateDiscoveryKnowledgeUnitFacetLimit()),
                Math.max(4, settings.candidateDiscoveryChunkTopicLimit()),
                trimToNull(request == null ? null : request.triggerSource()) == null ? "auto" : request.triggerSource().trim().toLowerCase(Locale.ROOT)
        );
    }

    private List<Map<String, Object>> loadRecentIndexedSignals(OffsetDateTime windowStart, OffsetDateTime windowEnd, int maxDocuments) {
        int perSourceLimit = Math.max(10, maxDocuments);
        String sql = """
                WITH recent_documents AS (
                    SELECT
                        'document:' || d.id::text AS evidence_ref,
                        'document' AS signal_type,
                        COALESCE(NULLIF(btrim(d.title), ''), NULLIF(btrim(d.source_filename), ''), d.source_file) AS title,
                        left(
                            concat_ws(' ',
                                NULLIF(btrim(d.title), ''),
                                NULLIF(btrim(d.source_filename), ''),
                                NULLIF(btrim(d.source_org), ''),
                                NULLIF(btrim(d.author), '')
                            ),
                            800
                        ) AS text,
                        d.source_file AS source_path,
                        COALESCE(d.updated_at, d.created_at) AS observed_at
                    FROM documents d
                    WHERE COALESCE(d.updated_at, d.created_at) >= :windowStart
                      AND COALESCE(d.updated_at, d.created_at) < :windowEnd
                    ORDER BY COALESCE(d.updated_at, d.created_at) DESC
                    LIMIT :perSourceLimit
                ),
                recent_knowledge_units AS (
                    SELECT
                        'knowledge_unit:' || ku.id::text AS evidence_ref,
                        'knowledge_unit' AS signal_type,
                        COALESCE(NULLIF(btrim(ku.title), ''), NULLIF(btrim(ku.subject), ''), NULLIF(btrim(ku.indicator), ''), d.title) AS title,
                        left(
                            concat_ws(' ',
                                NULLIF(btrim(ku.title), ''),
                                NULLIF(btrim(ku.subject), ''),
                                NULLIF(btrim(ku.action), ''),
                                NULLIF(btrim(ku.organization), ''),
                                NULLIF(btrim(ku.region), ''),
                                NULLIF(btrim(ku.indicator), ''),
                                NULLIF(btrim(ku.value_text), ''),
                                NULLIF(btrim(ku.content), '')
                            ),
                            1200
                        ) AS text,
                        d.source_file AS source_path,
                        COALESCE(ku.updated_at, ku.created_at) AS observed_at
                    FROM knowledge_units ku
                    JOIN documents d ON d.id = ku.doc_id
                    WHERE COALESCE(ku.updated_at, ku.created_at) >= :windowStart
                      AND COALESCE(ku.updated_at, ku.created_at) < :windowEnd
                    ORDER BY COALESCE(ku.updated_at, ku.created_at) DESC
                    LIMIT :perSourceLimit
                ),
                recent_chunks AS (
                    SELECT
                        'chunk:' || c.id::text AS evidence_ref,
                        'chunk' AS signal_type,
                        COALESCE(NULLIF(btrim(c.title), ''), d.title) AS title,
                        left(
                            concat_ws(' ',
                                NULLIF(btrim(c.title), ''),
                                NULLIF(btrim(c.chunk_type), ''),
                                NULLIF(btrim(c.content), '')
                            ),
                            1200
                        ) AS text,
                        d.source_file AS source_path,
                        COALESCE(c.created_at, d.updated_at, d.created_at) AS observed_at
                    FROM chunks c
                    JOIN documents d ON d.id = c.doc_id
                    WHERE COALESCE(c.created_at, d.updated_at, d.created_at) >= :windowStart
                      AND COALESCE(c.created_at, d.updated_at, d.created_at) < :windowEnd
                    ORDER BY COALESCE(c.created_at, d.updated_at, d.created_at) DESC
                    LIMIT :perSourceLimit
                )
                SELECT *
                FROM (
                    SELECT * FROM recent_documents
                    UNION ALL
                    SELECT * FROM recent_knowledge_units
                    UNION ALL
                    SELECT * FROM recent_chunks
                ) signals
                ORDER BY observed_at DESC
                LIMIT :maxDocuments
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", windowStart)
                .addValue("windowEnd", windowEnd)
                .addValue("perSourceLimit", perSourceLimit)
                .addValue("maxDocuments", maxDocuments);
        return namedParameterJdbcTemplate.query(sql, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evidenceRef", rs.getString("evidence_ref"));
            item.put("signalType", rs.getString("signal_type"));
            item.put("title", rs.getString("title"));
            item.put("text", rs.getString("text"));
            item.put("sourcePath", rs.getString("source_path"));
            OffsetDateTime observedAt = rs.getObject("observed_at", OffsetDateTime.class);
            item.put("observedAt", observedAt == null ? null : observedAt.toString());
            return item;
        });
    }

    private List<Map<String, Object>> buildClusterSeeds(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            List<Map<String, Object>> recentSignals,
            List<String> existingDomainNames,
            List<String> blockedCandidateNames,
            DiscoverySettings settings
    ) {
        List<Map<String, Object>> seeds = new ArrayList<>();
        seeds.addAll(loadKnowledgeUnitFacetSeeds(windowStart, windowEnd, settings.maxDocuments(), settings.knowledgeUnitFacetLimit()));
        seeds.addAll(buildChunkTopicSeeds(recentSignals, settings.chunkTopicLimit()));
        List<String> blockedNames = new ArrayList<>(existingDomainNames);
        blockedNames.addAll(blockedCandidateNames);
        return deduplicateClusterSeeds(seeds, blockedNames);
    }

    private List<Map<String, Object>> loadKnowledgeUnitFacetSeeds(OffsetDateTime windowStart, OffsetDateTime windowEnd, int maxDocuments, int facetLimit) {
        int scanLimit = Math.max(maxDocuments * 4, facetLimit * 8);
        String sql = """
                WITH recent_knowledge_units AS (
                    SELECT
                        COALESCE(ku.updated_at, ku.created_at) AS observed_at,
                        NULLIF(btrim(ku.subject), '') AS subject,
                        NULLIF(btrim(ku.action), '') AS action,
                        NULLIF(btrim(ku.organization), '') AS organization,
                        NULLIF(btrim(ku.indicator), '') AS indicator
                    FROM knowledge_units ku
                    WHERE COALESCE(ku.updated_at, ku.created_at) >= :windowStart
                      AND COALESCE(ku.updated_at, ku.created_at) < :windowEnd
                    ORDER BY COALESCE(ku.updated_at, ku.created_at) DESC
                    LIMIT :scanLimit
                ),
                raw_terms AS (
                    SELECT 'subject' AS facet, lower(subject) AS term, observed_at FROM recent_knowledge_units WHERE subject IS NOT NULL
                    UNION ALL
                    SELECT 'action' AS facet, lower(action) AS term, observed_at FROM recent_knowledge_units WHERE action IS NOT NULL
                    UNION ALL
                    SELECT 'organization' AS facet, lower(organization) AS term, observed_at FROM recent_knowledge_units WHERE organization IS NOT NULL
                    UNION ALL
                    SELECT 'indicator' AS facet, lower(indicator) AS term, observed_at FROM recent_knowledge_units WHERE indicator IS NOT NULL
                ),
                grouped AS (
                    SELECT
                        facet,
                        term,
                        count(*) AS signal_count,
                        max(observed_at) AS last_observed
                    FROM raw_terms
                    WHERE length(term) >= 2
                    GROUP BY facet, term
                    HAVING count(*) >= 2
                ),
                ranked AS (
                    SELECT
                        facet,
                        term,
                        signal_count,
                        last_observed,
                        row_number() OVER (PARTITION BY facet ORDER BY signal_count DESC, last_observed DESC, term ASC) AS rn
                    FROM grouped
                )
                SELECT facet, term, signal_count, last_observed
                FROM ranked
                WHERE rn <= :facetLimit
                ORDER BY signal_count DESC, last_observed DESC, term ASC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", windowStart)
                .addValue("windowEnd", windowEnd)
                .addValue("scanLimit", scanLimit)
                .addValue("facetLimit", facetLimit);
        return namedParameterJdbcTemplate.query(sql, params, (rs, rowNum) -> {
            return toSeed(
                    "knowledge_unit_facet",
                    rs.getString("facet"),
                    rs.getString("term"),
                    rs.getInt("signal_count"),
                    rs.getObject("last_observed", OffsetDateTime.class)
            );
        });
    }

    private List<Map<String, Object>> buildChunkTopicSeeds(List<Map<String, Object>> recentSignals, int chunkTopicLimit) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> signal : recentSignals) {
            if (!"chunk".equals(stringValue(signal.get("signalType")))) {
                continue;
            }
            String topic = deriveChunkTopic(signal);
            if (topic == null) {
                continue;
            }
            groups.computeIfAbsent(topic, ignored -> new ArrayList<>()).add(signal);
        }
        List<Map<String, Object>> seeds = new ArrayList<>();
        groups.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue().size(), left.getValue().size()))
                .limit(chunkTopicLimit)
                .forEach(entry -> {
                    OffsetDateTime lastObserved = parseObservedAt(entry.getValue().get(0).get("observedAt"));
                    seeds.add(toSeed("chunk_topic", "chunk_topic", entry.getKey(), entry.getValue().size(), lastObserved));
                });
        return seeds;
    }

    private List<Map<String, Object>> deduplicateClusterSeeds(List<Map<String, Object>> seeds, List<String> existingDomainNames) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> seed : seeds) {
            String term = trimToNull(stringValue(seed.get("term")));
            if (term == null || !isCandidateDomainTerm(term) || matchesExistingDomain(term, existingDomainNames)) {
                continue;
            }
            String normalized = normalizeDomainName(term);
            if (normalized.isEmpty() || !seen.add(normalized)) {
                continue;
            }
            filtered.add(seed);
        }
        return filtered;
    }

    private Map<String, Object> toSeed(String clusterType, String facet, String term, int signalCount, OffsetDateTime lastObserved) {
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("clusterType", clusterType);
        seed.put("facet", facet);
        seed.put("term", trimToNull(term));
        seed.put("signalCount", signalCount);
        seed.put("lastObserved", lastObserved == null ? null : lastObserved.toString());
        return seed;
    }

    private List<SuggestedCandidate> suggestCandidates(
            List<Map<String, Object>> recentSignals,
            List<Map<String, Object>> clusterSeeds,
            int maxCandidates,
            List<String> existingDomainNames,
            List<String> blockedCandidateNames
    ) {
        List<String> blockedNames = new ArrayList<>(existingDomainNames);
        blockedNames.addAll(blockedCandidateNames);
        if (!isLlmEnabled()) {
            return fallbackCandidates(recentSignals, clusterSeeds, maxCandidates, blockedNames);
        }
        try {
            String raw = switch (provider()) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(buildDiscoveryPrompt(recentSignals, clusterSeeds, maxCandidates, blockedNames));
                case "ollama" -> callOllama(buildDiscoveryPrompt(recentSignals, clusterSeeds, maxCandidates, blockedNames));
                default -> throw new IllegalStateException("Unsupported llm provider: " + provider());
            };
            Map<String, Object> parsed = parseJsonObject(raw);
            Object value = parsed.get("candidates");
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                return fallbackCandidates(recentSignals, clusterSeeds, maxCandidates, blockedNames);
            }
            List<SuggestedCandidate> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                result.add(new SuggestedCandidate(
                        trimToNull(String.valueOf(map.get("name"))),
                        trimToNull(String.valueOf(map.get("description"))),
                        toStringList(map.get("keywords")),
                        toStringList(map.get("supportingTitles"))
                ));
                if (result.size() >= maxCandidates) {
                    break;
                }
            }
            List<SuggestedCandidate> filtered = result.stream()
                    .map(this::normalizeSuggestedCandidate)
                    .filter(item -> item.name() != null)
                    .filter(item -> isCandidateDomainTerm(item.name()))
                    .filter(item -> !matchesExistingDomain(item.name(), blockedNames))
                    .limit(maxCandidates)
                    .toList();
            return filtered.isEmpty() ? fallbackCandidates(recentSignals, clusterSeeds, maxCandidates, blockedNames) : filtered;
        } catch (Exception ex) {
            return fallbackCandidates(recentSignals, clusterSeeds, maxCandidates, blockedNames);
        }
    }

    private List<SuggestedCandidate> fallbackCandidates(
            List<Map<String, Object>> recentSignals,
            List<Map<String, Object>> clusterSeeds,
            int maxCandidates,
            List<String> existingDomainNames
    ) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> seed : clusterSeeds) {
            String term = normalizeCandidateName(trimToNull(stringValue(seed.get("term"))));
            if (term == null || !isCandidateDomainTerm(term) || matchesExistingDomain(term, existingDomainNames)) {
                continue;
            }
            groups.putIfAbsent(term, matchingSignals(term, recentSignals));
        }
        List<SuggestedCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> items = entry.getValue();
            result.add(new SuggestedCandidate(
                    entry.getKey(),
                    "基于 knowledge_units 结构化字段与 chunks 主题联合归纳的候选领域",
                    buildFallbackKeywords(items),
                    items.stream().map(this::signalLabel).distinct().limit(4).toList()
            ));
            if (result.size() >= maxCandidates) {
                break;
            }
        }
        return result;
    }

    private String deriveChunkTopic(Map<String, Object> signal) {
        String text = trimToNull(stringValue(signal.get("text")));
        String title = trimToNull(stringValue(signal.get("title")));
        if (text != null && title != null && text.startsWith(title)) {
            text = trimToNull(text.substring(title.length()));
        }
        String topic = firstMeaningfulSegment(text, 18);
        return isCandidateDomainTerm(topic) ? topic : null;
    }

    private List<Map<String, Object>> matchingSignals(String term, List<Map<String, Object>> recentSignals) {
        String normalizedTerm = normalizeDomainName(term);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> signal : recentSignals) {
            String title = normalizeDomainName(stringValue(signal.get("title")));
            String text = normalizeDomainName(shortenForPrompt(stringValue(signal.get("text")), 120));
            if ((!title.isEmpty() && (title.contains(normalizedTerm) || normalizedTerm.contains(title)))
                    || (!text.isEmpty() && text.contains(normalizedTerm))) {
                matches.add(signal);
            }
        }
        return matches.isEmpty() ? recentSignals.stream().limit(4).toList() : matches.stream().limit(6).toList();
    }

    private OffsetDateTime parseObservedAt(Object value) {
        String text = trimToNull(stringValue(value));
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildDiscoveryPrompt(
            List<Map<String, Object>> recentSignals,
            List<Map<String, Object>> clusterSeeds,
            int maxCandidates,
            List<String> existingDomainNames
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一个领域发现器。请根据最近一段时间内已完成索引的 knowledge_units 结构化字段聚类结果，结合 chunk 文本样本，归纳候选领域。\n");
        builder.append("这些候选领域只进入人工确认池，不直接精炼。\n");
        if (!existingDomainNames.isEmpty()) {
            builder.append("以下正式领域或已拒绝候选已经存在，绝对不要重复产出或仅做近义改写：\n");
            for (String existingDomainName : existingDomainNames) {
                builder.append("- ").append(existingDomainName).append("\n");
            }
        }
        builder.append("只返回一个 JSON 对象，字段只能是 candidates。\n");
        builder.append("candidates 是数组，每个元素字段只能是 name,description,keywords,supportingTitles。\n");
        builder.append("要求：\n");
        builder.append("1. 输出 1 到 ").append(maxCandidates).append(" 个候选领域。\n");
        builder.append("2. name 必须是可长期维护的领域名称，建议 4 到 18 个汉字，不要过长。\n");
        builder.append("3. 必须综合多条 subject/action/organization/indicator 聚类线索，不能只改写单个标题或单个片段。\n");
        builder.append("3.1 严禁把文章题目、章节题目、论文题名、报告题名、通知标题直接当作候选领域名称。\n");
        builder.append("4. 不要输出“全文检索”“知识库”这种系统性词语。\n");
        builder.append("5. 不要直接产出正式领域，只产出候选领域。\n");
        builder.append("6. 如果证据不足以形成稳定领域，返回 candidates: []，不要为了凑数输出垃圾候选。\n\n");
        builder.append("结构化聚类线索:\n");
        int clusterCount = 0;
        for (Map<String, Object> seed : clusterSeeds) {
            builder.append("- [")
                    .append(stringValue(seed.get("clusterType")))
                    .append("/")
                    .append(stringValue(seed.get("facet")))
                    .append("] ")
                    .append(stringValue(seed.get("term")))
                    .append(" (count=")
                    .append(stringValue(seed.get("signalCount")))
                    .append(")\n");
            clusterCount++;
            if (clusterCount >= 48) {
                break;
            }
        }
        builder.append("\n最近索引内容样本:\n");
        int count = 0;
        for (Map<String, Object> item : recentSignals) {
            if ("document".equals(stringValue(item.get("signalType")))) {
                continue;
            }
            builder.append("- [")
                    .append(stringValue(item.get("signalType")))
                    .append("] ")
                    .append(signalDiscoveryLabel(item));
            String text = trimToNull(stringValue(item.get("text")));
            if (text != null) {
                builder.append(" | ").append(shortenForPrompt(text, 120));
            }
            builder.append("\n");
            count++;
            if (count >= 80) {
                break;
            }
        }
        return builder.toString();
    }

    private String callOpenAiCompatible(String prompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appProperties.domainKnowledge().refinementLlm().model());
        body.put("temperature", 0.2);
        body.put("enable_thinking", false);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", 512);
        body.put("max_completion_tokens", 512);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是候选领域归纳器，只能返回 JSON 对象。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, appProperties.domainKnowledge().refinementLlm().apiKey());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String callOllama(String prompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appProperties.domainKnowledge().refinementLlm().model());
        body.put("stream", false);
        body.put("format", "json");
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是候选领域归纳器，只能返回 JSON 对象。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        return root.path("message").path("content").asText();
    }

    private JsonNode sendJson(String url, Map<String, Object> body, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, appProperties.domainKnowledge().refinementLlm().connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, appProperties.domainKnowledge().refinementLlm().requestTimeoutSeconds())).toMillis());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        if (apiKey != null && !apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        try (var output = connection.getOutputStream()) {
            output.write(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String responseBody;
        try (InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
            responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Domain candidate discovery failed: HTTP " + status + " " + responseBody);
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
        return objectMapper.readValue(trimmed, new TypeReference<>() {});
    }

    private DomainCandidate require(UUID id) {
        return domainCandidateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("候选领域不存在: " + id));
    }

    private List<String> resolveEvidenceRefs(List<String> supportingTitles, List<Map<String, Object>> recentSignals) {
        if (supportingTitles == null || supportingTitles.isEmpty()) {
            return recentSignals.stream().map(item -> String.valueOf(item.get("evidenceRef"))).distinct().limit(5).toList();
        }
        List<String> refs = new ArrayList<>();
        for (String title : supportingTitles) {
            String expected = trimToNull(title);
            if (expected == null) {
                continue;
            }
            for (Map<String, Object> item : recentSignals) {
                String label = signalLabel(item);
                if (expected.equalsIgnoreCase(label) || label.contains(expected) || expected.contains(label)) {
                    refs.add(String.valueOf(item.get("evidenceRef")));
                }
            }
        }
        return refs.stream().distinct().limit(5).toList();
    }

    private Map<String, Object> buildSnapshot(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            List<Map<String, Object>> recentSignals,
            List<Map<String, Object>> clusterSeeds,
            SuggestedCandidate suggestion,
            String triggerSource
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("windowStart", windowStart.toString());
        snapshot.put("windowEnd", windowEnd.toString());
        snapshot.put("signalCount", recentSignals.size());
        snapshot.put("clusterSeedCount", clusterSeeds.size());
        snapshot.put("triggerSource", triggerSource);
        snapshot.put("supportingTitles", copyList(suggestion.supportingTitles()));
        snapshot.put("sampleClusterSeeds", clusterSeeds.stream().limit(8).toList());
        snapshot.put("sampleSignals", recentSignals.stream().limit(8).toList());
        return snapshot;
    }

    private ApiDtos.DomainCandidateItem toItem(DomainCandidate candidate) {
        return new ApiDtos.DomainCandidateItem(
                candidate.getId(),
                candidate.getName(),
                candidate.getDescription(),
                copyList(candidate.getKeywordsJson()),
                copyList(candidate.getEvidenceRefsJson()),
                copyMap(candidate.getSourceSnapshotJson()),
                candidate.getStatus(),
                candidate.getTriggerSource(),
                candidate.getReviewNote(),
                candidate.getAcceptedDomainId(),
                candidate.getDiscoveryWindowStart(),
                candidate.getDiscoveryWindowEnd(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    private List<String> buildSeedQueries(DomainCandidate candidate) {
        if (candidate.getKeywordsJson() != null && !candidate.getKeywordsJson().isEmpty()) {
            return candidate.getKeywordsJson().stream()
                    .map(this::trimToNull)
                    .filter(item -> item != null)
                    .limit(5)
                    .toList();
        }
        return List.of(candidate.getName() + " 关键问题", candidate.getName() + " 核心规则", candidate.getName() + " 证据材料");
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = trimToNull(item == null ? null : String.valueOf(item));
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private boolean isLlmEnabled() {
        return !"disabled".equals(provider())
                && baseUrl() != null
                && !baseUrl().isBlank()
                && appProperties.domainKnowledge().refinementLlm().model() != null
                && !appProperties.domainKnowledge().refinementLlm().model().isBlank();
    }

    private String provider() {
        String provider = appProperties.domainKnowledge().refinementLlm().provider();
        return provider == null ? "disabled" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String baseUrl() {
        String raw = appProperties.domainKnowledge().refinementLlm().baseUrl();
        if (raw == null) {
            return "";
        }
        raw = raw.replaceAll("/+$", "");
        if ("ollama".equals(provider()) && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private List<String> loadExistingDomainNames() {
        return domainDefinitionRepository.findAllByOrderByPriorityDescCreatedAtAsc().stream()
                .filter(domain -> !INACTIVE_DOMAIN_STATUSES.contains(normalize(domain.getStatus())))
                .map(DomainDefinition::getName)
                .map(this::trimToNull)
                .filter(item -> item != null)
                .toList();
    }

    private List<String> loadBlockedCandidateNames() {
        return domainCandidateRepository.findByStatusInOrderByCreatedAtDesc(BLOCKING_CANDIDATE_STATUSES).stream()
                .map(DomainCandidate::getName)
                .map(this::trimToNull)
                .filter(item -> item != null)
                .toList();
    }

    private boolean matchesExistingDomain(String candidateName, List<String> existingDomainNames) {
        String normalizedCandidate = normalizeDomainName(candidateName);
        if (normalizedCandidate.isEmpty()) {
            return false;
        }
        for (String domainName : existingDomainNames) {
            String normalizedDomain = normalizeDomainName(domainName);
            if (normalizedDomain.isEmpty()) {
                continue;
            }
            if (normalizedCandidate.equals(normalizedDomain)
                    || normalizedCandidate.contains(normalizedDomain)
                    || normalizedDomain.contains(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeDomainName(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceAll("[\\s_\\-·、，。,.()/（）【】\\[\\]]+", "");
    }

    private SuggestedCandidate normalizeSuggestedCandidate(SuggestedCandidate candidate) {
        if (candidate == null) {
            return new SuggestedCandidate(null, null, List.of(), List.of());
        }
        return new SuggestedCandidate(
                normalizeCandidateName(candidate.name()),
                trimToNull(candidate.description()),
                copyList(candidate.keywords()).stream()
                        .map(this::normalizeCandidateKeyword)
                        .filter(item -> item != null)
                        .distinct()
                        .limit(5)
                        .toList(),
                copyList(candidate.supportingTitles()).stream()
                        .map(this::trimToNull)
                        .filter(item -> item != null)
                        .distinct()
                        .limit(6)
                        .toList()
        );
    }

    private String normalizeCandidateKeyword(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() < 2 || isGenericDomainTerm(normalized)) {
            return null;
        }
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24);
    }

    private String normalizeCandidateName(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        String normalized = text
                .replaceAll("[《》\"“”'‘’`]+", "")
                .replaceAll("^(关于|基于|面向|围绕|有关)", "")
                .replaceAll("(的研究|研究|分析|探析|综述|报告|通知|公告|方案|论文)$", "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", "")
                .trim();
        if (normalized.length() > 18) {
            normalized = normalized.substring(0, 18);
        }
        return trimToNull(normalized);
    }

    private boolean isCandidateDomainTerm(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return false;
        }
        if (isArticleLikeTitle(text)) {
            return false;
        }
        String normalized = normalizeCandidateName(text);
        if (normalized == null) {
            return false;
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 4 || length > 18) {
            return false;
        }
        if (isGenericDomainTerm(normalized)) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(\\.pdf|\\.docx?|\\.xlsx?|\\.pptx?|\\.md|\\.txt).*")) {
            return false;
        }
        if (lower.matches(".*(第[一二三四五六七八九十0-9]+[章节条]|[0-9]+[\\.、]).*")) {
            return false;
        }
        if (lower.contains("http") || lower.contains("www") || lower.contains("附件") || lower.contains("目录")) {
            return false;
        }
        return true;
    }

    private boolean isArticleLikeTitle(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() <= 8) {
            return false;
        }
        boolean hasTitlePrefix = normalized.startsWith("关于")
                || normalized.startsWith("基于")
                || normalized.startsWith("面向")
                || normalized.startsWith("围绕")
                || normalized.startsWith("有关");
        boolean hasArticleTail = normalized.endsWith("研究")
                || normalized.endsWith("分析")
                || normalized.endsWith("探析")
                || normalized.endsWith("综述")
                || normalized.endsWith("报告")
                || normalized.endsWith("方案")
                || normalized.endsWith("通知")
                || normalized.contains("建设方案")
                || normalized.contains("实践研究");
        return hasTitlePrefix && hasArticleTail;
    }

    private boolean isGenericDomainTerm(String value) {
        String normalized = normalizeDomainName(value);
        return List.of(
                "领域", "专题", "知识库", "全文检索", "自动归纳候选领域", "内容", "资料", "文件",
                "问题", "情况", "信息", "管理", "研究", "系统", "平台", "通知", "公告", "报告"
        ).contains(normalized);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String signalLabel(Map<String, Object> signal) {
        String title = trimToNull(stringValue(signal.get("title")));
        if (title != null) {
            return title;
        }
        String text = trimToNull(stringValue(signal.get("text")));
        return text == null ? "-" : shortenForPrompt(text, 80);
    }

    private String signalDiscoveryLabel(Map<String, Object> signal) {
        String signalType = trimToNull(stringValue(signal.get("signalType")));
        String text = trimToNull(stringValue(signal.get("text")));
        if ("chunk".equals(signalType)) {
            String title = trimToNull(stringValue(signal.get("title")));
            if (text != null && title != null && text.startsWith(title)) {
                text = trimToNull(text.substring(title.length()));
            }
            String segment = firstMeaningfulSegment(text, 80);
            return segment == null ? "-" : segment;
        }
        if ("knowledge_unit".equals(signalType)) {
            String segment = firstMeaningfulSegment(text, 80);
            if (segment != null) {
                return segment;
            }
        }
        return signalLabel(signal);
    }

    private String shortenForPrompt(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }

    private String firstMeaningfulSegment(String text, int maxLength) {
        String normalized = trimToNull(text);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replaceAll("\\s+", " ");
        String[] parts = normalized.split("[，。；：、,.!？?\\-_/|]");
        for (String part : parts) {
            String item = trimToNull(part);
            if (item != null && item.length() >= 4) {
                return item.length() <= maxLength ? item : item.substring(0, maxLength);
            }
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private List<String> buildFallbackKeywords(List<Map<String, Object>> items) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String text = firstMeaningfulSegment(trimToNull(stringValue(item.get("text"))), 18);
            if (text != null) {
                result.add(text);
            }
            if (result.size() >= 5) {
                break;
            }
        }
        return result.stream()
                .map(this::normalizeCandidateKeyword)
                .filter(item -> item != null)
                .distinct()
                .limit(5)
                .toList();
    }

    private record SuggestedCandidate(
            String name,
            String description,
            List<String> keywords,
            List<String> supportingTitles
    ) {
    }

    public record DiscoverySettings(
            int lookbackHours,
            int maxDocuments,
            int maxCandidates,
            int minDocuments,
            int knowledgeUnitFacetLimit,
            int chunkTopicLimit,
            String triggerSource
    ) {
    }
}
