package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hmrag.backend.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeGraphStoreClient {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,，。；;、！？!？/\\\\|()（）【】\\[\\]<>《》\"'“”]+");
    private static final Pattern SOURCE_FILE_EXTENSION = Pattern.compile("(?i).*\\.(docx?|pdf|xlsx?|pptx?|txt|md)$");
    private static final Pattern IMPORT_SUFFIX = Pattern.compile(".*_[0-9]{3,}$");
    private static final Pattern VALUE_LIKE_ENTITY_NAME = Pattern.compile(
            "^[<>≥≤=约近不少于不低于超过]*[0-9]+(\\.[0-9]+)?(万|亿)?(元|万元|亿元|万|亿|人|所|个|项|次|篇|套|份|位|年|月|日|小时|分钟|秒|%|％).*$"
    );
    private static final Pattern DATE_LIKE_ENTITY_NAME = Pattern.compile(
            "^(19|20)[0-9]{2}([年./．-][0-9]{1,2}){0,2}日?([~至—-](19|20)?[0-9]{0,4}([年./．-][0-9]{1,2}){0,2}日?)?$"
    );

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public KnowledgeGraphStoreClient(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public boolean isConfigured() {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        return config != null
                && config.enabled()
                && config.storeBaseUrl() != null
                && !config.storeBaseUrl().isBlank()
                && ("neo4j-http".equals(provider()) || "memgraph-http".equals(provider()) || "cypher-http".equals(provider()));
    }

    public String provider() {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        String provider = config == null ? "neo4j-http" : config.storeProvider();
        return provider == null || provider.isBlank() ? "neo4j-http" : provider.trim().toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> readGraphView(int targetEntities) {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        int safeTargetEntities = Math.max(1, Math.min(targetEntities, 120));
        int safeLimit = Math.min(1000, Math.max(120, safeTargetEntities * 12));
        JsonNode root = query("""
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                OPTIONAL MATCH (subjectEntity)-[:MEMBER_OF]->(subjectCluster:EntityCluster)
                OPTIONAL MATCH (objectEntity)-[:MEMBER_OF]->(objectCluster:EntityCluster)
                CALL {
                    WITH subjectEntity
                    OPTIONAL MATCH (subjectEntity)-[:HAS_STATE]->(:EntityState)-[sourceOutTransition:EVOLVES_TO]->(:EntityState)<-[:HAS_STATE]-(:Entity)
                    OPTIONAL MATCH (subjectEntity)-[:HAS_STATE]->(:EntityState)<-[sourceInTransition:EVOLVES_TO]-(:EntityState)<-[:HAS_STATE]-(:Entity)
                    RETURN count(DISTINCT sourceOutTransition) + count(DISTINCT sourceInTransition) AS sourceTransitionCount
                }
                CALL {
                    WITH objectEntity
                    OPTIONAL MATCH (objectEntity)-[:HAS_STATE]->(:EntityState)-[targetOutTransition:EVOLVES_TO]->(:EntityState)<-[:HAS_STATE]-(:Entity)
                    OPTIONAL MATCH (objectEntity)-[:HAS_STATE]->(:EntityState)<-[targetInTransition:EVOLVES_TO]-(:EntityState)<-[:HAS_STATE]-(:Entity)
                    RETURN count(DISTINCT targetOutTransition) + count(DISTINCT targetInTransition) AS targetTransitionCount
                }
                RETURN
                    subjectEntity.canonicalKey AS sourceId,
                    subjectEntity.canonicalName AS sourceName,
                    subjectEntity.entityType AS sourceType,
                    objectEntity.canonicalKey AS targetId,
                    objectEntity.canonicalName AS targetName,
                    objectEntity.entityType AS targetType,
                    fact.factKey AS edgeId,
                    fact.relationType AS relationType,
                    fact.statement AS statement,
                    fact.confidence AS confidence,
                    fact.updatedAt AS updatedAt,
                    subjectCluster.canonicalName AS subjectCanonicalName,
                    subjectCluster.fusionKey AS subjectFusionKey,
                    objectCluster.canonicalName AS objectCanonicalName,
                    objectCluster.fusionKey AS objectFusionKey,
                    count(DISTINCT CASE
                        WHEN coalesce(subject.stateKind, '') <> 'default_anchor'
                        THEN coalesce(subject.stateClusterKey, subject.stateKey)
                    END) AS sourceStateCount,
                    count(DISTINCT CASE
                        WHEN coalesce(object.stateKind, '') <> 'default_anchor'
                        THEN coalesce(object.stateClusterKey, object.stateKey)
                    END) AS targetStateCount,
                    sourceTransitionCount,
                    targetTransitionCount,
                    collect(DISTINCT evidence.docId)[0..3] AS docIds
                ORDER BY updatedAt DESC
                LIMIT $limit
                """, Map.of("limit", safeLimit));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (data.isArray()) {
            for (JsonNode rowNode : data) {
                JsonNode row = rowNode.path("row");
                if (!row.isArray() || row.size() < 20) {
                    continue;
                }
                String sourceId = textAt(row, 0);
                String sourceName = textAt(row, 1);
                String sourceType = textAt(row, 2);
                String targetId = textAt(row, 3);
                String targetName = textAt(row, 4);
                String targetType = textAt(row, 5);
                if (sourceId.isBlank() || targetId.isBlank()
                        || isHiddenGraphEntity(sourceName, sourceType)
                        || isHiddenGraphEntity(targetName, targetType)
                        || isValueLikeGraphEntity(sourceName, sourceType)
                        || isValueLikeGraphEntity(targetName, targetType)) {
                    continue;
                }
                boolean sourceExists = nodes.containsKey(sourceId);
                boolean targetExists = nodes.containsKey(targetId);
                int newNodeCount = (sourceExists ? 0 : 1) + (targetExists ? 0 : 1);
                if (nodes.size() + newNodeCount > safeTargetEntities) {
                    if (!(sourceExists && targetExists)) {
                        continue;
                    }
                }
                String edgeId = textAt(row, 6);
                String relationType = textAt(row, 7);
                String statement = textAt(row, 8);
                double confidence = row.path(9).isNumber() ? row.path(9).asDouble() : 0.0d;
                String subjectCanonicalName = textAt(row, 11);
                String subjectFusionKey = textAt(row, 12);
                String objectCanonicalName = textAt(row, 13);
                String objectFusionKey = textAt(row, 14);
                int sourceStateCount = intAt(row, 15);
                int targetStateCount = intAt(row, 16);
                int sourceTransitionCount = intAt(row, 17);
                int targetTransitionCount = intAt(row, 18);
                List<String> docIds = new ArrayList<>();
                JsonNode docIdNode = row.path(19);
                if (docIdNode.isArray()) {
                    for (JsonNode item : docIdNode) {
                        if (!item.isNull() && !item.asText("").isBlank()) {
                            docIds.add(item.asText());
                        }
                    }
                }
                nodes.putIfAbsent(sourceId, graphNode(sourceId, sourceName, sourceType, subjectCanonicalName, subjectFusionKey, sourceStateCount, sourceTransitionCount));
                nodes.putIfAbsent(targetId, graphNode(targetId, targetName, targetType, objectCanonicalName, objectFusionKey, targetStateCount, targetTransitionCount));
                edges.add(Map.of(
                        "id", blankTo(edgeId, sourceId + "->" + targetId + ":" + edges.size()),
                        "source", sourceId,
                        "target", targetId,
                        "type", blankTo(relationType, "related_to"),
                        "label", blankTo(statement, relationType),
                        "confidence", confidence,
                        "docIds", docIds
                ));
            }
        }
        return Map.of(
                "nodes", new ArrayList<>(nodes.values()),
                "edges", edges,
                "stats", readGraphStats(),
                "targetEntities", safeTargetEntities,
                "factLimit", safeLimit
        );
    }

    public Map<String, Object> readTopConnectedGraphView(int targetEntities) {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        int safeTargetEntities = Math.max(10, Math.min(targetEntities, 120));
        int candidateLimit = Math.min(600, safeTargetEntities * 6);
        JsonNode entityRoot = query("""
                MATCH (e:Entity)
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)-[:SUBJECT_OF]->(outFact:Fact)
                    WHERE coalesce(outFact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN count(DISTINCT outFact) AS outgoingFacts
                }
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)<-[:OBJECT_OF]-(inFact:Fact)
                    WHERE coalesce(inFact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN count(DISTINCT inFact) AS incomingFacts
                }
                CALL {
                    WITH e
                    OPTIONAL MATCH (e)-[:HAS_STATE]->(state:EntityState)
                    RETURN count(DISTINCT CASE
                        WHEN coalesce(state.stateKind, '') <> 'default_anchor'
                        THEN coalesce(state.stateClusterKey, state.stateKey)
                    END) AS stateCount
                }
                CALL {
                    WITH e
                    OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)-[outTransition:EVOLVES_TO]->(:EntityState)<-[:HAS_STATE]-(:Entity)
                    OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)<-[inTransition:EVOLVES_TO]-(:EntityState)<-[:HAS_STATE]-(:Entity)
                    RETURN count(DISTINCT outTransition) + count(DISTINCT inTransition) AS transitionCount
                }
                OPTIONAL MATCH (e)-[:MEMBER_OF]->(cluster:EntityCluster)
                WITH e, cluster, outgoingFacts + incomingFacts AS connectionCount, stateCount, transitionCount
                WHERE connectionCount > 0
                RETURN e.canonicalKey AS id,
                       e.canonicalName AS name,
                       e.entityType AS type,
                       cluster.canonicalName AS clusterName,
                       cluster.fusionKey AS clusterKey,
                       stateCount,
                       transitionCount,
                       connectionCount,
                       e.updatedAt AS updatedAt
                ORDER BY connectionCount DESC, stateCount DESC, name
                LIMIT $limit
                """, Map.of("limit", candidateLimit));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> topEntities = new ArrayList<>();
        JsonNode entityData = entityRoot.path("results").path(0).path("data");
        if (entityData.isArray()) {
            for (JsonNode rowNode : entityData) {
                JsonNode row = rowNode.path("row");
                if (!row.isArray() || row.size() < 9) {
                    continue;
                }
                String id = textAt(row, 0);
                String name = textAt(row, 1);
                String type = textAt(row, 2);
                if (id.isBlank()
                        || isHiddenGraphEntity(name, type)
                        || isValueLikeGraphEntity(name, type)) {
                    continue;
                }
                int stateCount = intAt(row, 5);
                int transitionCount = intAt(row, 6);
                int connectionCount = intAt(row, 7);
                Map<String, Object> node = graphNode(id, name, type, textAt(row, 3), textAt(row, 4), stateCount, transitionCount);
                node.put("connectionCount", connectionCount);
                nodes.put(id, node);
                Map<String, Object> entity = new LinkedHashMap<>();
                entity.put("id", id);
                entity.put("label", name);
                entity.put("type", type);
                entity.put("clusterName", textAt(row, 3));
                entity.put("clusterKey", textAt(row, 4));
                entity.put("stateCount", stateCount);
                entity.put("factCount", connectionCount);
                entity.put("transitionCount", transitionCount);
                entity.put("updatedAt", textAt(row, 8));
                entity.put("hasEntityState", stateCount > 0);
                entity.put("hasMultipleStates", stateCount > 1);
                entity.put("hasEvolution", transitionCount > 0);
                topEntities.add(entity);
                if (nodes.size() >= safeTargetEntities) {
                    break;
                }
            }
        }
        List<String> selectedIds = new ArrayList<>(nodes.keySet());
        List<Map<String, Object>> edges = new ArrayList<>();
        if (!selectedIds.isEmpty()) {
            JsonNode edgeRoot = query("""
                    MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                      AND subjectEntity.canonicalKey IN $ids
                      AND objectEntity.canonicalKey IN $ids
                    OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                    RETURN subjectEntity.canonicalKey AS sourceId,
                           objectEntity.canonicalKey AS targetId,
                           fact.factKey AS edgeId,
                           fact.relationType AS relationType,
                           fact.statement AS statement,
                           fact.confidence AS confidence,
                           fact.updatedAt AS updatedAt,
                           collect(DISTINCT evidence.docId)[0..3] AS docIds
                    ORDER BY updatedAt DESC
                    LIMIT $limit
                    """, Map.of("ids", selectedIds, "limit", Math.min(1000, safeTargetEntities * 16)));
            JsonNode edgeData = edgeRoot.path("results").path(0).path("data");
            if (edgeData.isArray()) {
                for (JsonNode rowNode : edgeData) {
                    JsonNode row = rowNode.path("row");
                    if (!row.isArray() || row.size() < 8) {
                        continue;
                    }
                    String sourceId = textAt(row, 0);
                    String targetId = textAt(row, 1);
                    if (!nodes.containsKey(sourceId) || !nodes.containsKey(targetId)) {
                        continue;
                    }
                    List<String> docIds = new ArrayList<>();
                    JsonNode docIdNode = row.path(7);
                    if (docIdNode.isArray()) {
                        for (JsonNode item : docIdNode) {
                            if (!item.isNull() && !item.asText("").isBlank()) {
                                docIds.add(item.asText());
                            }
                        }
                    }
                    edges.add(Map.of(
                            "id", blankTo(textAt(row, 2), sourceId + "->" + targetId + ":" + edges.size()),
                            "source", sourceId,
                            "target", targetId,
                            "type", blankTo(textAt(row, 3), "related_to"),
                            "label", blankTo(textAt(row, 4), textAt(row, 3)),
                            "confidence", row.path(5).isNumber() ? row.path(5).asDouble() : 0.0d,
                            "docIds", docIds
                    ));
                }
            }
        }
        return Map.of(
                "nodes", new ArrayList<>(nodes.values()),
                "edges", edges,
                "stats", readGraphStats(),
                "targetEntities", safeTargetEntities,
                "scope", "top_connected",
                "topEntities", topEntities
        );
    }

    public Map<String, Object> readGraphStats() {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        JsonNode root = query("""
                CALL {
                    MATCH (e:Entity)
                    RETURN count(e) AS entities
                }
                CALL {
                    MATCH (s:EntityState)
                    RETURN count(s) AS rawStates,
                           count(CASE WHEN coalesce(s.stateKind, '') <> 'default_anchor' THEN s END) AS states,
                           count(CASE WHEN coalesce(s.stateKind, '') = 'default_anchor' THEN s END) AS defaultAnchorStates
                }
                CALL {
                    MATCH (m:Mention)
                    RETURN count(m) AS mentions
                }
                CALL {
                    MATCH (f:Fact)
                    RETURN count(f) AS facts
                }
                CALL {
                    MATCH (ev:Evidence)
                    RETURN count(ev) AS evidence
                }
                CALL {
                    MATCH (c:ChunkRef)
                    RETURN count(c) AS chunks
                }
                CALL {
                    MATCH (cluster:EntityCluster)
                    RETURN count(cluster) AS clusters
                }
                CALL {
                    MATCH (:EntityState)-[transition:EVOLVES_TO]->(:EntityState)
                    RETURN count(transition) AS transitionEdges
                }
                RETURN entities, states, rawStates, defaultAnchorStates, mentions, facts, evidence, chunks, clusters, transitionEdges
                """, Map.of());
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        return Map.of(
                "entities", intAt(row, 0),
                "states", intAt(row, 1),
                "rawStates", intAt(row, 2),
                "defaultAnchorStates", intAt(row, 3),
                "mentions", intAt(row, 4),
                "facts", intAt(row, 5),
                "evidence", intAt(row, 6),
                "chunks", intAt(row, 7),
                "clusters", intAt(row, 8),
                "transitionEdges", intAt(row, 9)
        );
    }

    public Map<String, Object> readGraphQuality() {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        Map<String, Object> metrics = readGraphQualityMetrics();
        return Map.of(
                "enabled", true,
                "metrics", metrics,
                "risks", readGraphQualityRisks(metrics),
                "samples", Map.of(
                        "unlinkedMentionEntities", readQualityRows("""
                                MATCH (e:Entity)-[:HAS_STATE]->(s:EntityState)
                                WHERE NOT (s)-[:SUBJECT_OF]->(:Fact)
                                  AND NOT (:Fact)-[:OBJECT_OF]->(s)
                                  AND NOT (s)-[:HAS_ATTRIBUTE_FACT]->(:Fact)
                                  AND coalesce(s.stateKind, '') = 'default_anchor'
                                MATCH (s)-[:MENTIONED_IN]->(chunk:ChunkRef)
                                OPTIONAL MATCH (d:Document {docId: coalesce(chunk.docId, '')})
                                RETURN e.canonicalName AS name,
                                       e.entityType AS type,
                                       collect(DISTINCT coalesce(chunk.title, ''))[0..3] AS sections,
                                       collect(DISTINCT coalesce(d.title, ''))[0..2] AS documents,
                                       coalesce(e.fusionEligible, false) AS fusionEligible,
                                       coalesce(e.fusionStatus, 'candidate') AS fusionStatus
                                ORDER BY name
                                LIMIT 30
                                """),
                        "suspiciousEntities", readQualityRows("""
                                MATCH (e:Entity)
                                WHERE size(coalesce(e.canonicalName, '')) <= 1
                                   OR coalesce(e.canonicalName, '') =~ '^[0-9.]+$'
                                   OR toLower(coalesce(e.canonicalName, '')) STARTS WITH 'liushl_'
                                RETURN e.canonicalName AS name,
                                       e.entityType AS type,
                                       coalesce(e.fusionEligible, false) AS fusionEligible
                                ORDER BY name
                                LIMIT 30
                                """),
                        "structureNoise", readQualityRows("""
                                MATCH (f:Fact)
                                WHERE coalesce(f.governanceStage, '') = 'structure_enhancement'
                                  AND (
                                      coalesce(f.statement, '') =~ '.*「[0-9.]+」.*'
                                      OR coalesce(f.statement, '') =~ '.*(字段|单价|支出标准|计量单位|备注|序号).*'
                                  )
                                RETURN coalesce(f.statement, '') AS statement,
                                       coalesce(f.confidence, 0.0) AS confidence
                                ORDER BY confidence ASC
                                LIMIT 30
                                """),
                        "relationsByType", readQualityRows("""
                                MATCH (f:Fact)
                                WHERE coalesce(f.factKind, '') = 'relation_fact'
                                RETURN coalesce(f.relationType, f.predicate, '') AS relationType,
                                       coalesce(f.governanceStage, '') AS governanceStage,
                                       count(f) AS count
                                ORDER BY count DESC
                                LIMIT 20
                                """)
                ),
                "actions", List.of(
                        Map.of("stage", "attribute_governance", "label", "处理属性候选", "reason", "候选属性过多会继续污染关系和状态。", "href", "/ui/graph-governance.html"),
                        Map.of("stage", "structure_enhancement", "label", "复查结构增强", "reason", "结构关系只用于上下文增强，字段/数字章节需要降噪。", "href", "/ui/graph-structure.html"),
                        Map.of("stage", "fusion", "label", "复查实体融合", "reason", "确认同名、近名融合后没有过合并。", "href", "/ui/graph-fusion.html"),
                        Map.of("stage", "browse", "label", "抽样浏览实体子图", "reason", "对高价值实体检查属性、关系、状态、演化链是否一致。", "href", "/ui/graph.html")
                )
        );
    }

    private Map<String, Object> readGraphQualityMetrics() {
        JsonNode root = query("""
                CALL {
                    MATCH (e:Entity)
                    RETURN count(e) AS entities,
                           count(CASE WHEN coalesce(e.fusionEligible, false) THEN e END) AS fusionEligibleEntities
                }
                CALL {
                    MATCH (f:Fact)
                    RETURN count(f) AS facts,
                           count(CASE WHEN coalesce(f.factKind, '') = 'relation_fact' THEN f END) AS relationFacts,
                           count(CASE WHEN coalesce(f.factKind, '') = 'attribute_fact' THEN f END) AS attributeFacts,
                           count(CASE WHEN coalesce(f.factKind, '') = 'attribute_candidate' THEN f END) AS attributeCandidates
                }
                CALL {
                    MATCH (cluster:EntityCluster)
                    RETURN count(cluster) AS entityClusters
                }
                CALL {
                    MATCH (s:EntityState)
                    RETURN count(CASE WHEN coalesce(s.stateKind, '') <> 'default_anchor' THEN s END) AS realStates
                }
                CALL {
                    MATCH (:EntityState)-[transition:EVOLVES_TO]->(:EntityState)
                    RETURN count(transition) AS transitionEdges
                }
                CALL {
                    MATCH (e:Entity)-[:HAS_STATE]->(s:EntityState)
                    WHERE NOT (s)-[:SUBJECT_OF]->(:Fact)
                      AND NOT (:Fact)-[:OBJECT_OF]->(s)
                      AND NOT (s)-[:HAS_ATTRIBUTE_FACT]->(:Fact)
                      AND coalesce(s.stateKind, '') = 'default_anchor'
                    OPTIONAL MATCH (s)-[:MENTIONED_IN]->(chunk:ChunkRef)
                    OPTIONAL MATCH (:Mention)-[:REFERS_TO]->(s)
                    RETURN count(DISTINCT e) AS unlinkedMentionEntities,
                           count(DISTINCT CASE WHEN chunk IS NULL THEN e END) AS trueIsolatedEntities
                }
                CALL {
                    MATCH (e:Entity)
                    WHERE size(coalesce(e.canonicalName, '')) <= 1
                       OR coalesce(e.canonicalName, '') =~ '^[0-9.]+$'
                       OR coalesce(e.canonicalName, '') =~ '^[<>≥≤=约近不少于不低于超过]*[0-9]+(\\\\.[0-9]+)?(万|亿)?(元|万元|亿元|万|亿|人|所|个|项|次|篇|套|份|位|年|月|日|小时|分钟|秒|%|％).*$'
                       OR coalesce(e.canonicalName, '') =~ '^(19|20)[0-9]{2}([年./．-][0-9]{1,2}){0,2}日?([~至—-](19|20)?[0-9]{0,4}([年./．-][0-9]{1,2}){0,2}日?)?$'
                       OR toLower(coalesce(e.canonicalName, '')) STARTS WITH 'liushl_'
                    RETURN count(e) AS suspiciousNameEntities
                }
                CALL {
                    MATCH (f:Fact)
                    WHERE coalesce(f.governanceStage, '') = 'structure_enhancement'
                    RETURN count(f) AS structureFacts,
                           count(CASE WHEN coalesce(f.statement, '') =~ '.*「[0-9.]+」.*' THEN f END) AS numericStructureFacts,
                           count(CASE WHEN coalesce(f.statement, '') =~ '.*(字段|单价|支出标准|计量单位|备注|序号).*' THEN f END) AS fieldLikeStructureFacts
                }
                RETURN entities, fusionEligibleEntities, facts, relationFacts, attributeFacts, attributeCandidates,
                       entityClusters, realStates, transitionEdges, unlinkedMentionEntities, trueIsolatedEntities, suspiciousNameEntities,
                       structureFacts, numericStructureFacts, fieldLikeStructureFacts
                """, Map.of());
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        int entities = intAt(row, 0);
        int relationFacts = intAt(row, 3);
        return Map.ofEntries(
                Map.entry("entities", entities),
                Map.entry("fusionEligibleEntities", intAt(row, 1)),
                Map.entry("facts", intAt(row, 2)),
                Map.entry("relationFacts", relationFacts),
                Map.entry("attributeFacts", intAt(row, 4)),
                Map.entry("attributeCandidates", intAt(row, 5)),
                Map.entry("entityClusters", intAt(row, 6)),
                Map.entry("realStates", intAt(row, 7)),
                Map.entry("transitionEdges", intAt(row, 8)),
                Map.entry("unlinkedMentionEntities", intAt(row, 9)),
                Map.entry("trueIsolatedEntities", intAt(row, 10)),
                Map.entry("suspiciousNameEntities", intAt(row, 11)),
                Map.entry("structureFacts", intAt(row, 12)),
                Map.entry("numericStructureFacts", intAt(row, 13)),
                Map.entry("fieldLikeStructureFacts", intAt(row, 14)),
                Map.entry("relationPerEntity", entities == 0 ? 0.0d : Math.round((relationFacts * 100.0d / entities)) / 100.0d)
        );
    }

    private List<Map<String, Object>> readGraphQualityRisks(Map<String, Object> metrics) {
        List<Map<String, Object>> risks = new ArrayList<>();
        int attributeCandidates = intObject(metrics.get("attributeCandidates"));
        int unlinkedMentionEntities = intObject(metrics.get("unlinkedMentionEntities"));
        int trueIsolatedEntities = intObject(metrics.get("trueIsolatedEntities"));
        int suspiciousNameEntities = intObject(metrics.get("suspiciousNameEntities"));
        int structureNoise = intObject(metrics.get("numericStructureFacts")) + intObject(metrics.get("fieldLikeStructureFacts"));
        double relationPerEntity = doubleValue(metrics.get("relationPerEntity"));
        if (attributeCandidates > 0) {
            risks.add(qualityRisk("属性候选仍有积压", "attribute_governance", attributeCandidates, "候选属性应继续治理，避免属性继续混在实体关系中。", "/ui/graph-governance.html"));
        }
        if (structureNoise > 0) {
            risks.add(qualityRisk("结构增强存在噪声", "structure_enhancement", structureNoise, "数字章节或字段类结构关系需要降噪。", "/ui/graph-structure.html"));
        }
        if (suspiciousNameEntities > 0) {
            risks.add(qualityRisk("疑似低质量实体名", "entity_extraction", suspiciousNameEntities, "短词、纯数字、导入痕迹不应进入核心图谱。", "/ui/graph-local.html"));
        }
        if (trueIsolatedEntities > 0) {
            risks.add(qualityRisk("真正孤立实体较多", "entity_extraction", trueIsolatedEntities, "没有章节或提及证据的实体应回收或重建。", "/ui/graph-local.html"));
        }
        if (unlinkedMentionEntities > 0) {
            risks.add(qualityRisk("未建语义关系的章节提及较多", "relation_enrichment", unlinkedMentionEntities, "这些实体有章节证据，但尚未形成语义关系，可继续通过结构增强、关系抽取或属性治理处理。", "/ui/graph-structure.html"));
        }
        if (relationPerEntity < 0.5d && intObject(metrics.get("entities")) > 0) {
            risks.add(qualityRisk("实体关系密度偏低", "relation_enrichment", (int) Math.round(relationPerEntity * 100), "当前关系不足以支撑复杂图查询，需要继续增强关系或过滤低价值实体。", "/ui/graph-structure.html"));
        }
        if (risks.isEmpty()) {
            risks.add(qualityRisk("未发现明显阻断风险", "ok", 0, "当前图谱可以进入查询和领域知识构建验证。", "/ui/graph.html"));
        }
        return risks;
    }

    private Map<String, Object> qualityRisk(String title, String stage, int count, String detail, String href) {
        return Map.of(
                "title", title,
                "stage", stage,
                "count", count,
                "detail", detail,
                "href", href
        );
    }

    private List<Map<String, Object>> readQualityRows(String cypher) {
        JsonNode root = query(cypher, Map.of());
        JsonNode result = root.path("results").path(0);
        JsonNode columns = result.path("columns");
        JsonNode data = result.path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode row = item.path("row");
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (int i = 0; i < row.size(); i++) {
                String key = columns.isArray() && columns.size() > i ? columns.path(i).asText("col" + i) : "col" + i;
                mapped.put(key, jsonScalar(row.path(i)));
            }
            rows.add(mapped);
        }
        return rows;
    }

    public Map<String, Object> readEntityList(String search, int limit) {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int queryLimit = Math.min(1500, Math.max(safeLimit, safeLimit * 4));
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE $search = ''
                   OR toLower(coalesce(e.canonicalName, '')) CONTAINS $search
                   OR toLower(coalesce(e.entityType, '')) CONTAINS $search
                   OR toLower(coalesce(e.fusionKey, '')) CONTAINS $search
                OPTIONAL MATCH (e)-[:HAS_STATE]->(s:EntityState)
                OPTIONAL MATCH (e)-[:MEMBER_OF]->(cluster:EntityCluster)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)-[:SUBJECT_OF]->(outFact:Fact)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)<-[:OBJECT_OF]-(inFact:Fact)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)-[outTransition:EVOLVES_TO]->(:EntityState)<-[:HAS_STATE]-(:Entity)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)<-[inTransition:EVOLVES_TO]-(:EntityState)<-[:HAS_STATE]-(:Entity)
                WITH e, cluster,
                     count(DISTINCT CASE
                         WHEN coalesce(s.stateKind, '') <> 'default_anchor'
                         THEN coalesce(s.stateClusterKey, s.stateKey)
                     END) AS stateCount,
                     count(DISTINCT outFact) + count(DISTINCT inFact) AS factCount,
                     count(DISTINCT outTransition) + count(DISTINCT inTransition) AS transitionCount
                RETURN
                    e.canonicalKey AS id,
                    e.canonicalName AS name,
                    e.entityType AS type,
                    e.fusionKey AS fusionKey,
                    e.fusionStatus AS fusionStatus,
                    cluster.canonicalName AS clusterName,
                    cluster.fusionKey AS clusterKey,
                    stateCount,
                    factCount,
                    transitionCount,
                    e.updatedAt AS updatedAt
                ORDER BY stateCount DESC, factCount DESC, name
                LIMIT $limit
                """, Map.of("search", normalizedSearch, "limit", queryLimit));
        List<Map<String, Object>> entities = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (data.isArray()) {
            for (JsonNode rowNode : data) {
                JsonNode row = rowNode.path("row");
                if (!row.isArray() || row.size() < 11) {
                    continue;
                }
                String name = textAt(row, 1);
                String type = textAt(row, 2);
                if (isHiddenGraphEntity(name, type)) {
                    continue;
                }
                if (isValueLikeGraphEntity(name, type)) {
                    continue;
                }
                Map<String, Object> entity = new LinkedHashMap<>();
                entity.put("id", textAt(row, 0));
                entity.put("label", name);
                entity.put("type", type);
                entity.put("fusionKey", textAt(row, 3));
                entity.put("fusionStatus", textAt(row, 4));
                entity.put("clusterName", textAt(row, 5));
                entity.put("clusterKey", textAt(row, 6));
                entity.put("stateCount", intAt(row, 7));
                entity.put("factCount", intAt(row, 8));
                entity.put("transitionCount", intAt(row, 9));
                entity.put("updatedAt", textAt(row, 10));
                entity.put("hasEntityState", intAt(row, 7) > 0);
                entity.put("hasMultipleStates", intAt(row, 7) > 1);
                entity.put("hasEvolution", intAt(row, 9) > 0);
                entities.add(entity);
                if (entities.size() >= safeLimit) {
                    break;
                }
            }
        }
        return Map.of(
                "items", entities,
                "limit", safeLimit,
                "search", normalizedSearch,
                "stats", readGraphStats()
        );
    }

    public Map<String, Object> readEntityDetail(String entityId, int connectionLimit) {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId is required.");
        }
        String canonicalEntityId = resolveEntityId(entityId);
        int safeLimit = Math.max(1, Math.min(connectionLimit, 1000));
        Map<String, Object> params = Map.of("entityId", canonicalEntityId, "limit", safeLimit);
        JsonNode entityRoot = query("""
                MATCH (e:Entity {canonicalKey: $entityId})
                OPTIONAL MATCH (e)-[:MEMBER_OF]->(cluster:EntityCluster)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(s:EntityState)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)-[outTransition:EVOLVES_TO]->(:EntityState)<-[:HAS_STATE]-(:Entity)
                OPTIONAL MATCH (e)-[:HAS_STATE]->(:EntityState)<-[inTransition:EVOLVES_TO]-(:EntityState)<-[:HAS_STATE]-(:Entity)
                RETURN
                    e.canonicalKey AS id,
                    e.canonicalName AS name,
                    e.entityType AS type,
                    e.fusionKey AS fusionKey,
                    e.fusionStatus AS fusionStatus,
                    e.fusedCanonicalKey AS fusedCanonicalKey,
                    cluster.canonicalName AS clusterName,
                    cluster.fusionKey AS clusterKey,
                    count(DISTINCT CASE
                        WHEN coalesce(s.stateKind, '') <> 'default_anchor'
                        THEN coalesce(s.stateClusterKey, s.stateKey)
                    END) AS stateCount,
                    count(DISTINCT outTransition) + count(DISTINCT inTransition) AS transitionCount,
                    e.updatedAt AS updatedAt
                LIMIT 1
                """, params);
        JsonNode entityRow = entityRoot.path("results").path(0).path("data").path(0).path("row");
        if (!entityRow.isArray() || entityRow.size() < 11) {
            throw new IllegalArgumentException("Entity not found: " + entityId);
        }
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", textAt(entityRow, 0));
        entity.put("label", textAt(entityRow, 1));
        entity.put("type", textAt(entityRow, 2));
        entity.put("fusionKey", textAt(entityRow, 3));
        entity.put("fusionStatus", textAt(entityRow, 4));
        entity.put("fusedCanonicalKey", textAt(entityRow, 5));
        entity.put("clusterName", textAt(entityRow, 6));
        entity.put("clusterKey", textAt(entityRow, 7));
        entity.put("stateCount", intAt(entityRow, 8));
        entity.put("transitionCount", intAt(entityRow, 9));
        entity.put("updatedAt", textAt(entityRow, 10));
        entity.put("hasEvolution", intAt(entityRow, 9) > 0);

        List<Map<String, Object>> stateConnections = readEntityStateConnections(params);
        List<Map<String, Object>> states = readEntityStates(params, stateConnections);
        List<Map<String, Object>> descriptions = readEntityDescriptions(params);
        List<Map<String, Object>> attributes = readEntityAttributeFacts(params);
        attachAttributesToStates(states, attributes);
        List<Map<String, Object>> transitions = readEntityTransitions(params);
        List<Map<String, Object>> connections = readEntityConnections(params);
        Map<String, Object> graph = entityDetailGraph(entity, states, connections);
        return Map.of(
                "entity", entity,
                "states", states,
                "descriptions", descriptions,
                "attributes", attributes,
                "transitions", transitions,
                "connections", connections,
                "graph", graph,
                "connectionLimit", safeLimit
        );
    }

    private String resolveEntityId(String idOrStateKey) {
        JsonNode root = query("""
                OPTIONAL MATCH (e:Entity {canonicalKey: $id})
                OPTIONAL MATCH (stateOwner:Entity)-[:HAS_STATE]->(:EntityState {stateKey: $id})
                RETURN coalesce(e.canonicalKey, stateOwner.canonicalKey) AS entityId
                LIMIT 1
                """, Map.of("id", idOrStateKey));
        String resolved = textAt(root.path("results").path(0).path("data").path(0).path("row"), 0);
        return resolved.isBlank() ? idOrStateKey : resolved;
    }

    private List<Map<String, Object>> readEntityDescriptions(Map<String, Object> params) {
        JsonNode root = query("""
                MATCH (e:Entity {canonicalKey: $entityId})-[:HAS_DESCRIPTION]->(desc:EntityDescription)
                RETURN desc.descriptionKey AS descriptionKey,
                       desc.text AS text,
                       desc.sourceKind AS sourceKind,
                       desc.docId AS docId,
                       desc.chunkId AS chunkId,
                       desc.knowledgeUnitId AS knowledgeUnitId,
                       desc.sourceSpan AS sourceSpan,
                       desc.confidence AS confidence,
                       desc.fusionBatchId AS fusionBatchId,
                       desc.updatedAt AS updatedAt
                ORDER BY updatedAt DESC
                LIMIT $limit
                """, params);
        List<Map<String, Object>> descriptions = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return descriptions;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 10) {
                continue;
            }
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("descriptionKey", textAt(row, 0));
            description.put("text", textAt(row, 1));
            description.put("sourceKind", textAt(row, 2));
            description.put("docId", textAt(row, 3));
            description.put("chunkId", textAt(row, 4));
            description.put("knowledgeUnitId", textAt(row, 5));
            description.put("sourceSpan", textAt(row, 6));
            description.put("confidence", row.path(7).isNumber() ? row.path(7).asDouble() : 0.0d);
            description.put("fusionBatchId", textAt(row, 8));
            description.put("updatedAt", textAt(row, 9));
            descriptions.add(description);
        }
        return descriptions;
    }

    private List<Map<String, Object>> readEntityStates(Map<String, Object> params, List<Map<String, Object>> stateConnections) {
        JsonNode root = query("""
                MATCH (e:Entity {canonicalKey: $entityId})-[:HAS_STATE]->(s:EntityState)
                WHERE coalesce(s.stateKind, '') <> 'default_anchor'
                OPTIONAL MATCH (m:Mention)-[:REFERS_TO]->(s)
                WITH s, count(DISTINCT m) AS mentionCount, collect(DISTINCT m.docId)[0..5] AS docIds
                WITH coalesce(s.stateClusterKey, s.stateKey) AS stateGroupKey,
                     collect({
                         state: s,
                         mentionCount: mentionCount,
                         docIds: docIds
                     }) AS members
                WITH stateGroupKey, members,
                     reduce(best = head(members), candidate IN members |
                         CASE
                             WHEN candidate.mentionCount > best.mentionCount THEN candidate
                             WHEN candidate.mentionCount = best.mentionCount
                                  AND size(coalesce(candidate.state.definition, '')) > size(coalesce(best.state.definition, '')) THEN candidate
                             ELSE best
                         END
                     ) AS representative
                RETURN
                    representative.state.stateKey AS stateKey,
                    representative.state.name AS name,
                    representative.state.entityType AS type,
                    representative.state.definition AS definition,
                    representative.state.validFrom AS validFrom,
                    representative.state.validTo AS validTo,
                    representative.state.status AS status,
                    representative.state.confidence AS confidence,
                    representative.state.fusionBatchId AS fusionBatchId,
                    reduce(total = 0, member IN members | total + member.mentionCount) AS mentionCount,
                    reduce(allDocIds = [], member IN members | allDocIds + member.docIds)[0..5] AS docIds,
                    representative.state.updatedAt AS updatedAt,
                    [member IN members | member.state.stateKey] AS stateKeys,
                    size(members) AS stateMemberCount
                ORDER BY validFrom, updatedAt DESC
                """, params);
        List<Map<String, Object>> states = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return states;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 14) {
                continue;
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("stateKey", textAt(row, 0));
            state.put("name", textAt(row, 1));
            state.put("type", textAt(row, 2));
            state.put("definition", textAt(row, 3));
            state.put("validFrom", textAt(row, 4));
            state.put("validTo", textAt(row, 5));
            state.put("status", textAt(row, 6));
            state.put("confidence", row.path(7).isNumber() ? row.path(7).asDouble() : 0.0d);
            state.put("fusionBatchId", textAt(row, 8));
            state.put("mentionCount", intAt(row, 9));
            state.put("docIds", stringArrayAt(row, 10, 5));
            state.put("updatedAt", textAt(row, 11));
            state.put("stateKeys", stringArrayAt(row, 12, 100));
            state.put("stateMemberCount", intAt(row, 13));
            String stateKey = textAt(row, 0);
            List<String> stateKeys = stringArrayAt(row, 12, 100);
            state.put("connections", stateConnections.stream()
                    .filter(connection -> stateKey.equals(connection.get("stateKey")) || stateKeys.contains(stringValue(connection.get("stateKey"))))
                    .toList());
            states.add(state);
        }
        enrichStateSources(states);
        for (int i = 0; i < states.size(); i++) {
            states.get(i).put("stateOrder", i + 1);
        }
        return states;
    }

    private List<Map<String, Object>> readEntityAttributeFacts(Map<String, Object> params) {
        JsonNode root = query("""
                MATCH (base:Entity {canonicalKey: $entityId})
                OPTIONAL MATCH (base)-[:MEMBER_OF]->(cluster:EntityCluster)
                WITH base, cluster
                CALL {
                    WITH base, cluster
                    MATCH (member:Entity)
                    WHERE member.canonicalKey = base.canonicalKey
                       OR (cluster IS NOT NULL AND (member)-[:MEMBER_OF]->(cluster))
                    RETURN collect(DISTINCT member) AS members
                }
                UNWIND members AS e
                MATCH (e)-[:HAS_STATE]->(s:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.attributeStatus, 'formal') = 'formal'
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                WITH e, s, fact,
                     collect(DISTINCT evidence.docId)[0..5] AS docIds,
                     collect(DISTINCT evidence.chunkId)[0..5] AS chunkIds
                RETURN
                    s.stateKey AS stateKey,
                    fact.factKey AS factKey,
                    coalesce(fact.attributeKey, fact.relationType) AS attributeKey,
                    coalesce(fact.attributeValue, fact.value, fact.objectText, fact.statement) AS attributeValue,
                    fact.statement AS statement,
                    fact.confidence AS confidence,
                    fact.validFrom AS validFrom,
                    fact.validTo AS validTo,
                    docIds,
                    chunkIds,
                    fact.updatedAt AS updatedAt,
                    e.canonicalKey AS ownerEntityId,
                    e.canonicalName AS ownerEntityName,
                    e.entityType AS ownerEntityType
                ORDER BY stateKey, attributeKey, updatedAt DESC
                LIMIT $limit
                """, params);
        List<Map<String, Object>> attributes = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return attributes;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 11) {
                continue;
            }
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("stateKey", textAt(row, 0));
            attribute.put("factKey", textAt(row, 1));
            attribute.put("attributeKey", textAt(row, 2));
            attribute.put("attributeValue", textAt(row, 3));
            attribute.put("statement", textAt(row, 4));
            attribute.put("confidence", row.path(5).isNumber() ? row.path(5).asDouble() : 0.0d);
            attribute.put("validFrom", textAt(row, 6));
            attribute.put("validTo", textAt(row, 7));
            attribute.put("docIds", stringArrayAt(row, 8, 5));
            attribute.put("chunkIds", stringArrayAt(row, 9, 5));
            attribute.put("updatedAt", textAt(row, 10));
            attribute.put("ownerEntityId", textAt(row, 11));
            attribute.put("ownerEntityName", textAt(row, 12));
            attribute.put("ownerEntityType", textAt(row, 13));
            attributes.add(attribute);
        }
        return attributes;
    }

    private void attachAttributesToStates(List<Map<String, Object>> states, List<Map<String, Object>> attributes) {
        for (Map<String, Object> state : states) {
            String stateKey = stringValue(state.get("stateKey"));
            List<String> stateKeys = state.get("stateKeys") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            state.put("attributes", attributes.stream()
                    .filter(attribute -> stateKey.equals(attribute.get("stateKey")) || stateKeys.contains(stringValue(attribute.get("stateKey"))))
                    .toList());
        }
    }

    private List<Map<String, Object>> readEntityTransitions(Map<String, Object> params) {
        JsonNode root = query("""
                MATCH (e:Entity {canonicalKey: $entityId})-[:HAS_STATE]->(ownedState:EntityState)
                MATCH (fromState:EntityState)-[transition:EVOLVES_TO]->(toState:EntityState)
                WHERE fromState = ownedState OR toState = ownedState
                OPTIONAL MATCH (sourceEntity:Entity)-[:HAS_STATE]->(fromState)
                OPTIONAL MATCH (targetEntity:Entity)-[:HAS_STATE]->(toState)
                WITH fromState, toState, sourceEntity, targetEntity, transition,
                     CASE WHEN fromState = ownedState THEN 'outgoing' ELSE 'incoming' END AS direction
                RETURN
                    fromState.stateKey AS fromStateKey,
                    toState.stateKey AS toStateKey,
                    sourceEntity.canonicalKey AS sourceEntityId,
                    sourceEntity.canonicalName AS sourceEntityName,
                    targetEntity.canonicalKey AS targetEntityId,
                    targetEntity.canonicalName AS targetEntityName,
                    transition.transitionType AS transitionType,
                    transition.reason AS reason,
                    transition.inferred AS inferred,
                    transition.confidence AS confidence,
                    transition.fusionBatchId AS fusionBatchId,
                    [] AS docIds,
                    [] AS chunkIds,
                    transition.updatedAt AS updatedAt,
                    direction AS direction
                ORDER BY fromState.validFrom, toState.validFrom, updatedAt DESC
                LIMIT $limit
                """, params);
        List<Map<String, Object>> transitions = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return transitions;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 15) {
                continue;
            }
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("fromStateKey", textAt(row, 0));
            transition.put("toStateKey", textAt(row, 1));
            transition.put("sourceEntityId", textAt(row, 2));
            transition.put("sourceEntityName", textAt(row, 3));
            transition.put("targetEntityId", textAt(row, 4));
            transition.put("targetEntityName", textAt(row, 5));
            transition.put("transitionType", textAt(row, 6));
            transition.put("reason", textAt(row, 7));
            transition.put("inferred", row.path(8).isBoolean() ? row.path(8).asBoolean() : Boolean.parseBoolean(textAt(row, 8)));
            transition.put("confidence", row.path(9).isNumber() ? row.path(9).asDouble() : 0.0d);
            transition.put("fusionBatchId", textAt(row, 10));
            transition.put("docIds", stringArrayAt(row, 11, 5));
            transition.put("chunkIds", stringArrayAt(row, 12, 5));
            transition.put("updatedAt", textAt(row, 13));
            transition.put("direction", textAt(row, 14));
            transitions.add(transition);
        }
        return transitions;
    }

    private void enrichStateSources(List<Map<String, Object>> states) {
        for (Map<String, Object> state : states) {
            String stateKey = stringValue(state.get("stateKey"));
            if (stateKey.isBlank()) {
                state.put("sources", List.of());
                continue;
            }
            JsonNode root = query("""
                    MATCH (s:EntityState {stateKey: $stateKey})<-[:REFERS_TO]-(m:Mention)
                    OPTIONAL MATCH (d:Document {docId: m.docId})
                    RETURN
                        m.docId AS docId,
                        m.chunkId AS chunkId,
                        m.pageNo AS pageNo,
                        m.sourceSpan AS sourceSpan,
                        d.title AS title,
                        d.sourceFilename AS sourceFilename,
                        d.relativePath AS relativePath
                    ORDER BY m.updatedAt DESC
                    LIMIT 8
                    """, Map.of("stateKey", stateKey));
            List<Map<String, Object>> sources = new ArrayList<>();
            JsonNode data = root.path("results").path(0).path("data");
            if (data.isArray()) {
                for (JsonNode rowNode : data) {
                    JsonNode row = rowNode.path("row");
                    if (!row.isArray() || row.size() < 7) {
                        continue;
                    }
                    sources.add(Map.of(
                            "docId", textAt(row, 0),
                            "chunkId", textAt(row, 1),
                            "pageNo", textAt(row, 2),
                            "sourceSpan", textAt(row, 3),
                            "title", textAt(row, 4),
                            "sourceFilename", textAt(row, 5),
                            "relativePath", textAt(row, 6)
                    ));
                }
            }
            state.put("sources", sources);
        }
    }

    private List<Map<String, Object>> readEntityStateConnections(Map<String, Object> params) {
        JsonNode root = query("""
                MATCH (e:Entity {canonicalKey: $entityId})-[:HAS_STATE]->(s:EntityState)
                CALL {
                    WITH s
                    MATCH (s)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(otherState:EntityState)<-[:HAS_STATE]-(other:Entity)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN fact, otherState, other, 'out' AS direction
                    UNION
                    WITH s
                    MATCH (s)<-[:OBJECT_OF]-(fact:Fact)<-[:SUBJECT_OF]-(otherState:EntityState)<-[:HAS_STATE]-(other:Entity)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN fact, otherState, other, 'in' AS direction
                }
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                OPTIONAL MATCH (other)-[:MEMBER_OF]->(cluster:EntityCluster)
                WITH s, fact, otherState, other, direction, cluster,
                     collect(DISTINCT evidence.docId)[0..5] AS docIds,
                     collect(DISTINCT evidence.chunkId)[0..5] AS chunkIds
                RETURN
                    s.stateKey AS stateKey,
                    fact.factKey AS factKey,
                    direction,
                    coalesce(fact.factKind, 'relation_fact') AS factKind,
                    fact.relationType AS relationType,
                    fact.statement AS statement,
                    fact.confidence AS confidence,
                    fact.validFrom AS validFrom,
                    fact.validTo AS validTo,
                    otherState.stateKey AS otherStateKey,
                    otherState.definition AS otherStateDefinition,
                    other.canonicalKey AS otherId,
                    coalesce(cluster.canonicalName, other.canonicalName) AS otherName,
                    other.entityType AS otherType,
                    docIds,
                    chunkIds,
                    fact.updatedAt AS updatedAt
                ORDER BY s.validFrom, updatedAt DESC
                LIMIT $limit
                """, params);
        List<Map<String, Object>> connections = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return connections;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 17) {
                continue;
            }
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("stateKey", textAt(row, 0));
            connection.put("factKey", textAt(row, 1));
            connection.put("direction", textAt(row, 2));
            connection.put("factKind", textAt(row, 3));
            connection.put("relationType", textAt(row, 4));
            connection.put("statement", textAt(row, 5));
            connection.put("confidence", row.path(6).isNumber() ? row.path(6).asDouble() : 0.0d);
            connection.put("validFrom", textAt(row, 7));
            connection.put("validTo", textAt(row, 8));
            connection.put("otherStateKey", textAt(row, 9));
            connection.put("otherStateDefinition", textAt(row, 10));
            connection.put("otherId", textAt(row, 11));
            connection.put("otherName", textAt(row, 12));
            connection.put("otherType", textAt(row, 13));
            if (isHiddenGraphEntity(stringValue(connection.get("otherName")), stringValue(connection.get("otherType")))) {
                continue;
            }
            connection.put("docIds", stringArrayAt(row, 14, 5));
            connection.put("chunkIds", stringArrayAt(row, 15, 5));
            connection.put("updatedAt", textAt(row, 16));
            connections.add(connection);
        }
        return connections;
    }

    private List<Map<String, Object>> readEntityConnections(Map<String, Object> params) {
        JsonNode root = query("""
                MATCH (e:Entity {canonicalKey: $entityId})
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(ownerState:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(otherState:EntityState)<-[:HAS_STATE]-(other:Entity)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN fact, other, ownerState, otherState, 'out' AS direction
                    UNION
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(ownerState:EntityState)<-[:OBJECT_OF]-(fact:Fact)<-[:SUBJECT_OF]-(otherState:EntityState)<-[:HAS_STATE]-(other:Entity)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                    RETURN fact, other, ownerState, otherState, 'in' AS direction
                }
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                OPTIONAL MATCH (other)-[:MEMBER_OF]->(cluster:EntityCluster)
                WITH fact, other, ownerState, otherState, direction, cluster, collect(DISTINCT evidence.docId)[0..5] AS docIds, collect(DISTINCT evidence.chunkId)[0..5] AS chunkIds
                RETURN
                    fact.factKey AS factKey,
                    direction,
                    ownerState.stateKey AS ownerStateKey,
                    ownerState.stateKind AS ownerStateKind,
                    otherState.stateKey AS otherStateKey,
                    otherState.stateKind AS otherStateKind,
                    coalesce(fact.factKind, 'relation_fact') AS factKind,
                    fact.relationType AS relationType,
                    fact.statement AS statement,
                    fact.confidence AS confidence,
                    fact.validFrom AS validFrom,
                    fact.validTo AS validTo,
                    other.canonicalKey AS otherId,
                    coalesce(cluster.canonicalName, other.canonicalName) AS otherName,
                    other.entityType AS otherType,
                    docIds,
                    chunkIds,
                    fact.updatedAt AS updatedAt
                ORDER BY updatedAt DESC
                LIMIT $limit
                """, params);
        List<Map<String, Object>> connections = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return connections;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 18) {
                continue;
            }
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("factKey", textAt(row, 0));
            connection.put("direction", textAt(row, 1));
            connection.put("ownerStateKey", textAt(row, 2));
            connection.put("ownerStateKind", textAt(row, 3));
            connection.put("otherStateKey", textAt(row, 4));
            connection.put("otherStateKind", textAt(row, 5));
            connection.put("factKind", textAt(row, 6));
            connection.put("relationType", textAt(row, 7));
            connection.put("statement", textAt(row, 8));
            connection.put("confidence", row.path(9).isNumber() ? row.path(9).asDouble() : 0.0d);
            connection.put("validFrom", textAt(row, 10));
            connection.put("validTo", textAt(row, 11));
            connection.put("otherId", textAt(row, 12));
            connection.put("otherName", textAt(row, 13));
            connection.put("otherType", textAt(row, 14));
            if (isHiddenGraphEntity(stringValue(connection.get("otherName")), stringValue(connection.get("otherType")))) {
                continue;
            }
            connection.put("docIds", stringArrayAt(row, 15, 5));
            connection.put("chunkIds", stringArrayAt(row, 16, 5));
            connection.put("updatedAt", textAt(row, 17));
            connections.add(connection);
        }
        return connections;
    }

    private Map<String, Object> entityDetailGraph(
            Map<String, Object> entity,
            List<Map<String, Object>> states,
            List<Map<String, Object>> connections
    ) {
        String entityId = stringValue(entity.get("id"));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        nodes.put(entityId, graphNode(
                entityId,
                stringValue(entity.get("label")),
                stringValue(entity.get("type")),
                stringValue(entity.get("clusterName")),
                stringValue(entity.get("fusionKey")),
                intObject(entity.get("stateCount")),
                intObject(entity.get("transitionCount"))
        ));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> state : states) {
            String stateKey = stringValue(state.get("stateKey"));
            if (stateKey.isBlank()) {
                continue;
            }
            Map<String, Object> stateNode = new LinkedHashMap<>();
            String period = formatPeriod(stringValue(state.get("validFrom")), stringValue(state.get("validTo")));
            stateNode.put("id", stateKey);
            stateNode.put("label", period.isBlank() ? "状态" : period);
            stateNode.put("type", "EntityState");
            stateNode.put("canonicalName", stringValue(state.get("definition")));
            stateNode.put("fusionKey", "");
            stateNode.put("stateCount", 0);
            stateNode.put("isStateNode", true);
            stateNode.put("definition", stringValue(state.get("definition")));
            stateNode.put("validFrom", stringValue(state.get("validFrom")));
            stateNode.put("validTo", stringValue(state.get("validTo")));
            stateNode.put("stateOrder", state.getOrDefault("stateOrder", 0));
            stateNode.put("sources", state.getOrDefault("sources", List.of()));
            nodes.put(stateKey, stateNode);
            edges.add(Map.of(
                    "id", entityId + ":state:" + stateKey,
                    "source", entityId,
                    "target", stateKey,
                    "type", "HAS_STATE",
                    "label", blankTo(stringValue(state.get("definition")), period),
                    "confidence", state.getOrDefault("confidence", 0.0d),
                    "docIds", state.getOrDefault("docIds", List.of()),
                    "isStateEdge", true
            ));
            for (Map<String, Object> connection : toMapList(state.get("connections"))) {
                String otherId = stringValue(connection.get("otherId"));
                if (otherId.isBlank()) {
                    continue;
                }
                nodes.putIfAbsent(otherId, graphNode(
                        otherId,
                        stringValue(connection.get("otherName")),
                        stringValue(connection.get("otherType")),
                        "",
                        "",
                        0
                ));
                boolean outgoing = "out".equals(connection.get("direction"));
                edges.add(Map.of(
                        "id", blankTo(stringValue(connection.get("factKey")), stateKey + ":" + otherId + ":" + edges.size()),
                        "source", outgoing ? stateKey : otherId,
                        "target", outgoing ? otherId : stateKey,
                        "type", blankTo(stringValue(connection.get("relationType")), "related_to"),
                        "label", blankTo(stringValue(connection.get("statement")), stringValue(connection.get("relationType"))),
                        "confidence", connection.getOrDefault("confidence", 0.0d),
                        "docIds", connection.getOrDefault("docIds", List.of()),
                        "stateKey", stateKey,
                        "isStateFact", true
                ));
            }
        }
        for (Map<String, Object> connection : connections) {
            String otherId = stringValue(connection.get("otherId"));
            if (otherId.isBlank()) {
                continue;
            }
            nodes.putIfAbsent(otherId, graphNode(
                    otherId,
                    stringValue(connection.get("otherName")),
                    stringValue(connection.get("otherType")),
                    "",
                    "",
                    0
            ));
            boolean outgoing = "out".equals(connection.get("direction"));
            String edgeId = blankTo(stringValue(connection.get("factKey")), entityId + ":" + otherId + ":" + edges.size());
            boolean alreadyStateFact = edges.stream().anyMatch(edge -> edgeId.equals(edge.get("id")));
            if (alreadyStateFact) {
                continue;
            }
            edges.add(Map.of(
                    "id", edgeId,
                    "source", outgoing ? entityId : otherId,
                    "target", outgoing ? otherId : entityId,
                    "type", blankTo(stringValue(connection.get("relationType")), "related_to"),
                    "label", blankTo(stringValue(connection.get("statement")), stringValue(connection.get("relationType"))),
                    "confidence", connection.getOrDefault("confidence", 0.0d),
                    "docIds", connection.getOrDefault("docIds", List.of())
            ));
        }
        return Map.of(
                "nodes", new ArrayList<>(nodes.values()),
                "edges", edges,
                "stats", Map.of(
                        "entities", nodes.size(),
                        "facts", edges.size(),
                        "states", intObject(entity.get("stateCount")),
                        "clusters", 0
                )
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object value) {
        return value instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();
    }

    private String formatPeriod(String validFrom, String validTo) {
        if ((validFrom == null || validFrom.isBlank()) && (validTo == null || validTo.isBlank())) {
            return "";
        }
        return blankTo(validFrom, "?") + " 至 " + blankTo(validTo, "今");
    }

    public List<Map<String, Object>> searchFacts(String queryText, int limit) {
        if (!isConfigured()) {
            return List.of();
        }
        List<String> tokens = graphSearchTokens(queryText);
        if (tokens.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        JsonNode root = query("""
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                OPTIONAL MATCH (subjectEntity)-[:MEMBER_OF]->(subjectCluster:EntityCluster)
                OPTIONAL MATCH (objectEntity)-[:MEMBER_OF]->(objectCluster:EntityCluster)
                WITH subjectEntity, subject, fact, object, objectEntity, subjectCluster, objectCluster, collect(DISTINCT evidence) AS evidences,
                     toLower(
                         coalesce(subjectEntity.canonicalName, '') + ' ' +
                         coalesce(subject.name, '') + ' ' +
                         coalesce(subject.definition, '') + ' ' +
                         coalesce(subjectCluster.canonicalName, '') + ' ' +
                         coalesce(objectEntity.canonicalName, '') + ' ' +
                         coalesce(object.name, '') + ' ' +
                         coalesce(object.definition, '') + ' ' +
                         coalesce(objectCluster.canonicalName, '') + ' ' +
                         coalesce(fact.relationType, '') + ' ' +
                         coalesce(fact.statement, '')
                     ) AS haystack
                WHERE any(token IN $tokens WHERE haystack CONTAINS token)
                WITH subjectEntity, subject, fact, object, objectEntity, subjectCluster, objectCluster,
                     [ev IN evidences WHERE ev IS NOT NULL][0] AS evidence,
                     reduce(score = 0, token IN $tokens | score + CASE WHEN haystack CONTAINS token THEN 1 ELSE 0 END) AS tokenScore
                RETURN
                    fact.factKey AS factKey,
                    coalesce(subjectCluster.canonicalName, subjectEntity.canonicalName, subject.name) AS subjectName,
                    coalesce(subjectEntity.entityType, subject.entityType) AS subjectType,
                    coalesce(objectCluster.canonicalName, objectEntity.canonicalName, object.name) AS objectName,
                    coalesce(objectEntity.entityType, object.entityType) AS objectType,
                    fact.relationType AS relationType,
                    fact.statement AS statement,
                    fact.confidence AS confidence,
                    fact.validFrom AS validFrom,
                    fact.validTo AS validTo,
                    evidence.docId AS docId,
                    evidence.chunkId AS chunkId,
                    evidence.knowledgeUnitId AS knowledgeUnitId,
                    evidence.sourceSpan AS sourceSpan,
                    evidence.fusionBatchId AS fusionBatchId,
                    tokenScore AS tokenScore,
                    fact.updatedAt AS updatedAt
                ORDER BY tokenScore DESC, updatedAt DESC
                LIMIT $limit
                """, Map.of("tokens", tokens, "limit", safeLimit));
        List<Map<String, Object>> facts = new ArrayList<>();
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return facts;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 17) {
                continue;
            }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("factKey", textAt(row, 0));
            fact.put("subject", textAt(row, 1));
            fact.put("subjectType", textAt(row, 2));
            fact.put("object", textAt(row, 3));
            fact.put("objectType", textAt(row, 4));
            fact.put("relationType", textAt(row, 5));
            fact.put("statement", textAt(row, 6));
            fact.put("confidence", row.path(7).isNumber() ? row.path(7).asDouble() : 0.0d);
            fact.put("validFrom", textAt(row, 8));
            fact.put("validTo", textAt(row, 9));
            fact.put("docId", textAt(row, 10));
            fact.put("chunkId", textAt(row, 11));
            fact.put("knowledgeUnitId", textAt(row, 12));
            fact.put("sourceSpan", textAt(row, 13));
            fact.put("fusionBatchId", textAt(row, 14));
            fact.put("tokenScore", intAt(row, 15));
            fact.put("updatedAt", textAt(row, 16));
            facts.add(fact);
        }
        return facts;
    }

    public Map<String, Object> writeLocalGraph(Map<String, Object> localGraph) {
        if (!isConfigured()) {
            throw new IllegalStateException("Knowledge graph store is disabled or not configured.");
        }
        ensureSchema();
        String docId = stringValue(localGraph.get("docId"));
        String sourceFileId = stringValue(localGraph.get("sourceFileId"));
        Map<String, Object> document = mapValue(localGraph.get("document"));
        List<Map<String, Object>> entities = listOfMaps(localGraph.get("entities"));
        List<Map<String, Object>> facts = localGraph.containsKey("facts")
                ? listOfMaps(localGraph.get("facts"))
                : legacyRelationsAsFacts(listOfMaps(localGraph.get("relations")));
        entities = entities.stream()
                .filter(entity -> !isSourceArtifactEntity(entity, document))
                .toList();
        String buildRunId = UUID.randomUUID().toString();
        String fusionBatchId = blankTo(stringValue(localGraph.get("fusionBatchId")), buildRunId);

        clearDocumentGraph(docId);

        execute("""
                MERGE (d:Document {docId: $docId})
                SET d.sourceFileId = $sourceFileId,
                    d.title = $title,
                    d.docType = $docType,
                    d.sourceFile = $sourceFile,
                    d.sourceFilename = $sourceFilename,
                    d.relativePath = $relativePath,
                    d.updatedAt = $now
                """,
                Map.of(
                        "docId", docId,
                        "sourceFileId", sourceFileId,
                        "title", stringValue(document.get("title")),
                        "docType", stringValue(document.get("docType")),
                        "sourceFile", stringValue(document.get("sourceFile")),
                        "sourceFilename", stringValue(document.get("sourceFilename")),
                        "relativePath", stringValue(document.get("relativePath")),
                        "now", OffsetDateTime.now().toString()
                ));
        Map<String, String> mentionToStateKey = new LinkedHashMap<>();
        for (Map<String, Object> entity : entities) {
            String mentionId = blankTo(stringValue(entity.get("mentionId")), UUID.randomUUID().toString());
            String name = blankTo(stringValue(entity.get("name")), "未命名实体");
            String type = blankTo(stringValue(entity.get("type")), "Other");
            String canonicalKey = canonicalKey(type, name);
            String fusionKey = fusionKey(type, name);
            String stateKey = stateKey(canonicalKey, entity);
            String definition = stringValue(entity.get("definition"));
            String descriptionKey = descriptionKey(canonicalKey, docId, mentionId, definition);
            String mentionKey = docId + ":" + mentionId;
            mentionToStateKey.put(mentionId, stateKey);
            String chunkId = stringValue(entity.get("chunkId"));
            upsertChunkRef(docId, chunkId, entity);
            execute("""
                    MERGE (e:Entity {canonicalKey: $canonicalKey})
                    ON CREATE SET e.id = $entityId, e.createdAt = $now
                    SET e.canonicalName = $name,
                        e.entityType = $type,
                        e.fusionKey = $fusionKey,
                        e.fusionStatus = 'candidate',
                        e.fusionBatchId = $fusionBatchId,
                        e.updatedAt = $now
                    MERGE (s:EntityState {stateKey: $stateKey})
                    ON CREATE SET s.id = $stateId, s.createdAt = $now
                    SET s.name = $name,
                        s.entityType = $type,
                        s.definition = $stateDefinition,
                        s.validFrom = $validFrom,
                        s.validTo = $validTo,
                        s.stateKind = $stateKind,
                        s.sourceKind = $stateSourceKind,
                        s.status = 'active',
                        s.fusionBatchId = $fusionBatchId,
                        s.confidence = $confidence,
                        s.updatedAt = $now
                    MERGE (e)-[:HAS_STATE]->(s)
                    WITH e, s
                    FOREACH (_ IN CASE WHEN $definition = '' THEN [] ELSE [1] END |
                        MERGE (desc:EntityDescription {descriptionKey: $descriptionKey})
                        ON CREATE SET desc.id = $descriptionId, desc.createdAt = $now
                        SET desc.text = $definition,
                            desc.sourceKind = 'entity_definition',
                            desc.docId = $docId,
                            desc.chunkId = $chunkId,
                            desc.knowledgeUnitId = $knowledgeUnitId,
                            desc.sourceSpan = $sourceSpan,
                            desc.confidence = $confidence,
                            desc.fusionBatchId = $fusionBatchId,
                            desc.updatedAt = $now
                        MERGE (e)-[:HAS_DESCRIPTION]->(desc)
                    )
                    WITH s
                    MERGE (m:Mention {mentionKey: $mentionKey})
                    ON CREATE SET m.id = $mentionUuid, m.createdAt = $now
                    SET m.text = $name,
                        m.docId = $docId,
                        m.chunkId = $chunkId,
                        m.knowledgeUnitId = $knowledgeUnitId,
                        m.sourceSpan = $sourceSpan,
                        m.pageNo = $pageNo,
                        m.confidence = $confidence,
                        m.status = 'active',
                        m.buildRunId = $buildRunId,
                        m.observedAt = $now,
                        m.updatedAt = $now
                    MERGE (m)-[:REFERS_TO]->(s)
                    WITH m, s
                    MERGE (c:ChunkRef {chunkId: $chunkId})
                    MERGE (m)-[:APPEARS_IN]->(c)
                    MERGE (s)-[sectionMention:MENTIONED_IN]->(c)
                    ON CREATE SET sectionMention.createdAt = $now
                    SET sectionMention.docId = $docId,
                        sectionMention.chunkId = $chunkId,
                        sectionMention.sourceSpan = $sourceSpan,
                        sectionMention.confidence = $confidence,
                        sectionMention.relationKind = 'section_mention',
                        sectionMention.updatedAt = $now
                    """,
                    Map.ofEntries(
                            Map.entry("canonicalKey", canonicalKey),
                            Map.entry("fusionKey", fusionKey),
                            Map.entry("stateKey", stateKey),
                            Map.entry("mentionKey", mentionKey),
                            Map.entry("entityId", UUID.randomUUID().toString()),
                            Map.entry("stateId", UUID.randomUUID().toString()),
                            Map.entry("mentionUuid", UUID.randomUUID().toString()),
                            Map.entry("descriptionId", UUID.randomUUID().toString()),
                            Map.entry("name", name),
                            Map.entry("type", type),
                            Map.entry("definition", definition),
                            Map.entry("descriptionKey", descriptionKey),
                            Map.entry("stateDefinition", ""),
                            Map.entry("stateKind", hasTemporalScope(entity) ? "temporal_anchor" : "default_anchor"),
                            Map.entry("stateSourceKind", "system_anchor"),
                            Map.entry("validFrom", stringValue(entity.get("validFrom"))),
                            Map.entry("validTo", stringValue(entity.get("validTo"))),
                            Map.entry("docId", docId),
                            Map.entry("chunkId", blankTo(chunkId, "unknown")),
                            Map.entry("knowledgeUnitId", stringValue(entity.get("knowledgeUnitId"))),
                            Map.entry("sourceSpan", stringValue(entity.get("sourceSpan"))),
                            Map.entry("pageNo", entity.get("pageNo") == null ? "" : String.valueOf(entity.get("pageNo"))),
                            Map.entry("confidence", doubleValue(entity.get("confidence"))),
                            Map.entry("buildRunId", buildRunId),
                            Map.entry("fusionBatchId", fusionBatchId),
                            Map.entry("now", OffsetDateTime.now().toString())
                    ));
        }

        int factCount = 0;
        int relationCount = 0;
        int attributeCount = 0;
        int transitionCount = 0;
        int evidenceCount = 0;
        Set<String> writtenFactKeys = new java.util.HashSet<>();
        for (Map<String, Object> fact : facts) {
            String factKind = normalizeFactKind(stringValue(fact.get("factKind")));
            String relationType = blankTo(blankTo(stringValue(fact.get("predicate")), stringValue(fact.get("relationType"))), "related_to");
            if ("transition_fact".equals(factKind) && !isEvolutionRelation(relationType, stringValue(fact.get("statement")))) {
                factKind = "relation_fact";
            }
            String subjectState = mentionToStateKey.get(stringValue(fact.get("subjectMentionId")));
            String objectState = mentionToStateKey.get(stringValue(fact.get("objectMentionId")));
            if (subjectState == null) {
                continue;
            }
            if (("relation_fact".equals(factKind) || "transition_fact".equals(factKind)) && objectState == null) {
                continue;
            }
            String factKey = factKey(subjectState, objectState, fact);
            String evidenceKey = docId + ":fact:" + evidenceCount;
            String chunkId = blankTo(stringValue(fact.get("chunkId")), "unknown");
            boolean newFactKey = !writtenFactKeys.contains(factKey);
            upsertChunkRef(docId, chunkId, fact);
            if ("attribute_fact".equals(factKind)) {
                writeAttributeFact(subjectState, fact, factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, relationType);
                if (newFactKey) {
                    attributeCount++;
                }
            } else if (objectState == null) {
                writeScalarFact(subjectState, fact, factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, relationType, factKind);
            } else {
                writeEntityFact(subjectState, objectState, fact, factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, relationType, factKind);
                if ("transition_fact".equals(factKind)) {
                    if (newFactKey) {
                        transitionCount++;
                    }
                    writeTransitionEdge(subjectState, objectState, fact, factKey, fusionBatchId, relationType, false);
                } else {
                    if (newFactKey && "relation_fact".equals(factKind)) {
                        relationCount++;
                    }
                }
            }
            if (writtenFactKeys.add(factKey)) {
                factCount++;
            }
            evidenceCount++;
        }

        execute("""
                MATCH (m:Mention {docId: $docId})
                WHERE m.buildRunId IS NULL OR m.buildRunId <> $buildRunId
                SET m.status = 'superseded', m.supersededAt = $now
                """,
                Map.of("docId", docId, "buildRunId", buildRunId, "now", OffsetDateTime.now().toString()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("graphStore", provider());
        summary.put("docId", docId);
        summary.put("fusionBatchId", fusionBatchId);
        summary.put("entityFusionStatus", "pending");
        summary.put("entityMentions", entities.size());
        Map<String, Object> metadata = mapValue(localGraph.get("metadata"));
        summary.put("facts", factCount);
        summary.put("relations", relationCount);
        summary.put("attributeFacts", attributeCount);
        summary.put("transitionFacts", transitionCount);
        summary.put("extractionProfile", metadata.getOrDefault("extractionProfile", "default"));
        summary.put("chunkCount", metadata.getOrDefault("chunkCount", 0));
        summary.put("extractedChunkCount", metadata.getOrDefault("extractedChunkCount", 0));
        summary.put("batchCount", metadata.getOrDefault("batchCount", 0));
        summary.put("failedBatchCount", metadata.getOrDefault("failedBatchCount", 0));
        summary.put("chunkBatchSize", metadata.getOrDefault("chunkBatchSize", 0));
        summary.put("selectedChunkCount", metadata.getOrDefault("selectedChunkCount", 0));
        summary.put("skippedChunkCount", metadata.getOrDefault("skippedChunkCount", 0));
        summary.put("structuralEntities", metadata.getOrDefault("structuralEntities", 0));
        summary.put("structuralFacts", metadata.getOrDefault("structuralFacts", 0));
        summary.put("extractionDepth", metadata.getOrDefault("extractionDepth", "skeleton"));
        summary.put("scopeType", metadata.getOrDefault("scopeType", "document"));
        summary.put("scopeKey", metadata.getOrDefault("scopeKey", ""));
        return summary;
    }

    private void writeEntityFact(
            String subjectState,
            String objectState,
            Map<String, Object> fact,
            String factKey,
            String evidenceKey,
            String docId,
            String chunkId,
            String buildRunId,
            String fusionBatchId,
            String relationType,
            String factKind
    ) {
        execute("""
                MATCH (subject:EntityState {stateKey: $subjectState})
                MATCH (object:EntityState {stateKey: $objectState})
                MERGE (f:Fact {factKey: $factKey})
                ON CREATE SET f.id = $factId, f.createdAt = $now
                SET f.factKind = $factKind,
                    f.relationType = $relationType,
                    f.predicate = $relationType,
                    f.statement = $statement,
                    f.objectText = $objectText,
                    f.objectType = $objectType,
                    f.value = $value,
                    f.validFrom = $validFrom,
                    f.validTo = $validTo,
                    f.status = 'active',
                    f.fusionStatus = 'merged',
                    f.confidence = $confidence,
                    f.fusionBatchId = $fusionBatchId,
                    f.updatedAt = $now
                MERGE (subject)-[:SUBJECT_OF]->(f)
                MERGE (f)-[:OBJECT_OF]->(object)
                MERGE (ev:Evidence {evidenceKey: $evidenceKey})
                ON CREATE SET ev.id = $evidenceId, ev.createdAt = $now
                SET ev.docId = $docId,
                    ev.chunkId = $chunkId,
                    ev.knowledgeUnitId = $knowledgeUnitId,
                    ev.sourceSpan = $sourceSpan,
                    ev.statement = $statement,
                    ev.confidence = $confidence,
                    ev.buildRunId = $buildRunId,
                    ev.fusionBatchId = $fusionBatchId,
                    ev.updatedAt = $now
                MERGE (f)-[:SUPPORTED_BY]->(ev)
                WITH ev
                MERGE (c:ChunkRef {chunkId: $chunkId})
                MERGE (ev)-[:APPEARS_IN]->(c)
                """,
                factParams(subjectState, objectState, fact, factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, relationType, factKind));
    }

    private void writeAttributeFact(
            String subjectState,
            Map<String, Object> fact,
            String factKey,
            String evidenceKey,
            String docId,
            String chunkId,
            String buildRunId,
            String fusionBatchId,
            String attributeKey
    ) {
        String attributeValue = firstNonBlank(stringValue(fact.get("value")), stringValue(fact.get("object")), stringValue(fact.get("attributeValue")), stringValue(fact.get("statement")));
        execute("""
                MATCH (subject:EntityState {stateKey: $subjectState})
                MERGE (f:Fact {factKey: $factKey})
                ON CREATE SET f.id = $factId, f.createdAt = $now
                SET f.factKind = 'attribute_fact',
                    f.relationType = $relationType,
                    f.predicate = $relationType,
                    f.attributeKey = $relationType,
                    f.attributeValue = $attributeValue,
                    f.objectText = $objectText,
                    f.objectType = $objectType,
                    f.value = $attributeValue,
                    f.statement = $statement,
                    f.validFrom = $validFrom,
                    f.validTo = $validTo,
                    f.status = 'active',
                    f.fusionStatus = 'merged',
                    f.confidence = $confidence,
                    f.fusionBatchId = $fusionBatchId,
                    f.updatedAt = $now
                MERGE (subject)-[:HAS_ATTRIBUTE_FACT]->(f)
                MERGE (ev:Evidence {evidenceKey: $evidenceKey})
                ON CREATE SET ev.id = $evidenceId, ev.createdAt = $now
                SET ev.docId = $docId,
                    ev.chunkId = $chunkId,
                    ev.knowledgeUnitId = $knowledgeUnitId,
                    ev.sourceSpan = $sourceSpan,
                    ev.statement = $statement,
                    ev.confidence = $confidence,
                    ev.buildRunId = $buildRunId,
                    ev.fusionBatchId = $fusionBatchId,
                    ev.updatedAt = $now
                MERGE (f)-[:SUPPORTED_BY]->(ev)
                WITH ev
                MERGE (c:ChunkRef {chunkId: $chunkId})
                MERGE (ev)-[:APPEARS_IN]->(c)
                """,
                factParams(subjectState, "", withValue(fact, attributeValue), factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, attributeKey, "attribute_fact"));
    }

    private void writeScalarFact(
            String subjectState,
            Map<String, Object> fact,
            String factKey,
            String evidenceKey,
            String docId,
            String chunkId,
            String buildRunId,
            String fusionBatchId,
            String relationType,
            String factKind
    ) {
        String scalarValue = firstNonBlank(stringValue(fact.get("value")), stringValue(fact.get("object")), stringValue(fact.get("attributeValue")), stringValue(fact.get("statement")));
        execute("""
                MATCH (subject:EntityState {stateKey: $subjectState})
                MERGE (f:Fact {factKey: $factKey})
                ON CREATE SET f.id = $factId, f.createdAt = $now
                SET f.factKind = $factKind,
                    f.relationType = $relationType,
                    f.predicate = $relationType,
                    f.attributeKey = $relationType,
                    f.attributeValue = $attributeValue,
                    f.objectText = $objectText,
                    f.objectType = $objectType,
                    f.value = $attributeValue,
                    f.statement = $statement,
                    f.validFrom = $validFrom,
                    f.validTo = $validTo,
                    f.status = 'active',
                    f.fusionStatus = 'merged',
                    f.confidence = $confidence,
                    f.fusionBatchId = $fusionBatchId,
                    f.updatedAt = $now
                MERGE (subject)-[:HAS_ATTRIBUTE_FACT]->(f)
                MERGE (ev:Evidence {evidenceKey: $evidenceKey})
                ON CREATE SET ev.id = $evidenceId, ev.createdAt = $now
                SET ev.docId = $docId,
                    ev.chunkId = $chunkId,
                    ev.knowledgeUnitId = $knowledgeUnitId,
                    ev.sourceSpan = $sourceSpan,
                    ev.statement = $statement,
                    ev.confidence = $confidence,
                    ev.buildRunId = $buildRunId,
                    ev.fusionBatchId = $fusionBatchId,
                    ev.updatedAt = $now
                MERGE (f)-[:SUPPORTED_BY]->(ev)
                WITH ev
                MERGE (c:ChunkRef {chunkId: $chunkId})
                MERGE (ev)-[:APPEARS_IN]->(c)
                """,
                factParams(subjectState, "", withValue(fact, scalarValue), factKey, evidenceKey, docId, chunkId, buildRunId, fusionBatchId, relationType, factKind));
    }

    private void writeTransitionEdge(String subjectState, String objectState, Map<String, Object> fact, String factKey, String fusionBatchId, String transitionType, boolean inferred) {
        if (subjectState == null || subjectState.isBlank() || objectState == null || objectState.isBlank() || subjectState.equals(objectState)) {
            return;
        }
        if (!inferred && !isEvolutionRelation(transitionType, stringValue(fact.get("statement")))) {
            return;
        }
        execute("""
                MATCH (subject:EntityState {stateKey: $subjectState})
                MATCH (object:EntityState {stateKey: $objectState})
                MERGE (subject)-[transition:EVOLVES_TO]->(object)
                ON CREATE SET transition.id = randomUUID(), transition.createdAt = $now
                SET transition.transitionType = $transitionType,
                    transition.reason = $reason,
                    transition.factKey = $factKey,
                    transition.inferred = $inferred,
                    transition.confidence = $confidence,
                    transition.fusionBatchId = $fusionBatchId,
                    transition.updatedAt = $now
                """,
                Map.ofEntries(
                        Map.entry("subjectState", subjectState),
                        Map.entry("objectState", objectState),
                        Map.entry("transitionType", blankTo(transitionType, "evolves_to")),
                        Map.entry("reason", blankTo(stringValue(fact.get("statement")), "状态演化")),
                        Map.entry("factKey", factKey),
                        Map.entry("inferred", inferred),
                        Map.entry("confidence", doubleValue(fact.get("confidence")) > 0 ? doubleValue(fact.get("confidence")) : (inferred ? 0.55d : 0.78d)),
                        Map.entry("fusionBatchId", blankTo(fusionBatchId, "")),
                        Map.entry("now", OffsetDateTime.now().toString())
                ));
    }

    private Map<String, Object> factParams(
            String subjectState,
            String objectState,
            Map<String, Object> fact,
            String factKey,
            String evidenceKey,
            String docId,
            String chunkId,
            String buildRunId,
            String fusionBatchId,
            String relationType,
            String factKind
    ) {
        return Map.ofEntries(
                Map.entry("subjectState", subjectState),
                Map.entry("objectState", blankTo(objectState, "")),
                Map.entry("factKey", factKey),
                Map.entry("factId", UUID.randomUUID().toString()),
                Map.entry("factKind", factKind),
                Map.entry("relationType", blankTo(relationType, "related_to")),
                Map.entry("statement", stringValue(fact.get("statement"))),
                Map.entry("objectText", stringValue(fact.get("object"))),
                Map.entry("objectType", stringValue(fact.get("objectType"))),
                Map.entry("value", firstNonBlank(stringValue(fact.get("value")), stringValue(fact.get("object")), stringValue(fact.get("attributeValue")))),
                Map.entry("attributeValue", firstNonBlank(stringValue(fact.get("value")), stringValue(fact.get("object")), stringValue(fact.get("attributeValue")), stringValue(fact.get("statement")))),
                Map.entry("validFrom", stringValue(fact.get("validFrom"))),
                Map.entry("validTo", stringValue(fact.get("validTo"))),
                Map.entry("confidence", doubleValue(fact.get("confidence"))),
                Map.entry("evidenceKey", evidenceKey),
                Map.entry("evidenceId", UUID.randomUUID().toString()),
                Map.entry("docId", docId),
                Map.entry("chunkId", chunkId),
                Map.entry("knowledgeUnitId", stringValue(fact.get("knowledgeUnitId"))),
                Map.entry("sourceSpan", stringValue(fact.get("sourceSpan"))),
                Map.entry("buildRunId", buildRunId),
                Map.entry("fusionBatchId", fusionBatchId),
                Map.entry("now", OffsetDateTime.now().toString())
        );
    }

    private Map<String, Object> withValue(Map<String, Object> fact, String value) {
        Map<String, Object> copy = new LinkedHashMap<>(fact);
        copy.put("value", value);
        return copy;
    }

    private List<Map<String, Object>> legacyRelationsAsFacts(List<Map<String, Object>> relations) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<String, Object> relation : relations) {
            Map<String, Object> fact = new LinkedHashMap<>(relation);
            fact.put("factKind", "relation_fact");
            fact.put("predicate", blankTo(stringValue(relation.get("predicate")), stringValue(relation.get("relationType"))));
            facts.add(fact);
        }
        return facts;
    }

    private void clearDocumentGraph(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        Map<String, Object> params = Map.of("docId", docId, "now", OffsetDateTime.now().toString());
        execute("""
                MATCH (d:Document {docId: $docId})-[:HAS_CHUNK]->(c:ChunkRef)<-[:APPEARS_IN]-(m:Mention)
                DETACH DELETE m
                """, params);
        execute("""
                MATCH (d:Document {docId: $docId})-[:HAS_CHUNK]->(c:ChunkRef)<-[:APPEARS_IN]-(ev:Evidence)
                DETACH DELETE ev
                """, params);
        execute("""
                MATCH (m:Mention {docId: $docId})
                DETACH DELETE m
                """, params);
        execute("""
                MATCH (ev:Evidence {docId: $docId})
                DETACH DELETE ev
                """, params);
        execute("""
                MATCH (desc:EntityDescription {docId: $docId})
                DETACH DELETE desc
                """, params);
        execute("""
                MATCH (d:Document {docId: $docId})-[r:HAS_CHUNK]->(:ChunkRef)
                DELETE r
                """, params);
        execute("""
                MATCH (f:Fact)
                WHERE NOT (f)-[:SUPPORTED_BY]->(:Evidence)
                DETACH DELETE f
                """, params);
        execute("""
                MATCH (s:EntityState)
                WHERE NOT (:Mention)-[:REFERS_TO]->(s)
                  AND NOT (s)-[:SUBJECT_OF]->(:Fact)
                  AND NOT (:Fact)-[:OBJECT_OF]->(s)
                  AND NOT (s)-[:HAS_ATTRIBUTE_FACT]->(:Fact)
                DETACH DELETE s
                """, params);
        execute("""
                MATCH (e:Entity)
                WHERE NOT (e)-[:HAS_STATE]->(:EntityState)
                DETACH DELETE e
                """, params);
        execute("""
                MATCH (c:ChunkRef {docId: $docId})
                WHERE NOT (:Mention)-[:APPEARS_IN]->(c)
                  AND NOT (:Evidence)-[:APPEARS_IN]->(c)
                DETACH DELETE c
                """, params);
    }

    public Map<String, Object> fuseEntities(String fusionBatchId) {
        ensureSchema();
        AppProperties.EntityFusion fusion = appProperties.knowledgeGraph() == null ? null : appProperties.knowledgeGraph().entityFusion();
        if (fusion != null && !fusion.enabled()) {
            return Map.of(
                    "entityFusionEnabled", false,
                    "entityFusionMode", "disabled",
                    "entityFusionGroups", 0,
                    "entityFusionSameAsEdges", 0
            );
        }
        String mode = fusion == null || fusion.mode() == null || fusion.mode().isBlank()
                ? "deterministic"
                : fusion.mode().trim().toLowerCase(Locale.ROOT);
        int minNameLength = Math.max(1, fusion == null ? 2 : fusion.minNameLength());
        int maxGroupSize = Math.max(2, fusion == null ? 50 : fusion.maxGroupSize());
        boolean createSameAsEdges = fusion == null || fusion.createSameAsEdges();
        boolean batchScoped = fusionBatchId != null && !fusionBatchId.isBlank();
        int backfilled = backfillEntityFusionKeys(batchScoped ? fusionBatchId : null);
        int eligiblePrepared = prepareEntityFusionEligibility(batchScoped, batchScoped ? fusionBatchId : "");
        execute("""
                MATCH (e:Entity)-[member:MEMBER_OF]->(cluster:EntityCluster)
                WHERE $batchScoped = false OR e.fusionBatchId = $fusionBatchId
                DELETE member
                SET e.fusionStatus = 'candidate',
                    e.fusedCanonicalKey = null,
                    e.fusedClusterKey = null,
                    e.fusionUpdatedAt = $now
                """, Map.of(
                "batchScoped", batchScoped,
                "fusionBatchId", batchScoped ? fusionBatchId : "",
                "now", OffsetDateTime.now().toString()
        ));
        execute("""
                MATCH (source:Entity)-[same:SAME_AS]->(target:Entity)
                WHERE $batchScoped = false OR source.fusionBatchId = $fusionBatchId OR target.fusionBatchId = $fusionBatchId
                DELETE same
                """, Map.of(
                "batchScoped", batchScoped,
                "fusionBatchId", batchScoped ? fusionBatchId : ""
        ));
        execute("""
                MATCH (cluster:EntityCluster)
                WHERE NOT (:Entity)-[:MEMBER_OF]->(cluster)
                DETACH DELETE cluster
                """, Map.of());
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE coalesce(e.fusionEligible, false) = true
                  AND e.fusionKey IS NOT NULL
                  AND e.fusionKey <> ''
                  AND size(e.fusionKey) >= $minNameLength
                  AND NOT e.fusionKey =~ '^[0-9]+$'
                  AND NOT e.fusionKey =~ '^[0-9]+[年月日时分秒]+$'
                  AND NOT e.fusionKey IN $excludedFusionKeys
                  AND NOT coalesce(e.entityType, '') IN $excludedFusionTypes
                WITH e.fusionKey AS fusionKey,
                     collect(e) AS entities
                WHERE size(entities) > 1
                  AND size(entities) <= $maxGroupSize
                  AND none(entity IN entities WHERE coalesce(entity.entityType, '') IN $excludedFusionTypes)
                  AND any(entity IN entities WHERE coalesce(entity.fusionStatus, 'candidate') <> 'fused')
                  AND ($batchScoped = false OR any(entity IN entities WHERE entity.fusionBatchId = $fusionBatchId AND coalesce(entity.fusionStatus, 'candidate') <> 'fused'))
                WITH fusionKey, entities,
                     reduce(best = head(entities), candidate IN entities |
                         CASE
                             WHEN coalesce(candidate.fusionRelationCount, 0) > coalesce(best.fusionRelationCount, 0) THEN candidate
                             WHEN coalesce(candidate.fusionRelationCount, 0) = coalesce(best.fusionRelationCount, 0)
                              AND coalesce(candidate.fusionMentionCount, 0) > coalesce(best.fusionMentionCount, 0) THEN candidate
                             WHEN coalesce(candidate.fusionRelationCount, 0) = coalesce(best.fusionRelationCount, 0)
                              AND coalesce(candidate.fusionMentionCount, 0) = coalesce(best.fusionMentionCount, 0)
                              AND size(coalesce(candidate.canonicalName, '')) > size(coalesce(best.canonicalName, '')) THEN candidate
                             ELSE best
                         END
                     ) AS canonical
                MERGE (cluster:EntityCluster {fusionKey: 'exact:' + fusionKey})
                ON CREATE SET cluster.id = randomUUID(), cluster.createdAt = $now
                SET cluster.entityType = coalesce(canonical.entityType, ''),
                    cluster.canonicalName = canonical.canonicalName,
                    cluster.canonicalKey = canonical.canonicalKey,
                    cluster.rawFusionKey = fusionKey,
                    cluster.fusionTypeGroup = 'exact_name',
                    cluster.memberCount = size(entities),
                    cluster.fusionMode = $mode,
                    cluster.updatedAt = $now
                WITH cluster, canonical, entities
                FOREACH (entity IN entities |
                    SET entity.fusionStatus = 'fused',
                        entity.fusedCanonicalKey = canonical.canonicalKey,
                        entity.fusedClusterKey = cluster.fusionKey,
                        entity.fusionUpdatedAt = $now
                )
                WITH cluster, canonical, entities
                UNWIND entities AS entity
                MERGE (entity)-[member:MEMBER_OF]->(cluster)
                ON CREATE SET member.createdAt = $now
                SET member.role = CASE WHEN entity.canonicalKey = canonical.canonicalKey THEN 'canonical' ELSE 'alias' END,
                    member.method = $mode,
                    member.updatedAt = $now
                WITH cluster, canonical, collect(entity) AS entities
                UNWIND entities AS entity
                WITH cluster, canonical, entity
                WHERE $createSameAsEdges = true AND entity.canonicalKey <> canonical.canonicalKey
                MERGE (entity)-[same:SAME_AS]->(canonical)
                ON CREATE SET same.createdAt = $now
                SET same.method = $mode,
                    same.confidence = 0.85,
                                same.reason = 'exact normalized name fusion',
                    same.updatedAt = $now
                RETURN count(DISTINCT cluster) AS groups, count(same) AS sameAsEdges
                """,
                Map.of(
                        "minNameLength", minNameLength,
                        "maxGroupSize", maxGroupSize,
                        "createSameAsEdges", createSameAsEdges,
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "excludedFusionKeys", List.of("其他", "其它", "名称", "时间", "日期", "数量", "金额", "编号", "序号", "状态"),
                        "excludedFusionTypes", List.of("Time", "MetricValue"),
                        "mode", mode,
                        "now", OffsetDateTime.now().toString()
                ));
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        int groups = row.isArray() && row.size() > 0 && row.path(0).isNumber() ? row.path(0).asInt() : 0;
        int sameAsEdges = row.isArray() && row.size() > 1 && row.path(1).isNumber() ? row.path(1).asInt() : 0;
        Map<String, Object> nearNameFusion = fuseNearNameEntities(
                batchScoped,
                batchScoped ? fusionBatchId : "",
                minNameLength,
                maxGroupSize,
                createSameAsEdges
        );
        Map<String, Object> stateFusion = fuseEntityStates(batchScoped ? fusionBatchId : null);
        Map<String, Object> transitions = buildStateTransitions(batchScoped ? fusionBatchId : null);
        return Map.ofEntries(
                Map.entry("entityFusionEnabled", true),
                Map.entry("entityFusionMode", mode),
                Map.entry("fusionBatchId", batchScoped ? fusionBatchId : ""),
                Map.entry("entityFusionBackfilled", backfilled),
                Map.entry("entityFusionEligiblePrepared", eligiblePrepared),
                Map.entry("entityFusionGroups", groups + intObject(nearNameFusion.get("groups"))),
                Map.entry("entityFusionExactGroups", groups),
                Map.entry("entityFusionNearNameGroups", nearNameFusion.getOrDefault("groups", 0)),
                Map.entry("entityFusionSameAsEdges", sameAsEdges + intObject(nearNameFusion.get("sameAsEdges"))),
                Map.entry("entityFusionExactSameAsEdges", sameAsEdges),
                Map.entry("entityFusionNearNameSameAsEdges", nearNameFusion.getOrDefault("sameAsEdges", 0)),
                Map.entry("stateFusionGroups", stateFusion.getOrDefault("stateFusionGroups", 0)),
                Map.entry("stateFusionCandidates", stateFusion.getOrDefault("stateFusionCandidates", 0)),
                Map.entry("transitionEdges", transitions.getOrDefault("transitionEdges", 0)),
                Map.entry("explicitTransitionEdges", transitions.getOrDefault("explicitTransitionEdges", 0)),
                Map.entry("inferredTransitionEdges", transitions.getOrDefault("inferredTransitionEdges", 0))
        );
    }

    private int prepareEntityFusionEligibility(boolean batchScoped, String fusionBatchId) {
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE $batchScoped = false OR e.fusionBatchId = $fusionBatchId
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(:EntityState)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                      AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                      AND coalesce(fact.relationType, fact.predicate, '') <> '章节涉及'
                    RETURN count(DISTINCT fact) AS outgoingRelationCount
                }
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)<-[:OBJECT_OF]-(fact:Fact)<-[:SUBJECT_OF]-(:EntityState)
                    WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                      AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                      AND coalesce(fact.relationType, fact.predicate, '') <> '章节涉及'
                    RETURN count(DISTINCT fact) AS incomingRelationCount
                }
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)<-[:REFERS_TO]-(mention:Mention)
                    RETURN count(DISTINCT mention) AS mentionCount
                }
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_STATE]->(:EntityState)-[:HAS_ATTRIBUTE_FACT]->(attributeFact:Fact)
                    RETURN count(DISTINCT attributeFact) AS attributeCount
                }
                CALL {
                    WITH e
                    MATCH (e)-[:HAS_DESCRIPTION]->(description:EntityDescription)
                    RETURN count(DISTINCT description) AS descriptionCount
                }
                RETURN e.canonicalKey AS canonicalKey,
                       e.canonicalName AS canonicalName,
                       e.entityType AS entityType,
                       e.fusionKey AS fusionKey,
                       outgoingRelationCount,
                       incomingRelationCount,
                       mentionCount,
                       attributeCount,
                       descriptionCount
                LIMIT 20000
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return 0;
        }
        int updated = 0;
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 9) {
                continue;
            }
            String canonicalKey = textAt(row, 0);
            String name = textAt(row, 1);
            String type = textAt(row, 2);
            String key = blankTo(textAt(row, 3), fusionKey(type, name));
            int relationCount = intAt(row, 4) + intAt(row, 5);
            int mentionCount = intAt(row, 6);
            int attributeCount = intAt(row, 7);
            int descriptionCount = intAt(row, 8);
            boolean eligible = isEntityFusionEligible(name, type, key, relationCount, mentionCount, attributeCount, descriptionCount);
            String typeGroup = fusionTypeGroup(type);
            execute("""
                    MATCH (e:Entity {canonicalKey: $canonicalKey})
                    SET e.fusionKey = $fusionKey,
                        e.fusionEligible = $eligible,
                        e.fusionTypeGroup = $fusionTypeGroup,
                        e.fusionRelationCount = $relationCount,
                        e.fusionMentionCount = $mentionCount,
                        e.fusionAttributeCount = $attributeCount,
                        e.fusionDescriptionCount = $descriptionCount,
                        e.fusionEligibilityReason = $reason,
                        e.fusionUpdatedAt = $now
                    """,
                    Map.of(
                            "canonicalKey", canonicalKey,
                            "fusionKey", key,
                            "eligible", eligible,
                            "fusionTypeGroup", typeGroup,
                            "relationCount", relationCount,
                            "mentionCount", mentionCount,
                            "attributeCount", attributeCount,
                            "descriptionCount", descriptionCount,
                            "reason", eligible ? "eligible" : "low_evidence_or_field_like_entity",
                            "now", OffsetDateTime.now().toString()
                    ));
            updated++;
        }
        return updated;
    }

    private Map<String, Object> fuseNearNameEntities(
            boolean batchScoped,
            String fusionBatchId,
            int minNameLength,
            int maxGroupSize,
            boolean createSameAsEdges
    ) {
        List<Map<String, Object>> candidates = readNearNameFusionCandidates(batchScoped, fusionBatchId, minNameLength);
        List<List<Map<String, Object>>> groups = nearNameGroups(candidates, maxGroupSize);
        int sameAsEdges = 0;
        for (List<Map<String, Object>> group : groups) {
            Map<String, Object> canonical = group.stream()
                    .reduce(group.get(0), this::betterFusionCanonical);
            String clusterKey = "near:" + stringValue(canonical.get("typeGroup")) + ":" + stringValue(canonical.get("fusionKey"));
            execute("""
                    MERGE (cluster:EntityCluster {fusionKey: $clusterKey})
                    ON CREATE SET cluster.id = randomUUID(), cluster.createdAt = $now
                    SET cluster.entityType = $entityType,
                        cluster.canonicalName = $canonicalName,
                        cluster.canonicalKey = $canonicalKey,
                        cluster.memberCount = $memberCount,
                        cluster.fusionMode = 'near-name',
                        cluster.updatedAt = $now
                    """,
                    Map.of(
                            "clusterKey", clusterKey,
                            "entityType", stringValue(canonical.get("type")),
                            "canonicalName", stringValue(canonical.get("name")),
                            "canonicalKey", stringValue(canonical.get("canonicalKey")),
                            "memberCount", group.size(),
                            "now", OffsetDateTime.now().toString()
                    ));
            for (Map<String, Object> member : group) {
                boolean isCanonical = Objects.equals(member.get("canonicalKey"), canonical.get("canonicalKey"));
                execute("""
                        MATCH (cluster:EntityCluster {fusionKey: $clusterKey})
                        MATCH (entity:Entity {canonicalKey: $canonicalKey})
                        SET entity.fusionStatus = 'fused',
                            entity.fusedCanonicalKey = $canonicalEntityKey,
                            entity.fusedClusterKey = $clusterKey,
                            entity.fusionUpdatedAt = $now
                        MERGE (entity)-[member:MEMBER_OF]->(cluster)
                        ON CREATE SET member.createdAt = $now
                        SET member.role = $role,
                            member.method = 'near-name',
                            member.updatedAt = $now
                        """,
                        Map.of(
                                "clusterKey", clusterKey,
                                "canonicalKey", stringValue(member.get("canonicalKey")),
                                "canonicalEntityKey", stringValue(canonical.get("canonicalKey")),
                                "role", isCanonical ? "canonical" : "alias",
                                "now", OffsetDateTime.now().toString()
                        ));
                if (createSameAsEdges && !isCanonical) {
                    execute("""
                            MATCH (source:Entity {canonicalKey: $sourceKey})
                            MATCH (target:Entity {canonicalKey: $targetKey})
                            MERGE (source)-[same:SAME_AS]->(target)
                            ON CREATE SET same.createdAt = $now
                            SET same.method = 'near-name',
                                same.confidence = 0.78,
                                same.reason = 'near-name edit distance fusion',
                                same.updatedAt = $now
                            """,
                            Map.of(
                                    "sourceKey", stringValue(member.get("canonicalKey")),
                                    "targetKey", stringValue(canonical.get("canonicalKey")),
                                    "now", OffsetDateTime.now().toString()
                            ));
                    sameAsEdges++;
                }
            }
        }
        return Map.of("groups", groups.size(), "sameAsEdges", sameAsEdges);
    }

    private List<Map<String, Object>> readNearNameFusionCandidates(boolean batchScoped, String fusionBatchId, int minNameLength) {
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE coalesce(e.fusionEligible, false) = true
                  AND coalesce(e.fusionStatus, 'candidate') <> 'fused'
                  AND size(coalesce(e.fusionKey, '')) >= $minNameLength
                  AND ($batchScoped = false OR e.fusionBatchId = $fusionBatchId)
                RETURN e.canonicalKey AS canonicalKey,
                       e.canonicalName AS name,
                       e.entityType AS type,
                       e.fusionKey AS fusionKey,
                       e.fusionTypeGroup AS typeGroup,
                       coalesce(e.fusionRelationCount, 0) AS relationCount,
                       coalesce(e.fusionMentionCount, 0) AS mentionCount
                LIMIT 5000
                """,
                Map.of(
                        "minNameLength", minNameLength,
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 7) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("canonicalKey", textAt(row, 0));
            candidate.put("name", textAt(row, 1));
            candidate.put("type", textAt(row, 2));
            candidate.put("fusionKey", textAt(row, 3));
            candidate.put("typeGroup", textAt(row, 4));
            candidate.put("relationCount", intAt(row, 5));
            candidate.put("mentionCount", intAt(row, 6));
            candidates.add(candidate);
        }
        return candidates;
    }

    private List<List<Map<String, Object>>> nearNameGroups(List<Map<String, Object>> candidates, int maxGroupSize) {
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            Map<String, Object> seed = candidates.get(i);
            List<Map<String, Object>> group = new ArrayList<>();
            group.add(seed);
            for (int j = i + 1; j < candidates.size(); j++) {
                if (used[j] || group.size() >= maxGroupSize) {
                    continue;
                }
                Map<String, Object> candidate = candidates.get(j);
                if (nearNameFusionMatch(seed, candidate)) {
                    group.add(candidate);
                    used[j] = true;
                }
            }
            if (group.size() > 1) {
                used[i] = true;
                groups.add(group);
            }
        }
        return groups;
    }

    private boolean nearNameFusionMatch(Map<String, Object> left, Map<String, Object> right) {
        String leftGroup = stringValue(left.get("typeGroup"));
        String rightGroup = stringValue(right.get("typeGroup"));
        if (leftGroup.isBlank() || !leftGroup.equals(rightGroup)) {
            return false;
        }
        String a = stringValue(left.get("fusionKey"));
        String b = stringValue(right.get("fusionKey"));
        int minLength = Math.min(a.length(), b.length());
        if (minLength < 5 || a.equals(b)) {
            return false;
        }
        int distance = levenshteinDistance(a, b, 2);
        if (distance == 1) {
            return true;
        }
        return distance == 2 && minLength >= 8 && commonPrefixLength(a, b) >= 3 && commonSuffixLength(a, b) >= 2;
    }

    private Map<String, Object> betterFusionCanonical(Map<String, Object> best, Map<String, Object> candidate) {
        int bestRelations = intObject(best.get("relationCount"));
        int candidateRelations = intObject(candidate.get("relationCount"));
        if (candidateRelations != bestRelations) {
            return candidateRelations > bestRelations ? candidate : best;
        }
        int bestMentions = intObject(best.get("mentionCount"));
        int candidateMentions = intObject(candidate.get("mentionCount"));
        if (candidateMentions != bestMentions) {
            return candidateMentions > bestMentions ? candidate : best;
        }
        return stringValue(candidate.get("name")).length() > stringValue(best.get("name")).length() ? candidate : best;
    }

    private boolean isEntityFusionEligible(String name, String type, String fusionKey, int relationCount, int mentionCount, int attributeCount, int descriptionCount) {
        String normalizedType = normalize(type);
        String normalizedName = normalize(fusionKey);
        if (normalizedName.isBlank()
                || normalizedName.length() < 2
                || normalizedName.matches("^[0-9]+$")
                || normalizedName.matches("^[0-9]+[年月日时分秒]+$")
                || Set.of("time", "metricvalue", "document", "entitystate").contains(normalizedType)
                || isValueLikeGraphEntity(name, type)
                || isHiddenGraphEntity(name, type)) {
            return false;
        }
        boolean lowEvidenceAttributeLike = relationCount == 0
                && attributeCount > 0
                && descriptionCount == 0
                && Set.of("other", "concept", "resource").contains(normalizedType);
        boolean localOnlyResource = relationCount == 0
                && mentionCount <= 1
                && "resource".equals(normalizedType);
        boolean lowEvidenceGeneric = relationCount == 0
                && descriptionCount == 0
                && mentionCount <= 1
                && normalizedName.length() <= 8
                && Set.of("other", "concept", "resource").contains(normalizedType);
        return !lowEvidenceAttributeLike && !localOnlyResource && !lowEvidenceGeneric;
    }

    private String fusionTypeGroup(String type) {
        String normalized = normalize(type);
        if (normalized.contains("org") || normalized.contains("organization") || normalized.contains("机构")) {
            return "organization";
        }
        if (normalized.contains("person") || normalized.contains("人物") || normalized.contains("人员")) {
            return "person";
        }
        if (normalized.contains("system") || normalized.contains("project") || normalized.contains("product")
                || normalized.contains("platform") || normalized.contains("系统") || normalized.contains("项目") || normalized.contains("平台")) {
            return "solution";
        }
        if (normalized.contains("policy") || normalized.contains("law") || normalized.contains("standard")
                || normalized.contains("制度") || normalized.contains("规章") || normalized.contains("标准") || normalized.contains("法律")) {
            return "normative";
        }
        if (normalized.contains("technology") || normalized.contains("concept") || normalized.contains("process")
                || normalized.contains("event") || normalized.contains("技术") || normalized.contains("概念") || normalized.contains("过程")) {
            return "knowledge";
        }
        return normalized.isBlank() ? "other" : normalized;
    }

    private int levenshteinDistance(String left, String right, int maxDistance) {
        if (Math.abs(left.length() - right.length()) > maxDistance) {
            return maxDistance + 1;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > maxDistance) {
                return maxDistance + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int count = 0;
        while (count < limit && left.charAt(count) == right.charAt(count)) {
            count++;
        }
        return count;
    }

    private int commonSuffixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int count = 0;
        while (count < limit && left.charAt(left.length() - 1 - count) == right.charAt(right.length() - 1 - count)) {
            count++;
        }
        return count;
    }

    private Map<String, List<String>> stateHintsByType(List<Map<String, Object>> templateHints) {
        Map<String, List<String>> hintsByType = new LinkedHashMap<>();
        if (templateHints == null) {
            return hintsByType;
        }
        for (Map<String, Object> hintRow : templateHints) {
            String entityType = normalize(stringValue(hintRow.get("entityType")));
            String hint = stringValue(hintRow.get("hint"));
            if (entityType.isBlank() || hint.isBlank()) {
                continue;
            }
            hintsByType.computeIfAbsent(entityType, key -> new ArrayList<>()).add(hint);
        }
        return hintsByType;
    }

    private Map<String, Object> derivedStateSignal(
            String entityName,
            String entityType,
            String factKind,
            String attributeStatus,
            String predicate,
            String value,
            String objectName,
            String statement,
            String validFrom,
            String validTo,
            Map<String, List<String>> hintsByType
    ) {
        String normalizedType = normalize(entityType);
        if (!isStatefulEntityType(normalizedType) || isHiddenGraphEntity(entityName, entityType)) {
            return Map.of();
        }
        String normalizedKind = normalizeFactKind(factKind);
        String normalizedStatus = normalize(attributeStatus);
        String normalizedPredicate = normalizeCandidatePredicate(predicate);
        String normalizedValue = normalize(firstNonBlank(value, objectName));
        String normalizedStatement = normalize(statement);
        String combined = normalize(predicate + " " + value + " " + objectName + " " + statement);
        boolean formalFact = "attribute_fact".equals(normalizedKind) || "transition_fact".equals(normalizedKind);
        boolean usableCandidate = "attribute_candidate".equals(normalizedKind)
                && ("formal".equals(normalizedStatus) || "promoted".equals(normalizedStatus));
        boolean relationWithStateSignal = "relation_fact".equals(normalizedKind) && hasTransitionSignal(combined);
        if (!formalFact && !usableCandidate && !relationWithStateSignal) {
            return Map.of();
        }
        String phase = phaseSignature(combined);
        if (!phase.isBlank()) {
            String label = phaseLabel(phase);
            return stateSignal(
                    phase,
                    label,
                    "phase_anchor",
                    label,
                    "",
                    "",
                    firstNonBlank(statement, predicate + "：" + value),
                    0.82d,
                    "phase_signal"
            );
        }
        String timeRange = timeRangeSignature(firstNonBlank(validFrom, ""), firstNonBlank(validTo, ""), firstNonBlank(value, objectName, statement));
        if (!timeRange.isBlank()) {
            String[] parts = timeRange.split("\\|", -1);
            String from = parts.length > 0 ? parts[0] : "";
            String to = parts.length > 1 ? parts[1] : "";
            return stateSignal(
                    "time:" + timeRange,
                    formatPeriod(from, to),
                    "temporal_anchor",
                    "有效期",
                    from,
                    to,
                    firstNonBlank(statement, predicate + "：" + value),
                    0.78d,
                    "time_range_signal"
            );
        }
        String version = versionSignature(normalizedPredicate, firstNonBlank(value, objectName, statement));
        if (!version.isBlank()) {
            return stateSignal(
                    "version:" + version,
                    "版本 " + firstNonBlank(value, objectName, version),
                    "version_anchor",
                    "版本",
                    "",
                    "",
                    firstNonBlank(statement, predicate + "：" + value),
                    0.76d,
                    "version_signal"
            );
        }
        if ((isStatusPredicate(normalizedPredicate) || isTemplateStatusPredicate(normalizedPredicate, normalizedType, hintsByType))
                && !normalizedValue.isBlank()) {
            String label = firstNonBlank(value, objectName);
            if (label.length() > 40 || looksLikeSourceArtifact(label)) {
                return Map.of();
            }
            return stateSignal(
                    "status:" + normalizeCandidatePredicate(predicate) + ":" + Integer.toHexString(normalizedValue.hashCode()),
                    label,
                    "status_anchor",
                    predicate,
                    "",
                    "",
                    firstNonBlank(statement, predicate + "：" + label),
                    0.70d,
                    "template_state_attribute"
            );
        }
        return Map.of();
    }

    private Map<String, Object> stateSignal(
            String signature,
            String label,
            String stateKind,
            String reason,
            String validFrom,
            String validTo,
            String definition,
            double confidence,
            String sourceReason
    ) {
        return Map.ofEntries(
                Map.entry("signature", signature),
                Map.entry("label", blankTo(label, signature)),
                Map.entry("stateKind", stateKind),
                Map.entry("reason", reason + ":" + sourceReason),
                Map.entry("validFrom", blankTo(validFrom, "")),
                Map.entry("validTo", blankTo(validTo, "")),
                Map.entry("definition", shorten(definition, 500)),
                Map.entry("confidence", confidence)
        );
    }

    private boolean isStatefulEntityType(String normalizedType) {
        return Set.of(
                "person", "organization", "location", "event", "document", "policy", "standard", "clause",
                "project", "system", "product", "algorithm", "technology", "dataset", "dataelement",
                "indicator", "process", "workflow", "task", "requirement", "role", "application",
                "risk", "outcome", "achievement", "thesis", "degreeprogram", "service", "interface"
        ).contains(normalizedType);
    }

    private boolean isTemplateStatusPredicate(String normalizedPredicate, String normalizedType, Map<String, List<String>> hintsByType) {
        if (normalizedPredicate.isBlank()) {
            return false;
        }
        return hintsByType.getOrDefault(normalizedType, List.of()).stream()
                .map(this::normalizeCandidatePredicate)
                .filter(hint -> !hint.isBlank() && isStatusPredicate(hint))
                .anyMatch(hint -> normalizedPredicate.contains(hint) || hint.contains(normalizedPredicate));
    }

    private boolean isStatusPredicate(String normalizedPredicate) {
        return !normalizedPredicate.isBlank()
                && List.of("状态", "进度", "有效性", "可用状态", "评审状态", "归档状态", "转化状态")
                .stream()
                .map(this::normalizeCandidatePredicate)
                .anyMatch(normalizedPredicate::contains);
    }

    private boolean hasTransitionSignal(String normalizedText) {
        return List.of(
                "升级", "演化", "演进", "发展为", "变更", "更名", "替代", "取代", "迁移", "改版", "修订",
                "废止", "新增", "删除", "调整", "优化", "迭代", "上线", "下线", "延期", "完成"
        ).stream().anyMatch(normalizedText::contains);
    }

    private String phaseSignature(String normalizedText) {
        if (normalizedText.contains("一期") || normalizedText.contains("第一阶段") || normalizedText.contains("第一期")) {
            return "phase:1";
        }
        if (normalizedText.contains("二期") || normalizedText.contains("第二阶段") || normalizedText.contains("第二期")) {
            return "phase:2";
        }
        if (normalizedText.contains("三期") || normalizedText.contains("第三阶段") || normalizedText.contains("第三期")) {
            return "phase:3";
        }
        if (normalizedText.contains("四期") || normalizedText.contains("第四阶段") || normalizedText.contains("第四期")) {
            return "phase:4";
        }
        if (normalizedText.contains("升级") || normalizedText.contains("改版") || normalizedText.contains("新版") || normalizedText.contains("新版本")) {
            return "phase:upgrade";
        }
        return "";
    }

    private String phaseLabel(String signature) {
        return switch (signature) {
            case "phase:1" -> "一期";
            case "phase:2" -> "二期";
            case "phase:3" -> "三期";
            case "phase:4" -> "四期";
            case "phase:upgrade" -> "升级阶段";
            default -> signature;
        };
    }

    private String timeRangeSignature(String validFrom, String validTo, String text) {
        String from = normalize(validFrom);
        String to = normalize(validTo);
        if (!from.isBlank() || !to.isBlank()) {
            return from + "|" + to;
        }
        String normalized = Normalizer.normalize(stringValue(text), Normalizer.Form.NFKC).replace('．', '.');
        java.util.regex.Matcher range = Pattern.compile("(20\\d{2})(?:[./-](\\d{1,2}))?\\s*[-至到~—]\\s*(20\\d{2})(?:[./-](\\d{1,2}))?").matcher(normalized);
        if (range.find()) {
            String start = range.group(1) + (range.group(2) == null ? "" : "-" + range.group(2));
            String end = range.group(3) + (range.group(4) == null ? "" : "-" + range.group(4));
            return normalize(start) + "|" + normalize(end);
        }
        return "";
    }

    private String versionSignature(String normalizedPredicate, String text) {
        if (!normalizedPredicate.contains("版本")) {
            return "";
        }
        String normalized = Normalizer.normalize(stringValue(text), Normalizer.Form.NFKC);
        java.util.regex.Matcher version = Pattern.compile("(?i)(v\\s*\\d+(?:\\.\\d+)?|\\d+\\.\\d+)").matcher(normalized);
        if (version.find()) {
            return normalize(version.group(1));
        }
        String value = normalize(normalized);
        if (value.length() >= 2 && value.length() <= 24 && !value.matches("^[0-9]+$")) {
            return value;
        }
        return "";
    }

    private boolean looksLikeSourceArtifact(String value) {
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return true;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return SOURCE_FILE_EXTENSION.matcher(lower).matches()
                || lower.startsWith("liushl_")
                || IMPORT_SUFFIX.matcher(raw).matches();
    }

    public Map<String, Object> governAttributes(List<Map<String, Object>> templateHints, String governanceBatchId) {
        ensureSchema();
        String batchId = blankTo(governanceBatchId, UUID.randomUUID().toString());
        List<Map<String, Object>> hints = templateHints == null ? List.of() : templateHints;
        execute("""
                MATCH (fact:Fact)
                WHERE coalesce(fact.factKind, '') = 'attribute_candidate'
                  AND NOT (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact)
                  AND fact.factKey CONTAINS ':fact:'
                WITH fact, split(fact.factKey, ':fact:')[0] AS stateKey
                MATCH (state:EntityState {stateKey: stateKey})
                MERGE (state)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.status = 'candidate',
                    attrRel.updatedAt = $now
                """,
                Map.of("now", OffsetDateTime.now().toString()));
        JsonNode seededRoot = query("""
                MATCH (subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[objectRel:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                  AND (
                      coalesce(objectEntity.entityType, '') IN ['Time', 'MetricValue']
                      OR coalesce(objectEntity.canonicalName, '') =~ '^[0-9]+(\\\\.[0-9]+)?(%|％|个|项|名|所|次|篇|位|人|元|万元|亿元|年|月|日)?$'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS '字段'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS '每条记录'
                      OR (
                          toLower(coalesce(fact.statement, '')) CONTAINS '列表'
                          AND toLower(coalesce(fact.statement, '')) CONTAINS '包含'
                      )
                      OR (
                          size(coalesce(fact.statement, '')) <= 24
                          AND coalesce(fact.statement, '') <> ''
                          AND NOT coalesce(fact.statement, '') =~ '.*[。；;，,].*'
                          AND NOT any(action IN $actionPredicates WHERE toLower(coalesce(fact.relationType, fact.predicate, '')) CONTAINS action)
                      )
                  )
                MERGE (subject)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.status = 'candidate',
                    attrRel.updatedAt = $now,
                    fact.factKind = 'attribute_candidate',
                    fact.attributeStatus = 'candidate',
                    fact.attributeKey = coalesce(fact.attributeKey, fact.relationType, fact.predicate, ''),
                    fact.attributeValue = coalesce(fact.attributeValue, fact.value, fact.objectText, objectEntity.canonicalName, ''),
                    fact.attributeCandidateObjectStateKey = object.stateKey,
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = 'attribute_like_relation_candidate',
                    fact.updatedAt = $now
                DELETE objectRel
                RETURN count(DISTINCT fact) AS seeded
                """,
                Map.of(
                        "actionPredicates", List.of(
                                "召开", "提出", "印发", "颁布", "取消", "建设", "提升", "提高", "探索", "实现", "开展",
                                "完成", "负责", "参与", "支持", "应用", "按照", "根据", "必须", "提供", "达到",
                                "促进", "推动", "服务", "管理", "研究", "发布", "要求", "涉及"
                        ),
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int attributeCandidatesSeeded = intAt(seededRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode candidateDetachedRoot = query("""
                MATCH (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact:Fact)-[objectRel:OBJECT_OF]->(object:EntityState)
                WHERE coalesce(fact.factKind, '') = 'attribute_candidate'
                  AND coalesce(fact.attributeStatus, 'candidate') = 'candidate'
                SET fact.attributeCandidateObjectStateKey = coalesce(fact.attributeCandidateObjectStateKey, object.stateKey),
                    fact.updatedAt = $now
                DELETE objectRel
                RETURN count(DISTINCT fact) AS detached
                """,
                Map.of("now", OffsetDateTime.now().toString()));
        int candidateObjectEdgesDetached = intAt(candidateDetachedRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode demoteRoot = query("""
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.factKind, '') = 'attribute_fact'
                WITH subjectEntity, subject, attrRel, fact,
                     toLower(coalesce(fact.attributeKey, fact.relationType, fact.predicate, '')) AS attrText
                WHERE NOT EXISTS {
                    UNWIND $hints AS hint
                    WITH hint, subjectEntity, attrText
                    WHERE toLower(coalesce(subjectEntity.entityType, '')) = toLower(hint.entityType)
                      AND (
                          attrText = toLower(hint.hint)
                          OR attrText CONTAINS toLower(hint.hint)
                          OR toLower(hint.hint) CONTAINS attrText
                      )
                    RETURN 1
                }
                SET fact.factKind = 'attribute_candidate',
                    fact.attributeStatus = 'candidate',
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = 'template_miss',
                    fact.updatedAt = $now
                SET attrRel.status = 'candidate',
                    attrRel.updatedAt = $now
                RETURN count(DISTINCT fact) AS demoted
                """,
                Map.of(
                        "hints", hints,
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int demoted = intAt(demoteRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode qualityDemoteRoot = query("""
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.factKind, '') = 'attribute_fact'
                  AND (
                      coalesce(fact.governanceStage, '') = 'structure_enhancement'
                      OR (
                          toLower(coalesce(fact.statement, '')) CONTAINS '章节'
                          AND toLower(coalesce(fact.statement, '')) CONTAINS '涉及'
                      )
                      OR toLower(coalesce(fact.statement, '')) CONTAINS '每条记录'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS '字段'
                      OR (
                          toLower(coalesce(fact.statement, '')) CONTAINS '列表'
                          AND toLower(coalesce(fact.statement, '')) CONTAINS '包含'
                      )
                  )
                SET fact.factKind = 'attribute_candidate',
                    fact.attributeStatus = 'candidate',
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = CASE
                        WHEN coalesce(fact.governanceStage, '') = 'structure_enhancement' THEN 'structure_fact_not_attribute'
                        ELSE 'field_list_not_entity_attribute'
                    END,
                    fact.updatedAt = $now
                SET attrRel.status = 'candidate',
                    attrRel.updatedAt = $now
                RETURN count(DISTINCT fact) AS demoted
                """,
                Map.of(
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int qualityDemoted = intAt(qualityDemoteRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode formalizedRoot = query("""
                UNWIND $hints AS hint
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.factKind, '') IN ['attribute_fact', 'attribute_candidate']
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '章节'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '涉及'
                  )
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '每条记录'
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '字段'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '列表'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '包含'
                  )
                  AND toLower(coalesce(subjectEntity.entityType, '')) = toLower(hint.entityType)
                  AND (
                      toLower(coalesce(fact.attributeKey, fact.relationType, fact.predicate, '')) = toLower(hint.hint)
                      OR toLower(coalesce(fact.attributeKey, fact.relationType, fact.predicate, '')) CONTAINS toLower(hint.hint)
                      OR toLower(hint.hint) CONTAINS toLower(coalesce(fact.attributeKey, fact.relationType, fact.predicate, ''))
                  )
                WITH DISTINCT subject, fact, hint,
                     coalesce(fact.statement, '') AS statement,
                     coalesce(fact.attributeValue, fact.value, fact.objectText, fact.statement) AS rawValue
                WITH subject, fact, hint,
                     CASE
                         WHEN statement CONTAINS '：'
                              AND toLower(split(statement, '：')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, '：')[1]) <> '' THEN trim(split(statement, '：')[1])
                         WHEN statement CONTAINS ':'
                              AND toLower(split(statement, ':')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, ':')[1]) <> '' THEN trim(split(statement, ':')[1])
                         ELSE rawValue
                     END AS governedValue
                MERGE (subject)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.updatedAt = $now,
                    fact.factKind = 'attribute_fact',
                    fact.attributeStatus = 'formal',
                    fact.attributeKey = hint.hint,
                    fact.relationType = hint.hint,
                    fact.predicate = hint.hint,
                    fact.attributeValue = governedValue,
                    fact.value = governedValue,
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = 'template_match_existing',
                    fact.updatedAt = $now
                RETURN count(DISTINCT fact) AS formalized
                """,
                Map.of(
                        "hints", hints,
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int formalized = intAt(formalizedRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode templateRoot = query("""
                UNWIND $hints AS hint
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[objectRel:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '章节'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '涉及'
                  )
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '每条记录'
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '字段'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '列表'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '包含'
                  )
                  AND toLower(coalesce(subjectEntity.entityType, '')) = toLower(hint.entityType)
                  AND (
                      toLower(coalesce(fact.relationType, fact.predicate, '')) = toLower(hint.hint)
                      OR toLower(coalesce(fact.relationType, fact.predicate, '')) CONTAINS toLower(hint.hint)
                      OR toLower(hint.hint) CONTAINS toLower(coalesce(fact.relationType, fact.predicate, ''))
                  )
                WITH DISTINCT subject, fact, objectRel, objectEntity, hint,
                     coalesce(fact.statement, '') AS statement,
                     coalesce(fact.value, fact.objectText, objectEntity.canonicalName, fact.statement) AS rawValue
                WITH subject, fact, objectRel, objectEntity, hint,
                     CASE
                         WHEN statement CONTAINS '：'
                              AND toLower(split(statement, '：')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, '：')[1]) <> '' THEN trim(split(statement, '：')[1])
                         WHEN statement CONTAINS ':'
                              AND toLower(split(statement, ':')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, ':')[1]) <> '' THEN trim(split(statement, ':')[1])
                         ELSE rawValue
                     END AS governedValue
                MERGE (subject)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.updatedAt = $now,
                    fact.factKind = 'attribute_fact',
                    fact.attributeStatus = 'formal',
                    fact.attributeKey = hint.hint,
                    fact.relationType = hint.hint,
                    fact.predicate = hint.hint,
                    fact.attributeValue = governedValue,
                    fact.value = governedValue,
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = 'template_match_relation',
                    fact.updatedAt = $now
                DELETE objectRel
                RETURN count(DISTINCT fact) AS governed
                """,
                Map.of(
                        "hints", hints,
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int templateGoverned = intAt(templateRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode scalarRoot = query("""
                UNWIND $hints AS hint
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[objectRel:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WHERE coalesce(fact.factKind, 'relation_fact') = 'relation_fact'
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '章节'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '涉及'
                  )
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '每条记录'
                  AND NOT toLower(coalesce(fact.statement, '')) CONTAINS '字段'
                  AND NOT (
                      toLower(coalesce(fact.statement, '')) CONTAINS '列表'
                      AND toLower(coalesce(fact.statement, '')) CONTAINS '包含'
                  )
                  AND toLower(coalesce(subjectEntity.entityType, '')) = toLower(hint.entityType)
                  AND (
                      coalesce(objectEntity.entityType, '') IN ['Time', 'MetricValue']
                      OR objectEntity.canonicalName =~ '^[0-9]+(\\\\.[0-9]+)?(%|％|个|项|名|所|次|篇|位|人|元|万元|亿元|年|月|日)?$'
                  )
                  AND (
                      toLower(coalesce(fact.relationType, fact.predicate, '')) = toLower(hint.hint)
                      OR toLower(coalesce(fact.relationType, fact.predicate, '')) CONTAINS toLower(hint.hint)
                      OR toLower(hint.hint) CONTAINS toLower(coalesce(fact.relationType, fact.predicate, ''))
                  )
                WITH DISTINCT subject, fact, objectRel, objectEntity, hint,
                     coalesce(fact.statement, '') AS statement,
                     coalesce(fact.value, fact.objectText, objectEntity.canonicalName, fact.statement) AS rawValue
                WITH subject, fact, objectRel, objectEntity, hint,
                     CASE
                         WHEN statement CONTAINS '：'
                              AND toLower(split(statement, '：')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, '：')[1]) <> '' THEN trim(split(statement, '：')[1])
                         WHEN statement CONTAINS ':'
                              AND toLower(split(statement, ':')[0]) CONTAINS toLower(hint.hint)
                              AND trim(split(statement, ':')[1]) <> '' THEN trim(split(statement, ':')[1])
                         ELSE rawValue
                     END AS governedValue
                MERGE (subject)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.updatedAt = $now,
                    fact.factKind = 'attribute_fact',
                    fact.attributeStatus = 'formal',
                    fact.attributeKey = hint.hint,
                    fact.relationType = hint.hint,
                    fact.predicate = hint.hint,
                    fact.attributeValue = governedValue,
                    fact.value = governedValue,
                    fact.governanceStage = 'attribute_governance',
                    fact.governanceBatchId = $batchId,
                    fact.governanceReason = 'template_match_scalar',
                    fact.updatedAt = $now
                DELETE objectRel
                RETURN count(DISTINCT fact) AS governed
                """,
                Map.of(
                        "hints", hints,
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int scalarGoverned = intAt(scalarRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        execute("""
                MATCH (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.factKind, '') = 'attribute_fact'
                SET fact.attributeStatus = 'formal',
                    fact.updatedAt = $now
                """,
                Map.of("now", OffsetDateTime.now().toString()));
        return Map.ofEntries(
                Map.entry("stage", "attribute_governance_completed"),
                Map.entry("governanceBatchId", batchId),
                Map.entry("attributeCandidatesSeeded", attributeCandidatesSeeded),
                Map.entry("candidateObjectEdgesDetached", candidateObjectEdgesDetached),
                Map.entry("candidateDemoted", demoted),
                Map.entry("qualityDemoted", qualityDemoted),
                Map.entry("existingFormalized", formalized),
                Map.entry("templateGoverned", templateGoverned),
                Map.entry("scalarGoverned", scalarGoverned),
                Map.entry("attributeGoverned", formalized + templateGoverned + scalarGoverned)
        );
    }

    public Map<String, Object> readAttributeCandidateClusters(List<Map<String, Object>> templateHints, int limit) {
        ensureSchema();
        recoverAttributeCandidateLinks();
        List<Map<String, Object>> clusters = buildAttributeCandidateClusters(templateHints, Math.max(1, Math.min(limit, 500)));
        return Map.of(
                "clusters", clusters,
                "clusterCount", clusters.size(),
                "generatedAt", OffsetDateTime.now().toString()
        );
    }

    public Map<String, Object> applyAttributeCandidateClusters(
            List<Map<String, Object>> templateHints,
            double minConfidence,
            int maxClusters,
            boolean dryRun
    ) {
        ensureSchema();
        recoverAttributeCandidateLinks();
        int clusterLimit = Math.max(1, Math.min(maxClusters, 500));
        List<Map<String, Object>> clusters = buildAttributeCandidateClusters(templateHints, clusterLimit);
        return applyAttributeCandidateClusters(clusters, minConfidence, maxClusters, dryRun);
    }

    public Map<String, Object> applyAttributeCandidateClusterDecisions(
            List<Map<String, Object>> clusters,
            double minConfidence,
            int maxClusters,
            boolean dryRun
    ) {
        ensureSchema();
        recoverAttributeCandidateLinks();
        int clusterLimit = Math.max(1, Math.min(maxClusters, 500));
        double threshold = Math.max(0.0d, Math.min(1.0d, minConfidence));
        List<Map<String, Object>> inputClusters = clusters == null ? List.of() : clusters.stream().limit(clusterLimit).toList();
        List<Map<String, Object>> promotions = new ArrayList<>();
        List<Map<String, Object>> rejections = new ArrayList<>();
        List<Map<String, Object>> selectedClusters = new ArrayList<>();
        for (Map<String, Object> cluster : inputClusters) {
            if (doubleValue(cluster.get("confidence")) < threshold) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = cluster.get("facts") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            String decision = stringValue(cluster.get("decision"));
            if ("attribute_fact".equals(decision)) {
                String attributeKey = stringValue(cluster.get("targetAttributeKey"));
                for (Map<String, Object> fact : facts) {
                    String factKey = stringValue(fact.get("factKey"));
                    String stateKey = stringValue(fact.get("stateKey"));
                    if (factKey.isBlank() || stateKey.isBlank() || attributeKey.isBlank()) {
                        continue;
                    }
                    promotions.add(Map.of(
                            "factKey", factKey,
                            "stateKey", stateKey,
                            "attributeKey", attributeKey,
                            "attributeValue", cleanGovernedAttributeValue(attributeKey, stringValue(fact.get("statement")), stringValue(fact.get("value"))),
                            "clusterKey", stringValue(cluster.get("clusterKey")),
                            "confidence", doubleValue(cluster.get("confidence")),
                            "reason", stringValue(cluster.get("reason"))
                    ));
                }
            } else if ("discard_or_relation".equals(decision) || "relation_or_event".equals(decision)) {
                for (Map<String, Object> fact : facts) {
                    String factKey = stringValue(fact.get("factKey"));
                    if (factKey.isBlank()) {
                        continue;
                    }
                    rejections.add(Map.of(
                            "factKey", factKey,
                            "clusterKey", stringValue(cluster.get("clusterKey")),
                            "decision", decision,
                            "confidence", doubleValue(cluster.get("confidence")),
                            "reason", stringValue(cluster.get("reason"))
                    ));
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>(cluster);
            summary.remove("facts");
            selectedClusters.add(summary);
        }
        String batchId = UUID.randomUUID().toString();
        if (!dryRun && !promotions.isEmpty()) {
            execute("""
                    UNWIND $promotions AS promotion
                    MATCH (state:EntityState {stateKey: promotion.stateKey})
                    MATCH (fact:Fact {factKey: promotion.factKey})
                    OPTIONAL MATCH (fact)-[objectRel:OBJECT_OF]->(:EntityState)
                    MERGE (state)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                    ON CREATE SET attrRel.createdAt = $now
                    SET attrRel.updatedAt = $now,
                        fact.factKind = 'attribute_fact',
                        fact.attributeStatus = 'formal',
                        fact.attributeKey = promotion.attributeKey,
                        fact.relationType = promotion.attributeKey,
                        fact.predicate = promotion.attributeKey,
                        fact.attributeValue = promotion.attributeValue,
                        fact.value = promotion.attributeValue,
                        fact.governanceStage = 'attribute_candidate_cluster',
                        fact.governanceReason = promotion.reason,
                        fact.governanceClusterKey = promotion.clusterKey,
                        fact.governanceConfidence = promotion.confidence,
                        fact.governanceBatchId = $batchId,
                        fact.governanceDecision = 'attribute_fact',
                        fact.updatedAt = $now
                    DELETE objectRel
                    """,
                    Map.of(
                            "promotions", promotions,
                            "batchId", batchId,
                            "now", OffsetDateTime.now().toString()
                    ));
        }
        if (!dryRun && !rejections.isEmpty()) {
            execute("""
                    UNWIND $rejections AS rejection
                    MATCH (fact:Fact {factKey: rejection.factKey})
                    OPTIONAL MATCH (object:EntityState {stateKey: coalesce(fact.attributeCandidateObjectStateKey, '')})
                    OPTIONAL MATCH (:EntityState)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                    SET fact.factKind = CASE
                            WHEN rejection.decision = 'relation_or_event' THEN 'relation_fact'
                            ELSE coalesce(fact.factKind, 'attribute_candidate')
                        END,
                        fact.attributeStatus = 'rejected',
                        fact.attributeCandidateDecision = rejection.decision,
                        fact.governanceStage = 'attribute_candidate_cluster',
                        fact.governanceReason = rejection.reason,
                        fact.governanceClusterKey = rejection.clusterKey,
                        fact.governanceConfidence = rejection.confidence,
                        fact.governanceBatchId = $batchId,
                        fact.governanceDecision = rejection.decision,
                        fact.updatedAt = $now
                    SET attrRel.status = 'rejected',
                        attrRel.updatedAt = $now
                    FOREACH (_ IN CASE WHEN rejection.decision = 'relation_or_event' AND object IS NOT NULL THEN [1] ELSE [] END |
                        MERGE (fact)-[:OBJECT_OF]->(object)
                    )
                    """,
                    Map.of(
                            "rejections", rejections,
                            "batchId", batchId,
                            "now", OffsetDateTime.now().toString()
                    ));
        }
        return Map.of(
                "dryRun", dryRun,
                "governanceBatchId", batchId,
                "minConfidence", threshold,
                "selectedClusters", selectedClusters.size(),
                "promotedFacts", promotions.size(),
                "rejectedCandidates", rejections.size(),
                "clusters", selectedClusters
        );
    }

    public Map<String, Object> enhanceStructure(List<Map<String, Object>> chunkContexts, String governanceBatchId) {
        ensureSchema();
        String batchId = blankTo(governanceBatchId, UUID.randomUUID().toString());
        List<Map<String, Object>> contexts = chunkContexts == null ? List.of() : chunkContexts;
        JsonNode sectionMentionRoot = query("""
                MATCH (mention:Mention)-[:REFERS_TO]->(state:EntityState)
                MATCH (mention)-[:APPEARS_IN]->(chunk:ChunkRef)
                MERGE (state)-[sectionMention:MENTIONED_IN]->(chunk)
                ON CREATE SET sectionMention.createdAt = $now
                SET sectionMention.docId = coalesce(mention.docId, chunk.docId, ''),
                    sectionMention.chunkId = coalesce(mention.chunkId, chunk.chunkId, ''),
                    sectionMention.sourceSpan = coalesce(mention.sourceSpan, ''),
                    sectionMention.confidence = coalesce(mention.confidence, 0.0),
                    sectionMention.relationKind = 'section_mention',
                    sectionMention.updatedAt = $now
                RETURN count(DISTINCT sectionMention) AS links
                """, Map.of("now", OffsetDateTime.now().toString()));
        int sectionMentionLinks = intAt(sectionMentionRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        execute("""
                MATCH (fact:Fact)
                WHERE coalesce(fact.governanceStage, '') = 'structure_enhancement'
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                DETACH DELETE fact, evidence
                """, Map.of());
        JsonNode root = query("""
                UNWIND $contexts AS ctx
                MATCH (chunk:ChunkRef {chunkId: ctx.chunkId})
                SET chunk.title = ctx.title,
                    chunk.chunkType = ctx.chunkType,
                    chunk.keywords = coalesce(ctx.keywords, []),
                    chunk.updatedAt = $now
                WITH DISTINCT ctx, chunk, [keyword IN coalesce(ctx.keywords, []) | toLower(toString(keyword))] AS keywords
                WHERE NOT coalesce(ctx.title, '') =~ '^[0-9]+(\\\\.[0-9]+)?$'
                CALL {
                    WITH ctx, chunk, keywords
                    CALL {
                        WITH ctx
                        MATCH (anchor:Entity)-[:HAS_STATE]->(anchorState:EntityState)
                        WHERE toLower(coalesce(anchor.entityType, '')) IN $anchorTypes
                          AND size(coalesce(anchor.canonicalName, '')) >= 3
                          AND size(coalesce(anchor.canonicalName, '')) <= 30
                  AND NOT coalesce(anchor.canonicalName, '') IN $excludedStructureNames
                          AND NOT coalesce(anchor.canonicalName, '') IN $excludedStructureFieldNames
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '、'
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '，'
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '；'
                          AND NOT any(fragment IN $excludedStructureFragments WHERE coalesce(anchor.canonicalName, '') CONTAINS fragment)
                          AND (
                              toLower(coalesce(ctx.docTitle, '')) CONTAINS toLower(coalesce(anchor.canonicalName, ''))
                              OR toLower(coalesce(ctx.title, '')) CONTAINS toLower(coalesce(anchor.canonicalName, ''))
                          )
                        WITH anchor, anchorState,
                             CASE
                                 WHEN toLower(coalesce(ctx.docTitle, '')) CONTAINS toLower(coalesce(anchor.canonicalName, '')) THEN 2
                                 ELSE 1
                             END AS anchorRank
                        RETURN anchor, anchorState, anchorRank
                        ORDER BY anchorRank DESC, size(coalesce(anchor.canonicalName, '')) DESC
                        LIMIT 1
                        UNION
                        WITH ctx, chunk, keywords
                        MATCH (chunk)<-[:APPEARS_IN]-(:Mention)-[:REFERS_TO]->(anchorState:EntityState)<-[:HAS_STATE]-(anchor:Entity)
                        WHERE toLower(coalesce(anchor.entityType, '')) IN $anchorTypes
                          AND size(coalesce(anchor.canonicalName, '')) >= 3
                          AND size(coalesce(anchor.canonicalName, '')) <= 30
                          AND NOT coalesce(anchor.canonicalName, '') IN $excludedStructureNames
                          AND NOT coalesce(anchor.canonicalName, '') IN $excludedStructureFieldNames
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '、'
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '，'
                          AND NOT coalesce(anchor.canonicalName, '') CONTAINS '；'
                          AND NOT any(fragment IN $excludedStructureFragments WHERE coalesce(anchor.canonicalName, '') CONTAINS fragment)
                          AND any(keyword IN keywords WHERE keyword CONTAINS toLower(coalesce(anchor.canonicalName, '')) OR toLower(coalesce(anchor.canonicalName, '')) CONTAINS keyword)
                        RETURN anchor, anchorState, 0 AS anchorRank
                        ORDER BY size(coalesce(anchor.canonicalName, '')) DESC
                        LIMIT 1
                    }
                    RETURN anchor, anchorState
                    ORDER BY anchorRank DESC, size(coalesce(anchor.canonicalName, '')) DESC
                    LIMIT 1
                }
                WITH ctx, chunk, keywords, anchor, anchorState
                WHERE anchor IS NOT NULL
                MATCH (chunk)<-[:APPEARS_IN]-(:Mention)-[:REFERS_TO]->(targetState:EntityState)<-[:HAS_STATE]-(target:Entity)
                WHERE target.canonicalKey <> anchor.canonicalKey
                  AND coalesce(target.canonicalName, '') <> coalesce(anchor.canonicalName, '')
                  AND NOT toLower(coalesce(target.entityType, '')) IN ['time','metricvalue','other']
                  AND size(coalesce(target.canonicalName, '')) >= 2
                  AND size(coalesce(target.canonicalName, '')) <= 30
                  AND NOT coalesce(target.canonicalName, '') =~ '^[0-9]+(\\\\.[0-9]+)?$'
                  AND NOT coalesce(target.canonicalName, '') IN $excludedStructureNames
                  AND NOT coalesce(target.canonicalName, '') IN $excludedStructureFieldNames
                  AND NOT coalesce(target.canonicalName, '') CONTAINS '、'
                  AND NOT coalesce(target.canonicalName, '') CONTAINS '，'
                  AND NOT coalesce(target.canonicalName, '') CONTAINS '；'
                  AND toLower(coalesce(target.entityType, '')) IN $structureTargetTypes
                  AND NOT any(fragment IN $excludedStructureFragments WHERE coalesce(target.canonicalName, '') CONTAINS fragment)
                  AND NOT EXISTS {
                      MATCH (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(attributeCandidate:Fact)
                      WHERE coalesce(attributeCandidate.factKind, '') = 'attribute_candidate'
                        AND coalesce(attributeCandidate.attributeStatus, 'candidate') = 'candidate'
                        AND coalesce(attributeCandidate.attributeCandidateObjectStateKey, '') = targetState.stateKey
                  }
                WITH DISTINCT ctx, chunk, keywords, anchor, anchorState, target, targetState,
                     CASE
                         WHEN any(keyword IN keywords WHERE keyword CONTAINS toLower(coalesce(target.canonicalName, '')) OR toLower(coalesce(target.canonicalName, '')) CONTAINS keyword) THEN 1
                         ELSE 0
                     END AS keywordHit,
                     CASE
                         WHEN toLower(coalesce(ctx.title, '')) CONTAINS toLower(coalesce(target.canonicalName, '')) THEN 1
                         ELSE 0
                     END AS titleHit
                WHERE keywordHit = 1 OR titleHit = 1
                ORDER BY titleHit DESC, keywordHit DESC, size(coalesce(target.canonicalName, '')) DESC
                WITH ctx, chunk, keywords, anchor, anchorState, collect({target: target, targetState: targetState, keywordHit: keywordHit, titleHit: titleHit})[0..6] AS targets
                UNWIND targets AS item
                WITH ctx, chunk, keywords, anchor, anchorState, item.target AS target, item.targetState AS targetState, item.keywordHit AS keywordHit, item.titleHit AS titleHit
                OPTIONAL MATCH (chunk)<-[:APPEARS_IN]-(:Mention)-[:REFERS_TO]->(parentState:EntityState)<-[:HAS_STATE]-(parent:Entity)
                WHERE parent.canonicalKey <> anchor.canonicalKey
                  AND parent.canonicalKey <> target.canonicalKey
                  AND coalesce(parent.canonicalName, '') <> coalesce(anchor.canonicalName, '')
                  AND coalesce(parent.canonicalName, '') <> coalesce(target.canonicalName, '')
                  AND toLower(coalesce(parent.entityType, '')) IN $structureParentTypes
                  AND size(coalesce(parent.canonicalName, '')) >= 2
                  AND size(coalesce(parent.canonicalName, '')) <= 30
                  AND NOT coalesce(parent.canonicalName, '') IN $excludedStructureNames
                  AND NOT coalesce(parent.canonicalName, '') IN $excludedStructureFieldNames
                  AND NOT coalesce(parent.canonicalName, '') CONTAINS '、'
                  AND NOT coalesce(parent.canonicalName, '') CONTAINS '，'
                  AND NOT coalesce(parent.canonicalName, '') CONTAINS '；'
                  AND NOT any(fragment IN $excludedStructureFragments WHERE coalesce(parent.canonicalName, '') CONTAINS fragment)
                  AND NOT EXISTS {
                      MATCH (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(attributeCandidate:Fact)
                      WHERE coalesce(attributeCandidate.factKind, '') = 'attribute_candidate'
                        AND coalesce(attributeCandidate.attributeStatus, 'candidate') = 'candidate'
                        AND coalesce(attributeCandidate.attributeCandidateObjectStateKey, '') = parentState.stateKey
                  }
                WITH ctx, keywords, anchor, anchorState, target, targetState, keywordHit, titleHit, parent, parentState,
                     CASE
                         WHEN parent IS NOT NULL
                              AND toLower(coalesce(ctx.title, '')) CONTAINS toLower(coalesce(parent.canonicalName, '')) THEN 1
                         ELSE 0
                     END AS parentTitleHit,
                     CASE
                         WHEN parent IS NOT NULL
                              AND any(keyword IN keywords WHERE keyword CONTAINS toLower(coalesce(parent.canonicalName, '')) OR toLower(coalesce(parent.canonicalName, '')) CONTAINS keyword) THEN 1
                         ELSE 0
                     END AS parentKeywordHit
                WHERE parent IS NULL OR parentTitleHit = 1 OR parentKeywordHit = 1
                WITH ctx, keywords, anchor, anchorState, target, targetState, keywordHit, titleHit, parent, parentState,
                     parentTitleHit, parentKeywordHit,
                     CASE
                         WHEN parent IS NULL THEN -1
                         WHEN parentTitleHit = 1 THEN 5
                         WHEN parentKeywordHit = 1 THEN 4
                         WHEN toLower(coalesce(parent.entityType, '')) = 'requirement' THEN 3
                         ELSE 1
                     END AS parentRank
                WHERE parent IS NULL
                   OR toLower(coalesce(parent.entityType, '')) <> 'concept'
                   OR parentTitleHit = 1
                   OR any(fragment IN $structureParentNameFragments WHERE coalesce(parent.canonicalName, '') CONTAINS fragment)
                ORDER BY parentRank DESC, size(coalesce(parent.canonicalName, '')) DESC
                WITH ctx, keywords, anchor, anchorState, target, targetState, keywordHit, titleHit,
                     collect({parent: parent, parentState: parentState, parentRank: parentRank})[0] AS parentItem
                WITH ctx, keywords, anchor, anchorState, target, targetState, keywordHit, titleHit,
                     CASE WHEN parentItem.parent IS NULL THEN anchor ELSE parentItem.parent END AS subject,
                     CASE WHEN parentItem.parentState IS NULL THEN anchorState ELSE parentItem.parentState END AS subjectState,
                     CASE WHEN parentItem.parent IS NULL THEN 'anchor_child' ELSE 'local_parent_child' END AS structureRole
                WHERE NOT (
                    structureRole = 'anchor_child'
                    AND titleHit = 0
                )
                MERGE (fact:Fact {factKey: 'structure:' + subject.canonicalKey + ':' + target.canonicalKey})
                ON CREATE SET fact.id = randomUUID(), fact.createdAt = $now
                SET fact.factKind = 'relation_fact',
                    fact.relationType = CASE WHEN structureRole = 'local_parent_child' THEN '结构包含' ELSE '章节涉及' END,
                    fact.predicate = CASE WHEN structureRole = 'local_parent_child' THEN '结构包含' ELSE '章节涉及' END,
                    fact.statement = CASE
                        WHEN structureRole = 'local_parent_child' THEN '结构「' + coalesce(subject.canonicalName, '') + '」包含「' + coalesce(target.canonicalName, '') + '」。'
                        ELSE '章节「' + coalesce(ctx.title, '') + '」涉及「' + coalesce(target.canonicalName, '') + '」。'
                    END,
                    fact.status = 'active',
                    fact.confidence = CASE WHEN titleHit = 1 THEN 0.72 WHEN keywordHit = 1 THEN 0.68 ELSE 0.62 END,
                    fact.structureSignals = keywords[0..8],
                    fact.structureRole = structureRole,
                    fact.structureAnchorKey = anchor.canonicalKey,
                    fact.structureAnchorName = anchor.canonicalName,
                    fact.structureSubjectKey = subject.canonicalKey,
                    fact.structureSubjectName = subject.canonicalName,
                    fact.structureTitle = ctx.title,
                    fact.governanceStage = 'structure_enhancement',
                    fact.governanceBatchId = $batchId,
                    fact.updatedAt = $now
                MERGE (subjectState)-[:SUBJECT_OF]->(fact)
                MERGE (fact)-[:OBJECT_OF]->(targetState)
                MERGE (evidence:Evidence {evidenceKey: 'structure:' + ctx.chunkId + ':' + subject.canonicalKey + ':' + target.canonicalKey})
                ON CREATE SET evidence.id = randomUUID(), evidence.createdAt = $now
                SET evidence.docId = ctx.docId,
                    evidence.chunkId = ctx.chunkId,
                    evidence.sourceSpan = ctx.title,
                    evidence.keywords = keywords[0..8],
                    evidence.statement = fact.statement,
                    evidence.confidence = CASE WHEN titleHit = 1 THEN 0.72 WHEN keywordHit = 1 THEN 0.68 ELSE 0.62 END,
                    evidence.governanceBatchId = $batchId,
                    evidence.updatedAt = $now
                MERGE (fact)-[:SUPPORTED_BY]->(evidence)
                MERGE (chunkRef:ChunkRef {chunkId: ctx.chunkId})
                MERGE (evidence)-[:APPEARS_IN]->(chunkRef)
                RETURN count(DISTINCT fact) AS structuralFacts
                """,
                Map.of(
                        "contexts", contexts,
                        "anchorTypes", List.of("system", "project", "policy", "standard", "organization"),
                        "structureTargetTypes", List.of(
                                "organization", "document", "project", "product", "system", "policy", "process",
                                "indicator", "concept", "technology", "resource", "requirement", "outcome", "standard"
                        ),
                        "structureParentTypes", List.of(
                                "requirement", "concept", "process", "standard", "policy", "system", "project",
                                "technology", "resource", "outcome", "document", "product"
                        ),
                        "structureParentNameFragments", List.of(
                                "要求", "指标", "标准", "规范", "规则", "流程", "模块", "方案", "体系", "机制",
                                "服务", "接口", "安全", "质量", "技术", "条件", "范围", "策略", "能力", "内容"
                        ),
                        "excludedStructureNames", List.of(
                                "我国", "本项目", "本系统", "项目", "系统", "平台", "高校", "院校",
                                "功能", "关键词", "重要内容", "相关工作", "相关工作的负责人", "其他", "内容", "要求",
                                "摘要信息", "表1", "表2"
                        ),
                        "excludedStructureFieldNames", List.of(
                                "序号", "编号", "姓名", "性别", "日期", "时间", "填报日期", "申报日期", "申报时间",
                                "申报单位", "备注", "说明", "附件", "内部", "主要内容", "联系电话", "通讯地址"
                        ),
                        "excludedStructureFragments", List.of("取得了", "开展了", "主持", "贯彻", "部署", "颁布", "取消", "提出", "给予", "作出", "决定", "提供"),
                        "batchId", batchId,
                        "now", OffsetDateTime.now().toString()
                ));
        int structuralFacts = intAt(root.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode reciprocalRoot = query("""
                MATCH (leftSubject:EntityState)-[:SUBJECT_OF]->(leftFact:Fact)-[:OBJECT_OF]->(leftObject:EntityState)
                MATCH (rightSubject:EntityState)-[:SUBJECT_OF]->(rightFact:Fact)-[:OBJECT_OF]->(rightObject:EntityState)
                WHERE coalesce(leftFact.governanceStage, '') = 'structure_enhancement'
                  AND coalesce(rightFact.governanceStage, '') = 'structure_enhancement'
                  AND coalesce(leftFact.structureRole, '') = 'local_parent_child'
                  AND coalesce(rightFact.structureRole, '') = 'local_parent_child'
                  AND leftSubject.stateKey = rightObject.stateKey
                  AND leftObject.stateKey = rightSubject.stateKey
                  AND leftFact.factKey < rightFact.factKey
                MATCH (leftEntity:Entity)-[:HAS_STATE]->(leftSubject)
                MATCH (rightEntity:Entity)-[:HAS_STATE]->(rightSubject)
                WITH DISTINCT CASE
                    WHEN size(coalesce(leftEntity.canonicalName, '')) <= size(coalesce(rightEntity.canonicalName, '')) THEN rightFact
                    ELSE leftFact
                END AS loser
                OPTIONAL MATCH (loser)-[:SUPPORTED_BY]->(evidence:Evidence)
                WITH collect(DISTINCT loser) AS losers, collect(DISTINCT evidence) AS evidences
                FOREACH (fact IN losers | DETACH DELETE fact)
                FOREACH (evidence IN evidences | DETACH DELETE evidence)
                RETURN size(losers) AS removed
                """, Map.of());
        int reciprocalLocalFactsRemoved = intAt(reciprocalRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode parentRoot = query("""
                MATCH (childFact:Fact)
                WHERE coalesce(childFact.governanceStage, '') = 'structure_enhancement'
                  AND coalesce(childFact.structureRole, '') = 'local_parent_child'
                  AND coalesce(childFact.structureAnchorKey, '') <> ''
                  AND coalesce(childFact.structureSubjectKey, '') <> ''
                  AND childFact.structureAnchorKey <> childFact.structureSubjectKey
                MATCH (anchor:Entity {canonicalKey: childFact.structureAnchorKey})-[:HAS_STATE]->(anchorState:EntityState)
                MATCH (subject:Entity {canonicalKey: childFact.structureSubjectKey})-[:HAS_STATE]->(subjectState:EntityState)
                OPTIONAL MATCH (childFact)-[:SUPPORTED_BY]->(childEvidence:Evidence)
                WITH DISTINCT anchor, anchorState, subject, subjectState, childEvidence,
                     coalesce(childFact.structureTitle, '') AS structureTitle,
                     coalesce(childFact.governanceBatchId, $batchId) AS childBatchId
                MERGE (parentFact:Fact {factKey: 'structure-parent:' + anchor.canonicalKey + ':' + subject.canonicalKey})
                ON CREATE SET parentFact.id = randomUUID(), parentFact.createdAt = $now
                SET parentFact.factKind = 'relation_fact',
                    parentFact.relationType = '章节包含',
                    parentFact.predicate = '章节包含',
                    parentFact.statement = '章节「' + structureTitle + '」包含「' + coalesce(subject.canonicalName, '') + '」。',
                    parentFact.status = 'active',
                    parentFact.confidence = 0.74,
                    parentFact.structureRole = 'anchor_parent',
                    parentFact.structureAnchorKey = anchor.canonicalKey,
                    parentFact.structureAnchorName = anchor.canonicalName,
                    parentFact.structureSubjectKey = anchor.canonicalKey,
                    parentFact.structureSubjectName = anchor.canonicalName,
                    parentFact.structureTitle = structureTitle,
                    parentFact.governanceStage = 'structure_enhancement',
                    parentFact.governanceBatchId = $batchId,
                    parentFact.updatedAt = $now
                MERGE (anchorState)-[:SUBJECT_OF]->(parentFact)
                MERGE (parentFact)-[:OBJECT_OF]->(subjectState)
                MERGE (evidence:Evidence {evidenceKey: 'structure-parent:' + coalesce(childEvidence.chunkId, '') + ':' + anchor.canonicalKey + ':' + subject.canonicalKey})
                ON CREATE SET evidence.id = randomUUID(), evidence.createdAt = $now
                SET evidence.docId = coalesce(childEvidence.docId, ''),
                    evidence.chunkId = coalesce(childEvidence.chunkId, ''),
                    evidence.sourceSpan = structureTitle,
                    evidence.keywords = coalesce(childEvidence.keywords, []),
                    evidence.statement = parentFact.statement,
                    evidence.confidence = 0.74,
                    evidence.governanceBatchId = $batchId,
                    evidence.updatedAt = $now
                MERGE (parentFact)-[:SUPPORTED_BY]->(evidence)
                WITH DISTINCT parentFact
                RETURN count(parentFact) AS parentFacts
                """, Map.of("batchId", batchId, "now", OffsetDateTime.now().toString()));
        int parentFacts = intAt(parentRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        return Map.of(
                "stage", "structure_enhancement_completed",
                "governanceBatchId", batchId,
                "inputChunks", contexts.size(),
                "structuralFacts", structuralFacts + parentFacts - reciprocalLocalFactsRemoved,
                "localChildFacts", structuralFacts,
                "anchorParentFacts", parentFacts,
                "reciprocalLocalFactsRemoved", reciprocalLocalFactsRemoved,
                "sectionMentionLinks", sectionMentionLinks
        );
    }

    public Map<String, Object> fuseEntityStates(String fusionBatchId) {
        ensureSchema();
        boolean batchScoped = fusionBatchId != null && !fusionBatchId.isBlank();
        execute("""
                MATCH (s:EntityState)-[member:MEMBER_OF_STATE_CLUSTER]->(:StateCluster)
                WHERE $batchScoped = false OR s.fusionBatchId = $fusionBatchId
                DELETE member
                SET s.stateFusionStatus = 'candidate',
                    s.stateClusterKey = NULL,
                    s.stateFusionUpdatedAt = $now
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "now", OffsetDateTime.now().toString()
                ));
        execute("""
                MATCH (cluster:StateCluster)
                WHERE NOT (:EntityState)-[:MEMBER_OF_STATE_CLUSTER]->(cluster)
                DETACH DELETE cluster
                """, Map.of());
        JsonNode root = query("""
                MATCH (e:Entity)-[:HAS_STATE]->(s:EntityState)
                WHERE ($batchScoped = false OR s.fusionBatchId = $fusionBatchId)
                  AND coalesce(s.stateKind, '') <> 'default_anchor'
                WITH e, s,
                     coalesce(e.fusedClusterKey, e.canonicalKey) AS entityGroupKey,
                     e.canonicalName AS entityName,
                     coalesce(s.validFrom, '') AS validFrom,
                     coalesce(s.validTo, '') AS validTo,
                     toLower(coalesce(s.name, '') + ' ' + coalesce(s.definition, '')) AS stateText
                WITH entityGroupKey, entityName, s, validFrom, validTo, stateText,
                     trim(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(stateText,
                         toLower(coalesce(entityName, '')), ''),
                         '项目名称之一', ''), '项目名称', ''), '系统名称', ''),
                         '文档中提及的', ''), '指该申报书', ''), '指本次申报', ''), '指代', ''), '指', ''),
                         '一个具体的', ''), '具体的', ''), '一个', ''), '核心', ''), '名称', ''),
                         '。', ''), '，', '')) AS normalizedStateText
                WITH entityGroupKey, entityName, s, validFrom, validTo, stateText, normalizedStateText,
                     CASE
                         WHEN stateText CONTAINS '一期' OR stateText CONTAINS '第一阶段' OR stateText CONTAINS '第一期' OR stateText CONTAINS 'v1' OR stateText CONTAINS '1.0' THEN 'phase:1'
                         WHEN stateText CONTAINS '二期' OR stateText CONTAINS '第二阶段' OR stateText CONTAINS '第二期' OR stateText CONTAINS 'v2' OR stateText CONTAINS '2.0' THEN 'phase:2'
                         WHEN stateText CONTAINS '三期' OR stateText CONTAINS '第三阶段' OR stateText CONTAINS '第三期' OR stateText CONTAINS 'v3' OR stateText CONTAINS '3.0' THEN 'phase:3'
                         WHEN stateText CONTAINS '升级' OR stateText CONTAINS '改造' OR stateText CONTAINS '新版' OR stateText CONTAINS '新版本' THEN 'phase:upgrade'
                         WHEN validFrom <> '' OR validTo <> '' THEN 'time:' + validFrom + '|' + validTo
                         WHEN normalizedStateText = '' OR normalizedStateText IN ['项目', '系统', '项目或系统', '智慧管理系统', '相关的系统项目'] THEN 'default'
                         ELSE 'text:' + left(normalizedStateText, 48)
                     END AS stateSignature
                WITH entityGroupKey, stateSignature,
                     collect(s) AS states,
                     collect(DISTINCT entityName)[0] AS entityName
                WHERE size(states) >= 1
                WITH entityGroupKey, stateSignature, states, entityName,
                     reduce(best = head(states), candidate IN states |
                         CASE
                             WHEN size(coalesce(candidate.definition, '')) > size(coalesce(best.definition, '')) THEN candidate
                             ELSE best
                         END
                     ) AS representative
                MERGE (cluster:StateCluster {stateClusterKey: entityGroupKey + ':state:' + stateSignature})
                ON CREATE SET cluster.id = randomUUID(), cluster.createdAt = $now
                SET cluster.entityGroupKey = entityGroupKey,
                    cluster.stateSignature = stateSignature,
                    cluster.label = coalesce(entityName, '') + ' / ' + stateSignature,
                    cluster.memberCount = size(states),
                    cluster.representativeStateKey = representative.stateKey,
                    cluster.representativeDefinition = representative.definition,
                    cluster.fusionBatchId = $fusionBatchId,
                    cluster.updatedAt = $now
                WITH cluster, states
                UNWIND states AS state
                SET state.stateFusionStatus = 'fused',
                    state.stateClusterKey = cluster.stateClusterKey,
                    state.stateFusionUpdatedAt = $now
                MERGE (state)-[member:MEMBER_OF_STATE_CLUSTER]->(cluster)
                ON CREATE SET member.createdAt = $now
                SET member.updatedAt = $now
                RETURN count(DISTINCT cluster) AS groups, count(DISTINCT state) AS candidates
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "now", OffsetDateTime.now().toString()
                ));
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        return Map.of(
                "fusionBatchId", batchScoped ? fusionBatchId : "",
                "stateFusionGroups", intAt(row, 0),
                "stateFusionCandidates", intAt(row, 1)
        );
    }

    public Map<String, Object> materializeEntityStates(List<Map<String, Object>> templateHints, String fusionBatchId) {
        ensureSchema();
        boolean batchScoped = fusionBatchId != null && !fusionBatchId.isBlank();
        Map<String, List<String>> hintsByType = stateHintsByType(templateHints);
        execute("""
                MATCH (state:EntityState)
                WHERE coalesce(state.sourceKind, '') = 'derived_from_fact'
                  AND ($batchScoped = false OR state.fusionBatchId = $fusionBatchId)
                DETACH DELETE state
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        JsonNode root = query("""
                MATCH (entity:Entity)-[:HAS_STATE]->(sourceState:EntityState)
                MATCH (sourceState)-[:HAS_ATTRIBUTE_FACT|SUBJECT_OF]->(fact:Fact)
                WHERE ($batchScoped = false OR fact.fusionBatchId = $fusionBatchId OR sourceState.fusionBatchId = $fusionBatchId)
                  AND coalesce(fact.attributeStatus, '') <> 'rejected'
                  AND coalesce(fact.factKind, '') IN ['attribute_fact', 'attribute_candidate', 'relation_fact', 'transition_fact']
                OPTIONAL MATCH (fact)-[:OBJECT_OF]->(:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WITH entity, sourceState, fact, collect(DISTINCT objectEntity.canonicalName)[0] AS objectName
                RETURN entity.canonicalKey AS canonicalKey,
                       entity.canonicalName AS canonicalName,
                       entity.entityType AS entityType,
                       sourceState.stateKey AS sourceStateKey,
                       fact.factKey AS factKey,
                       coalesce(fact.factKind, '') AS factKind,
                       coalesce(fact.attributeStatus, '') AS attributeStatus,
                       coalesce(fact.attributeKey, fact.predicate, fact.relationType, '') AS predicate,
                       coalesce(fact.attributeValue, fact.value, fact.objectText, objectName, '') AS value,
                       coalesce(objectName, '') AS objectName,
                       coalesce(fact.statement, '') AS statement,
                       coalesce(fact.validFrom, '') AS validFrom,
                       coalesce(fact.validTo, '') AS validTo,
                       coalesce(fact.confidence, 0.0) AS confidence,
                       coalesce(fact.fusionBatchId, sourceState.fusionBatchId, '') AS sourceFusionBatchId
                LIMIT 20000
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return Map.of(
                    "stage", "state_materialization_completed",
                    "fusionBatchId", batchScoped ? fusionBatchId : "",
                    "scannedFacts", 0,
                    "materializedStates", 0
            );
        }
        int scanned = 0;
        int materialized = 0;
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 15) {
                continue;
            }
            scanned++;
            String canonicalKey = textAt(row, 0);
            String canonicalName = textAt(row, 1);
            String entityType = textAt(row, 2);
            String factKey = textAt(row, 4);
            Map<String, Object> signal = derivedStateSignal(
                    canonicalName,
                    entityType,
                    textAt(row, 5),
                    textAt(row, 6),
                    textAt(row, 7),
                    textAt(row, 8),
                    textAt(row, 9),
                    textAt(row, 10),
                    textAt(row, 11),
                    textAt(row, 12),
                    hintsByType
            );
            if (signal.isEmpty()) {
                continue;
            }
            String stateSignature = stringValue(signal.get("signature"));
            if (canonicalKey.isBlank() || factKey.isBlank() || stateSignature.isBlank()) {
                continue;
            }
            String stateKey = canonicalKey + ":state:" + stateSignature;
            execute("""
                    MATCH (entity:Entity {canonicalKey: $canonicalKey})
                    MATCH (fact:Fact {factKey: $factKey})
                    MERGE (state:EntityState {stateKey: $stateKey})
                    ON CREATE SET state.id = randomUUID(), state.createdAt = $now
                    SET state.name = $stateName,
                        state.definition = $definition,
                        state.entityType = $entityType,
                        state.validFrom = $validFrom,
                        state.validTo = $validTo,
                        state.stateKind = $stateKind,
                        state.sourceKind = 'derived_from_fact',
                        state.status = 'active',
                        state.confidence = $confidence,
                        state.fusionBatchId = $sourceFusionBatchId,
                        state.updatedAt = $now
                    MERGE (entity)-[:HAS_STATE]->(state)
                    MERGE (state)-[derived:DERIVED_FROM_FACT]->(fact)
                    ON CREATE SET derived.createdAt = $now
                    SET derived.reason = $reason,
                        derived.updatedAt = $now
                    FOREACH (_ IN CASE WHEN $attachAsAttribute = true THEN [1] ELSE [] END |
                        MERGE (state)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                        ON CREATE SET attrRel.createdAt = $now
                        SET attrRel.status = 'formal',
                            attrRel.updatedAt = $now
                    )
                    """,
                    Map.ofEntries(
                            Map.entry("canonicalKey", canonicalKey),
                            Map.entry("factKey", factKey),
                            Map.entry("stateKey", stateKey),
                            Map.entry("stateName", stringValue(signal.get("label"))),
                            Map.entry("definition", stringValue(signal.get("definition"))),
                            Map.entry("entityType", entityType),
                            Map.entry("validFrom", stringValue(signal.get("validFrom"))),
                            Map.entry("validTo", stringValue(signal.get("validTo"))),
                            Map.entry("stateKind", stringValue(signal.get("stateKind"))),
                            Map.entry("confidence", doubleValue(signal.get("confidence"))),
                            Map.entry("sourceFusionBatchId", blankTo(textAt(row, 14), batchScoped ? fusionBatchId : "")),
                            Map.entry("reason", stringValue(signal.get("reason"))),
                            Map.entry("attachAsAttribute", "attribute_fact".equals(normalizeFactKind(textAt(row, 5)))),
                            Map.entry("now", OffsetDateTime.now().toString())
                    ));
            materialized++;
            String stateKind = stringValue(signal.get("stateKind"));
            byKind.put(stateKind, byKind.getOrDefault(stateKind, 0) + 1);
        }
        return Map.of(
                "stage", "state_materialization_completed",
                "fusionBatchId", batchScoped ? fusionBatchId : "",
                "scannedFacts", scanned,
                "materializedStates", materialized,
                "materializedByKind", byKind
        );
    }

    public Map<String, Object> buildStateTransitions(String fusionBatchId) {
        ensureSchema();
        boolean batchScoped = fusionBatchId != null && !fusionBatchId.isBlank();
        Map<String, Object> stateFusion = fuseEntityStates(batchScoped ? fusionBatchId : null);
        execute("""
                MATCH (:EntityState)-[transition:EVOLVES_TO]->(:EntityState)
                WHERE $batchScoped = false OR transition.fusionBatchId = $fusionBatchId
                DELETE transition
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        JsonNode explicitRoot = query("""
                MATCH (subject:EntityState)-[:SUBJECT_OF]->(fact:Fact)-[:OBJECT_OF]->(object:EntityState)
                WHERE coalesce(fact.factKind, '') = 'transition_fact'
                  AND ($batchScoped = false OR fact.fusionBatchId = $fusionBatchId)
                  AND (
                      toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '升级'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '演化'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '演进'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '发展为'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '变更为'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '更名为'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '替代'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '取代'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '迁移'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '改版'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '修订'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS '废止'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS 'successor'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS 'replace'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS 'upgrade'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS 'migrate'
                      OR toLower(coalesce(fact.relationType, '') + ' ' + coalesce(fact.predicate, '')) CONTAINS 'revise'
                  )
                  AND NOT (
                      coalesce(fact.relationType, '') IN ['建设', '负责', '依托', '应用', '支持', '包含', '隶属', '参与建设', '联系人', '申报单位', '推进']
                      OR toLower(coalesce(fact.relationType, '')) IN ['build', 'construct', 'responsible_for', 'supports', 'depends_on', 'part_of', 'contains', 'uses', 'applies_to', 'implemented_by']
                  )
                MERGE (subject)-[transition:EVOLVES_TO]->(object)
                ON CREATE SET transition.id = randomUUID(), transition.createdAt = $now
                SET transition.transitionType = coalesce(fact.relationType, 'evolves_to'),
                    transition.reason = coalesce(fact.statement, '显式演化事实'),
                    transition.factKey = fact.factKey,
                    transition.inferred = false,
                    transition.confidence = coalesce(fact.confidence, 0.78),
                    transition.fusionBatchId = coalesce(fact.fusionBatchId, $fusionBatchId),
                    transition.updatedAt = $now
                RETURN count(transition) AS edges
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "now", OffsetDateTime.now().toString()
                ));
        int explicitEdges = intAt(explicitRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode inferredRoot = query("""
                MATCH (subjectEntity:Entity)-[:HAS_STATE]->(subject:EntityState)-[:DERIVED_FROM_FACT]->(fact:Fact)-[:OBJECT_OF]->(object:EntityState)<-[:HAS_STATE]-(objectEntity:Entity)
                WHERE ($batchScoped = false OR fact.fusionBatchId = $fusionBatchId OR subject.fusionBatchId = $fusionBatchId)
                  AND coalesce(subject.stateKind, '') IN ['phase_anchor', 'version_anchor', 'status_anchor']
                  AND coalesce(subject.sourceKind, '') = 'derived_from_fact'
                  AND subject.stateKey <> object.stateKey
                  AND subjectEntity.canonicalKey <> objectEntity.canonicalKey
                  AND NOT toLower(coalesce(objectEntity.entityType, '')) IN ['time', 'metricvalue', 'other']
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                  AND (
                      coalesce(fact.statement, '') CONTAINS '升级'
                      OR coalesce(fact.statement, '') CONTAINS '改版'
                      OR coalesce(fact.statement, '') CONTAINS '替代'
                      OR coalesce(fact.statement, '') CONTAINS '取代'
                      OR coalesce(fact.statement, '') CONTAINS '迁移'
                      OR coalesce(fact.statement, '') CONTAINS '更名'
                      OR coalesce(fact.statement, '') CONTAINS '发展为'
                      OR coalesce(fact.statement, '') CONTAINS '演化'
                      OR coalesce(fact.statement, '') CONTAINS '演进'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS 'upgrade'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS 'replace'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS 'migrate'
                      OR toLower(coalesce(fact.statement, '')) CONTAINS 'rename'
                  )
                MERGE (object)-[transition:EVOLVES_TO]->(subject)
                ON CREATE SET transition.id = randomUUID(), transition.createdAt = $now
                SET transition.transitionType = 'inferred_evolves_to',
                    transition.reason = coalesce(fact.statement, '强演化词推断'),
                    transition.factKey = fact.factKey,
                    transition.inferred = true,
                    transition.confidence = 0.72,
                    transition.fusionBatchId = coalesce(fact.fusionBatchId, subject.fusionBatchId, $fusionBatchId),
                    transition.updatedAt = $now
                RETURN count(transition) AS edges
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "now", OffsetDateTime.now().toString()
                ));
        int inferredEdges = intAt(inferredRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        JsonNode phaseRelationRoot = query("""
                MATCH (:EntityState)-[transition:EVOLVES_TO]->(phaseState:EntityState)
                MATCH (fact:Fact {factKey: transition.factKey})
                WHERE ($batchScoped = false OR transition.fusionBatchId = $fusionBatchId OR fact.fusionBatchId = $fusionBatchId)
                  AND coalesce(phaseState.stateKind, '') IN ['phase_anchor', 'version_anchor', 'status_anchor']
                  AND coalesce(fact.factKind, '') IN ['relation_fact', 'transition_fact']
                MERGE (phaseState)-[subjectRel:SUBJECT_OF]->(fact)
                ON CREATE SET subjectRel.createdAt = $now
                SET subjectRel.phaseMaterialized = true,
                    subjectRel.materializedReason = 'evolution_phase_relation',
                    subjectRel.updatedAt = $now
                RETURN count(DISTINCT fact) AS phaseRelationFacts
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "now", OffsetDateTime.now().toString()
                ));
        int phaseRelationFacts = intAt(phaseRelationRoot.path("results").path(0).path("data").path(0).path("row"), 0);
        return Map.of(
                "fusionBatchId", batchScoped ? fusionBatchId : "",
                "explicitTransitionEdges", explicitEdges,
                "inferredTransitionEdges", inferredEdges,
                "transitionEdges", explicitEdges + inferredEdges,
                "phaseRelationFacts", phaseRelationFacts,
                "stateFusionGroups", stateFusion.getOrDefault("stateFusionGroups", 0),
                "stateFusionCandidates", stateFusion.getOrDefault("stateFusionCandidates", 0)
        );
    }

    private int backfillEntityFusionKeys(String fusionBatchId) {
        boolean batchScoped = fusionBatchId != null && !fusionBatchId.isBlank();
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE (e.fusionKey IS NULL OR e.fusionKey = '')
                  AND ($batchScoped = false OR e.fusionBatchId = $fusionBatchId)
                RETURN e.canonicalKey AS canonicalKey,
                       e.canonicalName AS canonicalName,
                       e.entityType AS entityType
                LIMIT 10000
                """,
                Map.of(
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : ""
                ));
        int updated = 0;
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return 0;
        }
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 3) {
                continue;
            }
            String canonicalKey = textAt(row, 0);
            String canonicalName = textAt(row, 1);
            String entityType = textAt(row, 2);
            String key = fusionKey(entityType, blankTo(canonicalName, canonicalKey));
            if (key.isBlank()) {
                continue;
            }
            execute("""
                    MATCH (e:Entity {canonicalKey: $canonicalKey})
                    SET e.fusionKey = $fusionKey,
                        e.fusionStatus = coalesce(e.fusionStatus, 'candidate'),
                        e.fusionUpdatedAt = $now
                    """,
                    Map.of(
                            "canonicalKey", canonicalKey,
                            "fusionKey", key,
                            "now", OffsetDateTime.now().toString()
                    ));
            updated++;
        }
        return updated;
    }

    private void ensureSchema() {
        List<String> statements = List.of(
                "CREATE CONSTRAINT hmrag_entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.canonicalKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_state_key IF NOT EXISTS FOR (n:EntityState) REQUIRE n.stateKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_mention_key IF NOT EXISTS FOR (n:Mention) REQUIRE n.mentionKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_fact_key IF NOT EXISTS FOR (n:Fact) REQUIRE n.factKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_evidence_key IF NOT EXISTS FOR (n:Evidence) REQUIRE n.evidenceKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_chunk_ref_key IF NOT EXISTS FOR (n:ChunkRef) REQUIRE n.chunkId IS UNIQUE",
                "CREATE CONSTRAINT hmrag_document_key IF NOT EXISTS FOR (n:Document) REQUIRE n.docId IS UNIQUE",
                "CREATE CONSTRAINT hmrag_entity_description_key IF NOT EXISTS FOR (n:EntityDescription) REQUIRE n.descriptionKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_entity_cluster_key IF NOT EXISTS FOR (n:EntityCluster) REQUIRE n.fusionKey IS UNIQUE",
                "CREATE CONSTRAINT hmrag_state_cluster_key IF NOT EXISTS FOR (n:StateCluster) REQUIRE n.stateClusterKey IS UNIQUE"
        );
        for (String statement : statements) {
            execute(statement, Map.of());
        }
    }

    private void upsertChunkRef(String docId, String chunkId, Map<String, Object> evidence) {
        String safeChunkId = blankTo(chunkId, "unknown");
        execute("""
                MERGE (c:ChunkRef {chunkId: $chunkId})
                SET c.docId = $docId,
                    c.pageNo = $pageNo,
                    c.updatedAt = $now
                WITH c
                MATCH (d:Document {docId: $docId})
                MERGE (d)-[:HAS_CHUNK]->(c)
                """,
                Map.of(
                        "docId", docId,
                        "chunkId", safeChunkId,
                        "pageNo", evidence.get("pageNo") == null ? "" : String.valueOf(evidence.get("pageNo")),
                        "now", OffsetDateTime.now().toString()
                ));
    }

    private void execute(String cypher, Map<String, Object> params) {
        try {
            Map<String, Object> body = Map.of("statements", List.of(Map.of("statement", cypher, "parameters", params)));
            JsonNode root = sendCypher(body);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new IllegalStateException(errors.toString());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Graph store write failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode query(String cypher, Map<String, Object> params) {
        try {
            Map<String, Object> body = Map.of("statements", List.of(Map.of("statement", cypher, "parameters", params)));
            JsonNode root = sendCypher(body);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new IllegalStateException(errors.toString());
            }
            return root;
        } catch (Exception ex) {
            throw new IllegalStateException("Graph store query failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode sendCypher(Map<String, Object> body) throws IOException {
        AppProperties.KnowledgeGraph config = appProperties.knowledgeGraph();
        String baseUrl = config.storeBaseUrl().replaceAll("/+$", "");
        String database = config.storeDatabase() == null || config.storeDatabase().isBlank() ? "neo4j" : config.storeDatabase();
        String url = baseUrl + "/db/" + database + "/tx/commit";
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, config.storeConnectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, config.storeRequestTimeoutSeconds())).toMillis());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        if (config.storeUsername() != null && !config.storeUsername().isBlank()) {
            String raw = config.storeUsername() + ":" + Objects.toString(config.storePassword(), "");
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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

    private boolean isSourceArtifactEntity(Map<String, Object> entity, Map<String, Object> document) {
        String name = stringValue(entity.get("name"));
        String type = stringValue(entity.get("type"));
        if (name.isBlank()) {
            return true;
        }
        if (isHiddenGraphEntity(name, type)) {
            return true;
        }
        return sourceArtifactNames(document).contains(normalizeArtifactName(name));
    }

    private boolean isHiddenGraphEntity(String name, String type) {
        String rawName = stringValue(name);
        String rawType = stringValue(type);
        if (rawName.isBlank()) {
            return true;
        }
        if ("document".equals(normalize(rawType)) || "entitystate".equals(normalize(rawType))) {
            return true;
        }
        String lower = rawName.toLowerCase(Locale.ROOT);
        String compact = normalize(rawName).toLowerCase(Locale.ROOT);
        if (Set.of(
                "hyperlink", "pageref", "ref", "doi", "journal", "of", "and", "the", "to", "key",
                "vol", "no", "pp", "et", "al"
        ).contains(compact)) {
            return true;
        }
        return SOURCE_FILE_EXTENSION.matcher(lower).matches()
                || lower.startsWith("liushl_")
                || IMPORT_SUFFIX.matcher(rawName).matches();
    }

    private boolean isValueLikeGraphEntity(String name, String type) {
        String normalizedType = normalize(type);
        String normalizedName = normalize(name).replaceAll("\\s+", "");
        if (normalizedName.isBlank()) {
            return true;
        }
        if (Set.of("time", "metricvalue").contains(normalizedType)) {
            return true;
        }
        if (normalizedName.matches("^[0-9.]+$")) {
            return true;
        }
        return VALUE_LIKE_ENTITY_NAME.matcher(normalizedName).matches()
                || DATE_LIKE_ENTITY_NAME.matcher(normalizedName).matches();
    }

    private List<String> sourceArtifactNames(Map<String, Object> document) {
        List<String> names = new ArrayList<>();
        for (String key : List.of("title", "sourceFilename", "sourceFile", "relativePath")) {
            String value = stringValue(document.get(key));
            if (value.isBlank()) {
                continue;
            }
            addArtifactName(names, value);
            addArtifactName(names, fileNameFromPath(value));
        }
        return names;
    }

    private void addArtifactName(List<String> names, String value) {
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return;
        }
        names.add(normalizeArtifactName(raw));
        names.add(normalizeArtifactName(stripExtension(raw)));
    }

    private String fileNameFromPath(String value) {
        String raw = stringValue(value);
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        return slash >= 0 ? raw.substring(slash + 1) : raw;
    }

    private String stripExtension(String value) {
        return stringValue(value).replaceFirst("(?i)\\.(docx?|pdf|xlsx?|pptx?|txt|md)$", "");
    }

    private String normalizeArtifactName(String value) {
        String normalized = Normalizer.normalize(stringValue(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        return stripExtension(normalized)
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private String canonicalKey(String type, String name) {
        return normalize(type) + ":" + normalize(name);
    }

    private String fusionKey(String type, String name) {
        String normalized = normalize(name);
        String entityType = normalize(type);
        if (entityType.contains("org") || entityType.contains("organization") || entityType.contains("机构")) {
            normalized = normalized
                    .replace("有限责任公司", "")
                    .replace("股份有限公司", "")
                    .replace("有限公司", "")
                    .replace("集团公司", "")
                    .replace("集团", "")
                    .replace("公司", "")
                    .replace("co.,ltd.", "")
                    .replace("co.ltd.", "")
                    .replace("coltd", "")
                    .replace("ltd", "");
        }
        return normalized.replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
    }

    private Map<String, Object> graphNode(String id, String label, String type, String canonicalName, String fusionKey, int stateCount) {
        return graphNode(id, label, type, canonicalName, fusionKey, stateCount, 0);
    }

    private Map<String, Object> graphNode(String id, String label, String type, String canonicalName, String fusionKey, int stateCount, int transitionCount) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", blankTo(id, UUID.randomUUID().toString()));
        node.put("label", blankTo(label, "未命名实体"));
        node.put("type", blankTo(type, "Other"));
        node.put("canonicalName", canonicalName);
        node.put("fusionKey", fusionKey);
        node.put("stateCount", stateCount);
        node.put("transitionCount", transitionCount);
        node.put("hasEvolution", transitionCount > 0);
        return node;
    }

    private List<Map<String, Object>> buildAttributeCandidateClusters(List<Map<String, Object>> templateHints, int limit) {
        Map<String, List<String>> hintsByType = attributeHintsByType(templateHints);
        List<Map<String, Object>> candidates = readAttributeCandidates(Math.max(1000, limit * 200));
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String entityType = blankTo(stringValue(candidate.get("entityType")), "Other");
            String predicate = blankTo(stringValue(candidate.get("predicate")), "unknown");
            String value = stringValue(candidate.get("value"));
            String statement = stringValue(candidate.get("statement"));
            String valueShape = candidateValueShape(value);
            String pattern = candidateStatementPattern(statement);
            String clusterKey = normalize(entityType) + "|" + normalizeCandidatePredicate(predicate) + "|" + valueShape + "|" + pattern;
            Map<String, Object> cluster = grouped.computeIfAbsent(clusterKey, key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("clusterKey", key);
                created.put("entityType", entityType);
                created.put("predicate", predicate);
                created.put("normalizedPredicate", normalizeCandidatePredicate(predicate));
                created.put("valueShape", valueShape);
                created.put("statementPattern", pattern);
                created.put("facts", new ArrayList<Map<String, Object>>());
                created.put("sampleValues", new ArrayList<String>());
                created.put("sampleStatements", new ArrayList<String>());
                created.put("sampleFacts", new ArrayList<Map<String, Object>>());
                created.put("formalCount", 0);
                created.put("candidateCount", 0);
                return created;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) cluster.get("facts");
            facts.add(candidate);
            if ("attribute_fact".equals(stringValue(candidate.get("factKind")))) {
                cluster.put("formalCount", intObject(cluster.get("formalCount")) + 1);
            } else {
                cluster.put("candidateCount", intObject(cluster.get("candidateCount")) + 1);
            }
            @SuppressWarnings("unchecked")
            List<String> sampleValues = (List<String>) cluster.get("sampleValues");
            if (!value.isBlank() && sampleValues.size() < 8 && !sampleValues.contains(value)) {
                sampleValues.add(value);
            }
            @SuppressWarnings("unchecked")
            List<String> sampleStatements = (List<String>) cluster.get("sampleStatements");
            if (!statement.isBlank() && sampleStatements.size() < 5 && !sampleStatements.contains(statement)) {
                sampleStatements.add(statement);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sampleFacts = (List<Map<String, Object>>) cluster.get("sampleFacts");
            if (sampleFacts.size() < 5) {
                sampleFacts.add(Map.of(
                        "entityName", stringValue(candidate.get("entityName")),
                        "entityType", entityType,
                        "predicate", predicate,
                        "value", shorten(value, 160),
                        "statement", shorten(statement, 240),
                        "factKind", stringValue(candidate.get("factKind")),
                        "attributeStatus", stringValue(candidate.get("attributeStatus"))
                ));
            }
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        for (Map<String, Object> cluster : grouped.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) cluster.get("facts");
            String entityType = stringValue(cluster.get("entityType"));
            String predicate = stringValue(cluster.get("predicate"));
            String valueShape = stringValue(cluster.get("valueShape"));
            String pattern = stringValue(cluster.get("statementPattern"));
            Map<String, Object> decision = recommendCandidateCluster(
                    entityType,
                    predicate,
                    valueShape,
                    pattern,
                    hintsByType,
                    intObject(cluster.get("formalCount")),
                    facts
            );
            cluster.put("factCount", facts.size());
            cluster.put("entityCount", facts.stream().map(fact -> stringValue(fact.get("entityId"))).filter(value -> !value.isBlank()).distinct().count());
            cluster.put("documentCount", facts.stream().map(fact -> stringValue(fact.get("docId"))).filter(value -> !value.isBlank()).distinct().count());
            cluster.put("decision", decision.get("decision"));
            cluster.put("targetAttributeKey", decision.get("targetAttributeKey"));
            cluster.put("confidence", Math.min(0.97d, doubleValue(decision.get("confidence")) + Math.min(0.10d, Math.log10(Math.max(1, facts.size())) * 0.04d)));
            cluster.put("reason", decision.get("reason"));
            clusters.add(cluster);
        }
        clusters.sort((left, right) -> {
            int byDecision = Boolean.compare("attribute_fact".equals(stringValue(right.get("decision"))), "attribute_fact".equals(stringValue(left.get("decision"))));
            if (byDecision != 0) {
                return byDecision;
            }
            int byConfidence = Double.compare(doubleValue(right.get("confidence")), doubleValue(left.get("confidence")));
            if (byConfidence != 0) {
                return byConfidence;
            }
            return Integer.compare(intObject(right.get("factCount")), intObject(left.get("factCount")));
        });
        return clusters.stream().limit(limit).toList();
    }

    private void recoverAttributeCandidateLinks() {
        execute("""
                MATCH (fact:Fact)
                WHERE coalesce(fact.factKind, '') = 'attribute_candidate'
                  AND NOT (:EntityState)-[:HAS_ATTRIBUTE_FACT]->(fact)
                  AND fact.factKey CONTAINS ':fact:'
                WITH fact, split(fact.factKey, ':fact:')[0] AS stateKey
                MATCH (state:EntityState {stateKey: stateKey})
                MERGE (state)-[attrRel:HAS_ATTRIBUTE_FACT]->(fact)
                ON CREATE SET attrRel.createdAt = $now
                SET attrRel.status = 'candidate',
                    attrRel.updatedAt = $now
                """, Map.of("now", OffsetDateTime.now().toString()));
    }

    private List<Map<String, Object>> readAttributeCandidates(int limit) {
        JsonNode root = query("""
                MATCH (entity:Entity)-[:HAS_STATE]->(state:EntityState)-[:SUBJECT_OF|HAS_ATTRIBUTE_FACT]->(fact:Fact)
                WHERE coalesce(fact.factKind, '') IN ['attribute_candidate', 'attribute_fact']
                  AND coalesce(fact.attributeStatus, 'candidate') <> 'rejected'
                  AND coalesce(fact.governanceStage, '') <> 'structure_enhancement'
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                WITH DISTINCT entity, state, fact,
                     collect(DISTINCT evidence.docId)[0] AS docId,
                     collect(DISTINCT evidence.chunkId)[0] AS chunkId
                RETURN
                    entity.canonicalKey AS entityId,
                    entity.canonicalName AS entityName,
                    entity.entityType AS entityType,
                    state.stateKey AS stateKey,
                    fact.factKey AS factKey,
                    coalesce(fact.attributeKey, fact.relationType, fact.predicate, '') AS predicate,
                    coalesce(fact.attributeValue, fact.value, fact.objectText, '') AS value,
                    coalesce(fact.statement, '') AS statement,
                    coalesce(fact.governanceReason, '') AS governanceReason,
                    coalesce(fact.factKind, '') AS factKind,
                    coalesce(fact.attributeStatus, '') AS attributeStatus,
                    docId,
                    chunkId
                LIMIT $limit
                """, Map.of("limit", Math.max(1, limit)));
        JsonNode data = root.path("results").path(0).path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode rowNode : data) {
            JsonNode row = rowNode.path("row");
            if (!row.isArray() || row.size() < 13) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("entityId", textAt(row, 0));
            candidate.put("entityName", textAt(row, 1));
            candidate.put("entityType", textAt(row, 2));
            candidate.put("stateKey", textAt(row, 3));
            candidate.put("factKey", textAt(row, 4));
            candidate.put("predicate", textAt(row, 5));
            candidate.put("value", textAt(row, 6));
            candidate.put("statement", textAt(row, 7));
            candidate.put("governanceReason", textAt(row, 8));
            candidate.put("factKind", textAt(row, 9));
            candidate.put("attributeStatus", textAt(row, 10));
            candidate.put("docId", textAt(row, 11));
            candidate.put("chunkId", textAt(row, 12));
            candidates.add(candidate);
        }
        return candidates;
    }

    private Map<String, List<String>> attributeHintsByType(List<Map<String, Object>> templateHints) {
        Map<String, List<String>> hintsByType = new LinkedHashMap<>();
        if (templateHints == null) {
            return hintsByType;
        }
        for (Map<String, Object> hintRow : templateHints) {
            String entityType = stringValue(hintRow.get("entityType"));
            String hint = stringValue(hintRow.get("hint"));
            if (entityType.isBlank() || hint.isBlank()) {
                continue;
            }
            hintsByType.computeIfAbsent(normalize(entityType), key -> new ArrayList<>()).add(hint);
        }
        return hintsByType;
    }

    private Map<String, Object> recommendCandidateCluster(
            String entityType,
            String predicate,
            String valueShape,
            String statementPattern,
            Map<String, List<String>> hintsByType,
            int formalCount,
            List<Map<String, Object>> facts
    ) {
        String normalizedType = normalize(entityType);
        String normalizedPredicate = normalizeCandidatePredicate(predicate);
        List<String> hints = hintsByType.getOrDefault(normalizedType, List.of());
        String sampleText = candidateClusterText(facts);
        if (Set.of("time", "metricvalue").contains(normalizedType)) {
            return Map.of("decision", "keep_candidate", "targetAttributeKey", "", "confidence", 0.62d, "reason", "value_entity_not_attribute_subject");
        }
        if (isEmptyRelatedCandidate(normalizedPredicate, valueShape)) {
            return Map.of("decision", "discard_or_relation", "targetAttributeKey", "", "confidence", 0.90d, "reason", "empty_related_candidate");
        }
        if ("structure_reference".equals(statementPattern) || "field_list".equals(statementPattern)) {
            return Map.of("decision", "discard_or_relation", "targetAttributeKey", "", "confidence", 0.90d, "reason", statementPattern);
        }
        if (isFormFieldNoise(normalizedPredicate, sampleText, valueShape)) {
            return Map.of("decision", "discard_or_relation", "targetAttributeKey", "", "confidence", 0.88d, "reason", "form_field_noise");
        }
        if (formalCount > 0) {
            String directFormalHint = matchTemplateHint(hints, normalizedPredicate);
            return Map.of(
                    "decision", "attribute_fact",
                    "targetAttributeKey", directFormalHint.isBlank() ? predicate : directFormalHint,
                    "confidence", 0.86d,
                    "reason", "existing_formal_attribute_review"
            );
        }
        String directHint = matchTemplateHint(hints, normalizedPredicate);
        if (!directHint.isBlank()) {
            return Map.of("decision", "attribute_fact", "targetAttributeKey", directHint, "confidence", 0.84d, "reason", "template_alias_cluster");
        }
        String semanticHint = matchSemanticAttributeHint(hints, normalizedPredicate, valueShape);
        if (!semanticHint.isBlank()) {
            return Map.of("decision", "attribute_fact", "targetAttributeKey", semanticHint, "confidence", 0.76d, "reason", "semantic_value_shape_cluster");
        }
        String genericAttributeKey = genericAttributeKey(normalizedType, normalizedPredicate, valueShape, sampleText);
        if (!genericAttributeKey.isBlank()) {
            return Map.of(
                    "decision", "attribute_fact",
                    "targetAttributeKey", genericAttributeKey,
                    "confidence", genericAttributeConfidence(valueShape, normalizedPredicate),
                    "reason", "generic_typed_attribute_rule"
            );
        }
        String role = candidatePredicateRole(normalizedPredicate);
        if ("generic_action".equals(role)) {
            return Map.of("decision", "relation_or_event", "targetAttributeKey", "", "confidence", 0.88d, "reason", "action_predicate");
        }
        if ("scalar".equals(valueShape) || "date".equals(valueShape) || "money".equals(valueShape) || "percent".equals(valueShape)) {
            return Map.of("decision", "keep_candidate", "targetAttributeKey", "", "confidence", 0.58d, "reason", "scalar_without_template");
        }
        return Map.of("decision", "keep_candidate", "targetAttributeKey", "", "confidence", 0.50d, "reason", "insufficient_template_signal");
    }

    private String matchTemplateHint(List<String> hints, String normalizedPredicate) {
        if (normalizedPredicate.isBlank()) {
            return "";
        }
        for (String hint : hints) {
            String normalizedHint = normalizeCandidatePredicate(hint);
            if (normalizedHint.isBlank()) {
                continue;
            }
            if (normalizedPredicate.equals(normalizedHint)
                    || normalizedPredicate.contains(normalizedHint)
                    || normalizedHint.contains(normalizedPredicate)) {
                return hint;
            }
        }
        return "";
    }

    private String matchSemanticAttributeHint(List<String> hints, String normalizedPredicate, String valueShape) {
        List<List<String>> groups = List.of(
                List.of("经费", "费用", "金额", "预算", "成本", "单价", "报价"),
                List.of("时间", "日期", "期限", "周期", "建设时间", "发布时间", "生效日期"),
                List.of("名称", "题名", "标题", "简称", "姓名"),
                List.of("数量", "规模", "人数", "项数"),
                List.of("状态", "阶段", "进度"),
                List.of("版本", "编号", "代码"),
                List.of("单位", "机构", "组织", "部门"),
                List.of("范围", "覆盖范围", "对象"),
                List.of("功能", "能力", "接口", "服务")
        );
        for (List<String> group : groups) {
            boolean predicateHit = group.stream().map(this::normalizeCandidatePredicate).anyMatch(normalizedPredicate::contains);
            if (!predicateHit) {
                continue;
            }
            if (("money".equals(valueShape) || normalizedPredicate.contains("费用") || normalizedPredicate.contains("金额"))
                    && group.stream().noneMatch(value -> normalize(value).contains("经费") || normalize(value).contains("金额") || normalize(value).contains("费用"))) {
                continue;
            }
            for (String hint : hints) {
                String normalizedHint = normalizeCandidatePredicate(hint);
                if (group.stream().map(this::normalizeCandidatePredicate).anyMatch(alias -> normalizedHint.contains(alias) || alias.contains(normalizedHint))) {
                    return hint;
                }
            }
        }
        return "";
    }

    private String candidateClusterText(List<Map<String, Object>> facts) {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map<String, Object> fact : facts) {
            if (count++ >= 20) {
                break;
            }
            builder.append(' ')
                    .append(stringValue(fact.get("entityName"))).append(' ')
                    .append(stringValue(fact.get("entityType"))).append(' ')
                    .append(stringValue(fact.get("predicate"))).append(' ')
                    .append(stringValue(fact.get("value"))).append(' ')
                    .append(stringValue(fact.get("statement")));
        }
        return normalize(builder.toString());
    }

    private boolean isEmptyRelatedCandidate(String normalizedPredicate, String valueShape) {
        if (!"empty".equals(valueShape)) {
            return false;
        }
        return normalizedPredicate.isBlank()
                || "relatedto".equals(normalizedPredicate)
                || "相关".equals(normalizedPredicate)
                || "关联".equals(normalizedPredicate);
    }

    private boolean isFormFieldNoise(String normalizedPredicate, String sampleText, String valueShape) {
        if (!"empty".equals(valueShape)) {
            return false;
        }
        List<String> fieldSignals = List.of("字段", "表头", "编号", "代码", "序号", "备注", "联系方式", "联系电话", "电子邮箱", "地址");
        return fieldSignals.stream().anyMatch(normalizedPredicate::contains)
                || (sampleText.contains("字段") && sampleText.contains("记录"));
    }

    private String genericAttributeKey(String normalizedType, String normalizedPredicate, String valueShape, String sampleText) {
        if (normalizedPredicate.isBlank()) {
            return "";
        }
        if (("money".equals(valueShape) || "date".equals(valueShape))
                && "generic_action".equals(candidatePredicateRole(normalizedPredicate))) {
            return "";
        }
        if ("date".equals(valueShape)) {
            if (containsAny(normalizedPredicate, "起始", "开始", "立项", "启动")) {
                return "起始时间";
            }
            if (containsAny(normalizedPredicate, "终止", "结束", "截止", "完成")) {
                return "结束时间";
            }
            if (containsAny(normalizedPredicate, "发布", "印发", "生效", "制定", "修订")) {
                return "时间";
            }
            return "时间";
        }
        if ("money".equals(valueShape)) {
            if (containsAny(normalizedPredicate, "成本")) {
                return "成本";
            }
            if (containsAny(normalizedPredicate, "费用", "收费", "支出", "单价")) {
                return "费用";
            }
            if (containsAny(normalizedPredicate, "预算", "经费", "资金")) {
                return "经费";
            }
            return "金额";
        }
        if ("percent".equals(valueShape)
                || ("scalar".equals(valueShape) && ("indicator".equals(normalizedType) || containsAny(normalizedPredicate, "指标", "达到", "不低于", "不少于", "小于", "大于", "≥", "≤")))) {
            return "指标值";
        }
        if ("scalar".equals(valueShape)) {
            if (containsAny(normalizedPredicate, "成本")) {
                return "成本";
            }
            if (containsAny(normalizedPredicate, "费用", "收费", "支出", "单价")) {
                return "费用";
            }
            if (containsAny(normalizedPredicate, "预算", "经费", "资金")) {
                return "经费";
            }
            if (containsAny(normalizedPredicate, "金额")) {
                return "金额";
            }
            if (containsAny(normalizedPredicate, "数量", "人数", "次数", "规模", "篇数", "项数")) {
                return "数量";
            }
            if (containsAny(normalizedPredicate, "周期", "时长", "期限", "响应", "延迟", "耗时")) {
                return "周期";
            }
            if (containsAny(normalizedPredicate, "版本")) {
                return "版本";
            }
            return "";
        }
        if ("short_text".equals(valueShape) || "long_text".equals(valueShape)) {
            if (containsAny(normalizedPredicate, "名称", "题名", "标题")) {
                return "名称";
            }
            if (containsAny(normalizedPredicate, "简称")) {
                return "简称";
            }
            if (containsAny(normalizedPredicate, "类型", "类别", "分类")) {
                return "类型";
            }
            if (containsAny(normalizedPredicate, "状态", "阶段", "进度")) {
                return "状态";
            }
            if (containsAny(normalizedPredicate, "版本")) {
                return "版本";
            }
            if (containsAny(normalizedPredicate, "范围", "对象")) {
                return "范围";
            }
            if (containsAny(normalizedPredicate, "主题", "方向")) {
                return "主题";
            }
            if (containsAny(normalizedPredicate, "描述", "说明", "定义", "含义")) {
                return "描述";
            }
        }
        return "";
    }

    private double genericAttributeConfidence(String valueShape, String normalizedPredicate) {
        if ("money".equals(valueShape) || "date".equals(valueShape) || "percent".equals(valueShape)) {
            return 0.90d;
        }
        if ("scalar".equals(valueShape)) {
            return 0.88d;
        }
        if (containsAny(normalizedPredicate, "名称", "题名", "标题", "类型", "类别", "分类", "状态", "阶段", "版本")) {
            return 0.87d;
        }
        return 0.85d;
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(normalize(fragment))) {
                return true;
            }
        }
        return false;
    }

    private String candidatePredicateRole(String normalizedPredicate) {
        if (normalizedPredicate.isBlank()) {
            return "unknown";
        }
        List<String> actionSignals = List.of(
                "召开", "提出", "印发", "颁布", "取消", "建设", "提升", "提高", "探索", "实现", "开展", "完成",
                "负责", "参与", "支持", "应用", "包含", "按照", "根据", "必须", "提供", "主持", "贯彻", "增强",
                "取得", "招收", "撤销", "增列", "准备", "遵循", "服务", "整合", "生成", "总结", "推送", "涉及",
                "采用", "满足", "能够", "触发", "支付", "参照", "使用", "部署", "保留", "签订", "回答",
                "全免", "到期", "低于", "高于", "relatedto"
        );
        return actionSignals.stream().anyMatch(normalizedPredicate::contains) ? "generic_action" : "unknown";
    }

    private String candidateValueShape(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "empty";
        }
        boolean hasMoneyAmount = normalized.matches(".*[0-9]+(\\.[0-9]+)?(元|万元|亿元).*")
                || normalized.matches(".*人民币[0-9]+(\\.[0-9]+)?(元|万元|亿元).*");
        if (hasMoneyAmount) {
            return "money";
        }
        if (normalized.matches("^[0-9.]+(%|％)$")) {
            return "percent";
        }
        boolean shortTemporalValue = normalized.length() <= 24
                && (normalized.matches("^(19|20)[0-9]{2}$")
                || normalized.matches("^(19|20)[0-9]{2}[年./．-][0-9]{1,2}([月./．-][0-9]{1,2})?日?$")
                || normalized.matches("^(第[一二三四五六七八九十0-9]+阶段|一期|二期|三期|试点期|建设期|质保期)$"));
        if (shortTemporalValue) {
            return "date";
        }
        if (normalized.matches("^[0-9.]+([个项名所次篇位人万亿年月日小时分钟秒]+)?$")) {
            return "scalar";
        }
        return normalized.length() <= 32 ? "short_text" : "long_text";
    }

    private String candidateStatementPattern(String statement) {
        String normalized = normalize(statement);
        if (normalized.contains("章节") && normalized.contains("涉及")) {
            return "structure_reference";
        }
        if (normalized.contains("每条记录") || normalized.contains("字段") || (normalized.contains("列表") && normalized.contains("包含"))) {
            return "field_list";
        }
        if (statement.contains("：") || statement.contains(":")) {
            return "key_value";
        }
        if (normalized.contains("为") || normalized.contains("是")) {
            return "copula";
        }
        return "free_text";
    }

    private String normalizeCandidatePredicate(String value) {
        String normalized = normalize(value)
                .replaceAll("[()（）\\[\\]【】《》<>“”\"'、，,。.;；:：\\-_/\\\\|]", "");
        for (String suffix : List.of("为", "是", "为：", "为:", "要求为")) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                normalized = normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    private String cleanGovernedAttributeValue(String attributeKey, String statement, String rawValue) {
        String value = stringValue(rawValue);
        String key = normalize(attributeKey);
        String text = stringValue(statement);
        for (String separator : List.of("：", ":")) {
            if (text.contains(separator)) {
                String[] parts = text.split(Pattern.quote(separator), 2);
                if (parts.length == 2 && normalize(parts[0]).contains(key) && !parts[1].trim().isBlank()) {
                    return parts[1].trim();
                }
            }
        }
        return value;
    }

    private String textAt(JsonNode row, int index) {
        JsonNode value = row.path(index);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private int intAt(JsonNode row, int index) {
        JsonNode value = row.path(index);
        return value.isNumber() ? value.asInt() : 0;
    }

    private Object jsonScalar(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(jsonScalar(item));
            }
            return items;
        }
        if (value.isObject()) {
            return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
        }
        return value.asText("");
    }

    private int intObject(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<String> stringArrayAt(JsonNode row, int index, int limit) {
        JsonNode value = row.path(index);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (items.size() >= limit) {
                break;
            }
            if (!item.isNull() && !item.asText("").isBlank()) {
                items.add(item.asText());
            }
        }
        return items;
    }

    String stateKey(String canonicalKey, Map<String, Object> entity) {
        String validFrom = normalize(stringValue(entity.get("validFrom")));
        String validTo = normalize(stringValue(entity.get("validTo")));
        if (validFrom.isBlank() && validTo.isBlank()) {
            return canonicalKey + ":state:default";
        }
        return canonicalKey + ":state:" + validFrom + ":" + validTo;
    }

    String descriptionKey(String canonicalKey, String docId, String mentionId, String definition) {
        String normalizedDefinition = normalize(definition);
        if (normalizedDefinition.isBlank()) {
            return "";
        }
        return canonicalKey + ":description:" + normalize(docId) + ":" + normalize(mentionId) + ":" + Integer.toHexString(normalizedDefinition.hashCode());
    }

    private boolean hasTemporalScope(Map<String, Object> entity) {
        return !normalize(stringValue(entity.get("validFrom"))).isBlank()
                || !normalize(stringValue(entity.get("validTo"))).isBlank();
    }

    private String factKey(String subjectState, String objectState, Map<String, Object> relation) {
        String factKind = normalizeFactKind(stringValue(relation.get("factKind")));
        String predicate = blankTo(blankTo(stringValue(relation.get("predicate")), stringValue(relation.get("relationType"))), "related_to");
        String objectPart = "attribute_fact".equals(factKind)
                ? firstNonBlank(stringValue(relation.get("value")), stringValue(relation.get("object")), stringValue(relation.get("attributeValue")), stringValue(relation.get("statement")))
                : stringValue(objectState).isBlank()
                ? firstNonBlank(stringValue(relation.get("value")), stringValue(relation.get("object")), stringValue(relation.get("attributeValue")), stringValue(relation.get("statement")))
                : stringValue(objectState);
        return subjectState + ":fact:" + factKind + ":" + normalize(predicate) + ":"
                + normalize(objectPart) + ":" + normalize(stringValue(relation.get("validFrom"))) + ":" + normalize(stringValue(relation.get("validTo")));
    }

    private String normalizeFactKind(String factKind) {
        String normalized = normalize(factKind).replace("-", "_");
        return switch (normalized) {
            case "attribute", "attr", "property", "attribute_fact" -> "attribute_fact";
            case "transition", "evolution", "transition_fact" -> "transition_fact";
            case "identity", "alias", "same_as", "identity_fact" -> "identity_fact";
            case "rule", "rule_fact" -> "rule_fact";
            case "claim", "claim_fact" -> "claim_fact";
            case "task", "task_fact" -> "task_fact";
            case "event", "event_fact" -> "event_fact";
            default -> "relation_fact";
        };
    }

    private boolean isEvolutionRelation(String relationType, String statement) {
        String relation = normalize(blankTo(relationType, ""));
        String text = normalize(blankTo(relationType, "") + " " + blankTo(statement, ""));
        if (relation.isBlank() && text.isBlank()) {
            return false;
        }
        List<String> blockedRelations = List.of(
                "建设", "负责", "依托", "应用", "支持", "包含", "隶属", "参与建设", "联系人", "申报单位", "推进",
                "build", "construct", "responsiblefor", "support", "supports", "dependson", "partof",
                "contains", "use", "uses", "appliesto", "implementedby", "isimplementedby"
        );
        if (blockedRelations.stream().anyMatch(relation::equals)) {
            return false;
        }
        List<String> allowedSignals = List.of(
                "升级", "演化", "演进", "发展为", "变更为", "更名为", "替代", "取代", "迁移", "改版", "修订",
                "废止", "继承", "变为", "转为", "转换为", "successor", "replace", "replaces", "replacedby",
                "upgrade", "upgraded", "migrate", "migrated", "revise", "revision", "rename", "renamed",
                "supersede", "superseded", "evolv"
        );
        boolean relationHasSignal = allowedSignals.stream().anyMatch(relation::contains);
        boolean relationIsGeneric = relation.isBlank() || "relatedto".equals(relation) || "关系".equals(relation);
        return relationHasSignal || (relationIsGeneric && allowedSignals.stream().anyMatch(text::contains));
    }

    private String normalize(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }

    private List<String> graphSearchTokens(String queryText) {
        String normalized = queryText == null ? "" : Normalizer.normalize(queryText.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> tokens = new LinkedHashMap<>();
        for (String raw : TOKEN_SPLITTER.split(normalized)) {
            String token = raw == null ? "" : raw.trim();
            if (token.length() >= 2 && token.length() <= 48) {
                tokens.put(token, true);
            }
        }
        String compact = normalized.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
        if (compact.length() >= 2 && compact.length() <= 64) {
            tokens.put(compact, true);
        }
        if (tokens.isEmpty() && normalized.length() >= 2) {
            tokens.put(normalized.substring(0, Math.min(normalized.length(), 64)), true);
        }
        return tokens.keySet().stream().limit(16).toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double doubleValue(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? 0.0d : Double.parseDouble(String.valueOf(raw));
        } catch (Exception ex) {
            return 0.0d;
        }
    }

    private String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
