package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainMemoryPack;
import com.hmrag.backend.domain.DomainRefineJob;
import com.hmrag.backend.repository.DomainMemoryPackRepository;
import com.hmrag.backend.repository.DomainRefineJobRepository;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.repository.TopicDefinitionRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DomainMemoryPackService {

    private final DomainMemoryPackRepository domainMemoryPackRepository;
    private final DomainRefineJobRepository domainRefineJobRepository;
    private final DomainDefinitionRepository domainDefinitionRepository;
    private final TopicDefinitionRepository topicDefinitionRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DomainMemoryPackService(
            DomainMemoryPackRepository domainMemoryPackRepository,
            DomainRefineJobRepository domainRefineJobRepository,
            DomainDefinitionRepository domainDefinitionRepository,
            TopicDefinitionRepository topicDefinitionRepository,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.domainMemoryPackRepository = domainMemoryPackRepository;
        this.domainRefineJobRepository = domainRefineJobRepository;
        this.domainDefinitionRepository = domainDefinitionRepository;
        this.topicDefinitionRepository = topicDefinitionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainMemoryPackItem> list(UUID domainId, UUID topicId, String triggerSource) {
        List<DomainMemoryPack> packs;
        if (topicId != null) {
            requireTopic(topicId);
            packs = domainMemoryPackRepository.findByTopicIdOrderByCreatedAtDesc(topicId);
        } else if (domainId != null) {
            requireDomain(domainId);
            packs = domainMemoryPackRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
        } else {
            packs = domainMemoryPackRepository.findAllByOrderByCreatedAtDesc();
        }
        Map<UUID, String> triggerSources = loadTriggerSources(packs);
        String normalizedTriggerSource = trimToNull(triggerSource);
        if (normalizedTriggerSource != null) {
            packs = packs.stream()
                    .filter(pack -> normalizedTriggerSource.equalsIgnoreCase(triggerSources.get(pack.getRefineJobId())))
                    .toList();
        }
        return packs.stream().map(pack -> toItem(pack, triggerSources)).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.DomainMemoryPackItem get(UUID id) {
        DomainMemoryPack pack = requirePack(id);
        return toItem(pack, loadTriggerSources(List.of(pack)));
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainMemoryPackItem> listForAgent(UUID domainId, UUID topicId, int limit) {
        List<DomainMemoryPack> packs = listCandidatePacks(domainId, topicId);
        Map<UUID, String> triggerSources = loadTriggerSources(packs);
        List<DomainMemoryPack> prioritized = prioritizeForAgent(packs);
        int safeLimit = Math.max(1, Math.min(20, limit));
        return prioritized.stream()
                .limit(safeLimit)
                .map(pack -> toItem(pack, triggerSources))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainEvidenceItem> listEvidence(UUID packId) {
        DomainMemoryPack pack = requirePack(packId);
        List<ApiDtos.DomainEvidenceItem> items = new ArrayList<>();
        for (String evidenceRef : pack.getEvidenceRefsJson()) {
            ApiDtos.DomainEvidenceItem item = resolveEvidenceItem(evidenceRef);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    @Transactional(readOnly = true)
    public ApiDtos.DomainEvidenceContextResponse getEvidenceContext(UUID packId, String evidenceRef, int window) {
        DomainMemoryPack pack = requirePack(packId);
        if (pack.getEvidenceRefsJson().stream().noneMatch(evidenceRef::equals)) {
            throw new IllegalArgumentException("证据引用不存在于当前知识包: " + evidenceRef);
        }
        return resolveEvidenceContext(evidenceRef, Math.max(0, window));
    }

    @Transactional
    public DomainMemoryPack save(DomainMemoryPack pack) {
        return domainMemoryPackRepository.save(pack);
    }

    @Transactional
    public ApiDtos.DomainMemoryPackItem updateReview(UUID id, ApiDtos.UpdateDomainMemoryPackReviewRequest request) {
        DomainMemoryPack pack = requirePack(id);
        String status = normalizeReviewStatus(request.status());
        pack.setStatus(status);

        Map<String, Object> snapshot = copyMap(pack.getSourceSnapshotJson());
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", status);
        review.put("note", trimToNull(request.note()));
        review.put("reviewedBy", trimToNull(request.reviewedBy()));
        review.put("reviewedAt", OffsetDateTime.now().toString());
        snapshot.put("review", review);
        pack.setSourceSnapshotJson(snapshot);

        DomainMemoryPack saved = domainMemoryPackRepository.save(pack);
        return toItem(saved, loadTriggerSources(List.of(saved)));
    }

    @Transactional
    public void delete(UUID id) {
        DomainMemoryPack pack = requirePack(id);
        domainMemoryPackRepository.delete(pack);
        domainMemoryPackRepository.flush();
    }

    @Transactional(readOnly = true)
    public DomainRefineJob requireJob(UUID jobId) {
        return domainRefineJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("精炼任务不存在: " + jobId));
    }

    private DomainMemoryPack requirePack(UUID id) {
        return domainMemoryPackRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("知识包不存在: " + id));
    }

    private List<DomainMemoryPack> listCandidatePacks(UUID domainId, UUID topicId) {
        if (topicId != null) {
            requireTopic(topicId);
            return domainMemoryPackRepository.findByTopicIdOrderByCreatedAtDesc(topicId);
        }
        if (domainId != null) {
            requireDomain(domainId);
            return domainMemoryPackRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
        }
        return domainMemoryPackRepository.findAllByOrderByCreatedAtDesc();
    }

    private void requireDomain(UUID id) {
        if (!domainDefinitionRepository.existsById(id)) {
            throw new EntityNotFoundException("领域不存在: " + id);
        }
    }

    private void requireTopic(UUID id) {
        if (!topicDefinitionRepository.existsById(id)) {
            throw new EntityNotFoundException("专题不存在: " + id);
        }
    }

    private ApiDtos.DomainMemoryPackItem toItem(DomainMemoryPack pack, Map<UUID, String> triggerSources) {
        return new ApiDtos.DomainMemoryPackItem(
                pack.getId(),
                pack.getDomainId(),
                pack.getTopicId(),
                pack.getRefineJobId(),
                pack.getArtifactType(),
                pack.getStatus(),
                pack.getRefineJobId() == null ? null : triggerSources.get(pack.getRefineJobId()),
                pack.getTitle(),
                pack.getSummary(),
                copyList(pack.getKeyPointsJson()),
                copyList(pack.getEvidenceRefsJson()),
                copyMap(pack.getSourceSnapshotJson()),
                copyMap(pack.getStructuredContentJson()),
                pack.getContentMarkdown(),
                pack.getModelProfile(),
                pack.getCreatedAt(),
                pack.getUpdatedAt()
        );
    }

    private List<DomainMemoryPack> prioritizeForAgent(List<DomainMemoryPack> packs) {
        List<DomainMemoryPack> accepted = packs.stream()
                .filter(pack -> "accepted".equalsIgnoreCase(pack.getStatus()))
                .sorted((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()))
                .toList();
        if (!accepted.isEmpty()) {
            return accepted;
        }

        List<DomainMemoryPack> ready = packs.stream()
                .filter(pack -> "ready".equalsIgnoreCase(pack.getStatus()))
                .sorted((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()))
                .toList();
        if (!ready.isEmpty()) {
            return ready;
        }

        return packs.stream()
                .filter(pack -> "reference".equalsIgnoreCase(pack.getStatus()))
                .sorted((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()))
                .toList();
    }

    private Map<UUID, String> loadTriggerSources(List<DomainMemoryPack> packs) {
        List<UUID> jobIds = packs.stream()
                .map(DomainMemoryPack::getRefineJobId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (jobIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<UUID, String> triggerSources = new HashMap<>();
        domainRefineJobRepository.findAllById(jobIds).forEach(job -> triggerSources.put(job.getId(), job.getTriggerSource()));
        return triggerSources;
    }

    private ApiDtos.DomainEvidenceItem resolveEvidenceItem(String evidenceRef) {
        EvidenceRef ref = parseRef(evidenceRef);
        if (ref == null) {
            return null;
        }
        return switch (ref.type()) {
            case "document" -> fetchDocumentEvidence(ref);
            case "knowledge_unit" -> fetchKnowledgeUnitEvidence(ref);
            case "chunk" -> fetchChunkEvidence(ref);
            default -> null;
        };
    }

    private ApiDtos.DomainEvidenceContextResponse resolveEvidenceContext(String evidenceRef, int window) {
        EvidenceRef ref = parseRef(evidenceRef);
        if (ref == null) {
            throw new IllegalArgumentException("非法证据引用: " + evidenceRef);
        }
        return switch (ref.type()) {
            case "document" -> fetchDocumentContext(ref, window);
            case "knowledge_unit" -> fetchKnowledgeUnitContext(ref, window);
            case "chunk" -> fetchChunkContext(ref, window);
            default -> throw new IllegalArgumentException("不支持的证据类型: " + ref.type());
        };
    }

    private ApiDtos.DomainEvidenceItem fetchDocumentEvidence(EvidenceRef ref) {
        List<ApiDtos.DomainEvidenceItem> items = jdbcTemplate.query("""
                SELECT d.id, d.title, d.source_file, c.id AS chunk_id, c.page_no, c.content
                FROM documents d
                LEFT JOIN LATERAL (
                    SELECT c.id, c.page_no, c.content
                    FROM chunks c
                    WHERE c.doc_id = d.id
                    ORDER BY c.chunk_no ASC
                    LIMIT 1
                ) c ON TRUE
                WHERE d.id = :id
                """, new MapSqlParameterSource("id", ref.id()), (rs, rowNum) ->
                new ApiDtos.DomainEvidenceItem(
                        ref.raw(),
                        "document",
                        rs.getObject("id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class),
                        rs.getString("title"),
                        abbreviate(rs.getString("content")),
                        rs.getString("source_file"),
                        (Integer) rs.getObject("page_no")
                ));
        return items.isEmpty() ? null : items.get(0);
    }

    private ApiDtos.DomainEvidenceItem fetchKnowledgeUnitEvidence(EvidenceRef ref) {
        List<ApiDtos.DomainEvidenceItem> items = jdbcTemplate.query("""
                SELECT ku.id, ku.doc_id, ku.chunk_id, ku.title, ku.content, ku.source_page, d.source_file
                FROM knowledge_units ku
                JOIN documents d ON d.id = ku.doc_id
                WHERE ku.id = :id
                """, new MapSqlParameterSource("id", ref.id()), (rs, rowNum) ->
                new ApiDtos.DomainEvidenceItem(
                        ref.raw(),
                        "knowledge_unit",
                        rs.getObject("id", UUID.class),
                        rs.getObject("doc_id", UUID.class),
                        rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class),
                        rs.getString("title"),
                        abbreviate(rs.getString("content")),
                        rs.getString("source_file"),
                        (Integer) rs.getObject("source_page")
                ));
        return items.isEmpty() ? null : items.get(0);
    }

    private ApiDtos.DomainEvidenceItem fetchChunkEvidence(EvidenceRef ref) {
        List<ApiDtos.DomainEvidenceItem> items = jdbcTemplate.query("""
                SELECT c.id, c.doc_id, c.title, c.content, c.page_no, d.source_file
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                WHERE c.id = :id
                """, new MapSqlParameterSource("id", ref.id()), (rs, rowNum) ->
                new ApiDtos.DomainEvidenceItem(
                        ref.raw(),
                        "chunk",
                        rs.getObject("id", UUID.class),
                        rs.getObject("doc_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        abbreviate(rs.getString("content")),
                        rs.getString("source_file"),
                        (Integer) rs.getObject("page_no")
                ));
        return items.isEmpty() ? null : items.get(0);
    }

    private ApiDtos.DomainEvidenceContextResponse fetchDocumentContext(EvidenceRef ref, int window) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT d.id, d.title, d.source_file, c.id AS chunk_id, c.page_no, c.content
                FROM documents d
                LEFT JOIN chunks c ON c.doc_id = d.id
                WHERE d.id = :id
                ORDER BY c.chunk_no ASC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("id", ref.id())
                .addValue("limit", Math.max(1, window == 0 ? 3 : window)), (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("docId", rs.getObject("id", UUID.class));
            item.put("title", rs.getString("title"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("chunkId", rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class));
            item.put("pageNo", rs.getObject("page_no"));
            item.put("content", rs.getString("content"));
            return item;
        });
        if (rows.isEmpty()) {
            throw new EntityNotFoundException("文档证据不存在: " + ref.id());
        }
        Map<String, Object> first = rows.get(0);
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (row.get("content") != null) {
                context.append(abbreviate((String) row.get("content"))).append("\n");
            }
        }
        return new ApiDtos.DomainEvidenceContextResponse(
                ref.raw(),
                "document",
                (UUID) first.get("docId"),
                (UUID) first.get("docId"),
                (UUID) first.get("chunkId"),
                (String) first.get("title"),
                context.toString().trim(),
                context.toString().trim(),
                (String) first.get("sourceFile"),
                (Integer) first.get("pageNo")
        );
    }

    private ApiDtos.DomainEvidenceContextResponse fetchKnowledgeUnitContext(EvidenceRef ref, int window) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT ku.id, ku.doc_id, ku.chunk_id, ku.title, ku.content, ku.source_page, d.source_file,
                       c.content AS chunk_content
                FROM knowledge_units ku
                JOIN documents d ON d.id = ku.doc_id
                LEFT JOIN chunks c ON c.id = ku.chunk_id
                WHERE ku.id = :id
                """, new MapSqlParameterSource("id", ref.id()), (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("resourceId", rs.getObject("id", UUID.class));
            item.put("docId", rs.getObject("doc_id", UUID.class));
            item.put("chunkId", rs.getObject("chunk_id") == null ? null : rs.getObject("chunk_id", UUID.class));
            item.put("title", rs.getString("title"));
            item.put("content", rs.getString("content"));
            item.put("pageNo", rs.getObject("source_page"));
            item.put("sourceFile", rs.getString("source_file"));
            item.put("chunkContent", rs.getString("chunk_content"));
            return item;
        });
        if (rows.isEmpty()) {
            throw new EntityNotFoundException("知识单元证据不存在: " + ref.id());
        }
        Map<String, Object> row = rows.get(0);
        String context = row.get("chunkContent") == null ? (String) row.get("content") : (String) row.get("chunkContent");
        return new ApiDtos.DomainEvidenceContextResponse(
                ref.raw(),
                "knowledge_unit",
                (UUID) row.get("resourceId"),
                (UUID) row.get("docId"),
                (UUID) row.get("chunkId"),
                (String) row.get("title"),
                (String) row.get("content"),
                abbreviate(context),
                (String) row.get("sourceFile"),
                (Integer) row.get("pageNo")
        );
    }

    private ApiDtos.DomainEvidenceContextResponse fetchChunkContext(EvidenceRef ref, int window) {
        List<Map<String, Object>> baseRows = jdbcTemplate.query("""
                SELECT c.id, c.doc_id, c.chunk_no, c.title, c.content, c.page_no, d.source_file
                FROM chunks c
                JOIN documents d ON d.id = c.doc_id
                WHERE c.id = :id
                """, new MapSqlParameterSource("id", ref.id()), (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("resourceId", rs.getObject("id", UUID.class));
            item.put("docId", rs.getObject("doc_id", UUID.class));
            item.put("chunkNo", rs.getInt("chunk_no"));
            item.put("title", rs.getString("title"));
            item.put("content", rs.getString("content"));
            item.put("pageNo", rs.getObject("page_no"));
            item.put("sourceFile", rs.getString("source_file"));
            return item;
        });
        if (baseRows.isEmpty()) {
            throw new EntityNotFoundException("正文片段证据不存在: " + ref.id());
        }
        Map<String, Object> base = baseRows.get(0);
        int safeWindow = Math.max(0, window == 0 ? 1 : window);
        List<String> contextParts = jdbcTemplate.query("""
                SELECT c.content
                FROM chunks c
                WHERE c.doc_id = :docId
                  AND c.chunk_no BETWEEN :startNo AND :endNo
                ORDER BY c.chunk_no ASC
                """, new MapSqlParameterSource()
                .addValue("docId", base.get("docId"))
                .addValue("startNo", Math.max(1, (Integer) base.get("chunkNo") - safeWindow))
                .addValue("endNo", (Integer) base.get("chunkNo") + safeWindow), (rs, rowNum) -> abbreviate(rs.getString("content")));
        return new ApiDtos.DomainEvidenceContextResponse(
                ref.raw(),
                "chunk",
                (UUID) base.get("resourceId"),
                (UUID) base.get("docId"),
                (UUID) base.get("resourceId"),
                (String) base.get("title"),
                (String) base.get("content"),
                String.join("\n", contextParts),
                (String) base.get("sourceFile"),
                (Integer) base.get("pageNo")
        );
    }

    private EvidenceRef parseRef(String evidenceRef) {
        if (evidenceRef == null || !evidenceRef.contains(":")) {
            return null;
        }
        String[] parts = evidenceRef.split(":", 2);
        try {
            return new EvidenceRef(parts[0], UUID.fromString(parts[1]), evidenceRef);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "...";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeReviewStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            throw new IllegalArgumentException("知识包审核状态不能为空");
        }
        String lowered = normalized.toLowerCase();
        if (!List.of("accepted", "reference", "ready").contains(lowered)) {
            throw new IllegalArgumentException("不支持的知识包审核状态: " + status);
        }
        return lowered;
    }

    private record EvidenceRef(String type, UUID id, String raw) {
    }
}
