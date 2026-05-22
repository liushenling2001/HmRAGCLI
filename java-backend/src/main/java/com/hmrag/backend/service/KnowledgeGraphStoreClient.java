package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeGraphStoreClient {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,，。；;、！？!？/\\\\|()（）【】\\[\\]<>《》\"'“”]+");

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
                OPTIONAL MATCH (fact)-[:SUPPORTED_BY]->(evidence:Evidence)
                OPTIONAL MATCH (subjectEntity)-[:MEMBER_OF]->(subjectCluster:EntityCluster)
                OPTIONAL MATCH (objectEntity)-[:MEMBER_OF]->(objectCluster:EntityCluster)
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
                    count(DISTINCT subject) AS sourceStateCount,
                    count(DISTINCT object) AS targetStateCount,
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
                if (!row.isArray() || row.size() < 18) {
                    continue;
                }
                String sourceId = textAt(row, 0);
                String sourceName = textAt(row, 1);
                String sourceType = textAt(row, 2);
                String targetId = textAt(row, 3);
                String targetName = textAt(row, 4);
                String targetType = textAt(row, 5);
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
                List<String> docIds = new ArrayList<>();
                JsonNode docIdNode = row.path(17);
                if (docIdNode.isArray()) {
                    for (JsonNode item : docIdNode) {
                        if (!item.isNull() && !item.asText("").isBlank()) {
                            docIds.add(item.asText());
                        }
                    }
                }
                nodes.putIfAbsent(sourceId, graphNode(sourceId, sourceName, sourceType, subjectCanonicalName, subjectFusionKey, sourceStateCount));
                nodes.putIfAbsent(targetId, graphNode(targetId, targetName, targetType, objectCanonicalName, objectFusionKey, targetStateCount));
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
                    RETURN count(s) AS states
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
                RETURN entities, states, mentions, facts, evidence, chunks, clusters
                """, Map.of());
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        return Map.of(
                "entities", intAt(row, 0),
                "states", intAt(row, 1),
                "mentions", intAt(row, 2),
                "facts", intAt(row, 3),
                "evidence", intAt(row, 4),
                "chunks", intAt(row, 5),
                "clusters", intAt(row, 6)
        );
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
        List<Map<String, Object>> relations = listOfMaps(localGraph.get("relations"));
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
                        s.definition = $definition,
                        s.validFrom = $validFrom,
                        s.validTo = $validTo,
                        s.status = 'active',
                        s.fusionBatchId = $fusionBatchId,
                        s.confidence = $confidence,
                        s.updatedAt = $now
                    MERGE (e)-[:HAS_STATE]->(s)
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
                    WITH m
                    MERGE (c:ChunkRef {chunkId: $chunkId})
                    MERGE (m)-[:APPEARS_IN]->(c)
                    """,
                    Map.ofEntries(
                            Map.entry("canonicalKey", canonicalKey),
                            Map.entry("fusionKey", fusionKey),
                            Map.entry("stateKey", stateKey),
                            Map.entry("mentionKey", mentionKey),
                            Map.entry("entityId", UUID.randomUUID().toString()),
                            Map.entry("stateId", UUID.randomUUID().toString()),
                            Map.entry("mentionUuid", UUID.randomUUID().toString()),
                            Map.entry("name", name),
                            Map.entry("type", type),
                            Map.entry("definition", stringValue(entity.get("definition"))),
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

        int relationCount = 0;
        for (Map<String, Object> relation : relations) {
            String subjectState = mentionToStateKey.get(stringValue(relation.get("subjectMentionId")));
            String objectState = mentionToStateKey.get(stringValue(relation.get("objectMentionId")));
            if (subjectState == null || objectState == null) {
                continue;
            }
            String relationType = blankTo(stringValue(relation.get("relationType")), "related_to");
            String factKey = factKey(subjectState, objectState, relation);
            String evidenceKey = docId + ":rel:" + relationCount;
            String chunkId = blankTo(stringValue(relation.get("chunkId")), "unknown");
            upsertChunkRef(docId, chunkId, relation);
            execute("""
                    MATCH (subject:EntityState {stateKey: $subjectState})
                    MATCH (object:EntityState {stateKey: $objectState})
                    MERGE (f:Fact {factKey: $factKey})
                    ON CREATE SET f.id = $factId, f.createdAt = $now
                    SET f.relationType = $relationType,
                        f.statement = $statement,
                        f.validFrom = $validFrom,
                        f.validTo = $validTo,
                        f.status = 'active',
                        f.fusionStatus = 'merged',
                        f.confidence = $confidence,
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
                    Map.ofEntries(
                            Map.entry("subjectState", subjectState),
                            Map.entry("objectState", objectState),
                            Map.entry("factKey", factKey),
                            Map.entry("factId", UUID.randomUUID().toString()),
                            Map.entry("relationType", relationType),
                            Map.entry("statement", stringValue(relation.get("statement"))),
                            Map.entry("validFrom", stringValue(relation.get("validFrom"))),
                            Map.entry("validTo", stringValue(relation.get("validTo"))),
                            Map.entry("confidence", doubleValue(relation.get("confidence"))),
                            Map.entry("evidenceKey", evidenceKey),
                            Map.entry("evidenceId", UUID.randomUUID().toString()),
                            Map.entry("docId", docId),
                            Map.entry("chunkId", chunkId),
                            Map.entry("knowledgeUnitId", stringValue(relation.get("knowledgeUnitId"))),
                            Map.entry("sourceSpan", stringValue(relation.get("sourceSpan"))),
                            Map.entry("buildRunId", buildRunId),
                            Map.entry("fusionBatchId", fusionBatchId),
                            Map.entry("now", OffsetDateTime.now().toString())
                    ));
            relationCount++;
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
        summary.put("relations", relationCount);
        Map<String, Object> metadata = mapValue(localGraph.get("metadata"));
        summary.put("chunkCount", metadata.getOrDefault("chunkCount", 0));
        summary.put("extractedChunkCount", metadata.getOrDefault("extractedChunkCount", 0));
        summary.put("batchCount", metadata.getOrDefault("batchCount", 0));
        summary.put("failedBatchCount", metadata.getOrDefault("failedBatchCount", 0));
        summary.put("chunkBatchSize", metadata.getOrDefault("chunkBatchSize", 0));
        return summary;
    }

    private void clearDocumentGraph(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        Map<String, Object> params = Map.of("docId", docId, "now", OffsetDateTime.now().toString());
        execute("""
                MATCH (d:Document {docId: $docId})-[r:HAS_CHUNK]->(:ChunkRef)
                DELETE r
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
                MATCH (f:Fact)
                WHERE NOT (f)-[:SUPPORTED_BY]->(:Evidence)
                DETACH DELETE f
                """, params);
        execute("""
                MATCH (s:EntityState)
                WHERE NOT (:Mention)-[:REFERS_TO]->(s)
                  AND NOT (s)-[:SUBJECT_OF]->(:Fact)
                  AND NOT (:Fact)-[:OBJECT_OF]->(s)
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
        JsonNode root = query("""
                MATCH (e:Entity)
                WHERE e.fusionKey IS NOT NULL
                  AND e.fusionKey <> ''
                  AND size(e.fusionKey) >= $minNameLength
                WITH e.entityType AS entityType, e.fusionKey AS fusionKey, collect(e) AS entities
                WHERE size(entities) > 1
                  AND size(entities) <= $maxGroupSize
                  AND any(entity IN entities WHERE coalesce(entity.fusionStatus, 'candidate') <> 'fused')
                  AND ($batchScoped = false OR any(entity IN entities WHERE entity.fusionBatchId = $fusionBatchId AND coalesce(entity.fusionStatus, 'candidate') <> 'fused'))
                WITH entityType, fusionKey, entities,
                     reduce(best = head(entities), candidate IN entities |
                         CASE
                             WHEN size(coalesce(candidate.canonicalName, '')) > size(coalesce(best.canonicalName, '')) THEN candidate
                             ELSE best
                         END
                     ) AS canonical
                MERGE (cluster:EntityCluster {fusionKey: entityType + ':' + fusionKey})
                ON CREATE SET cluster.id = randomUUID(), cluster.createdAt = $now
                SET cluster.entityType = entityType,
                    cluster.canonicalName = canonical.canonicalName,
                    cluster.canonicalKey = canonical.canonicalKey,
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
                    same.reason = 'normalized name/type fusion',
                    same.updatedAt = $now
                RETURN count(DISTINCT cluster) AS groups, count(same) AS sameAsEdges
                """,
                Map.of(
                        "minNameLength", minNameLength,
                        "maxGroupSize", maxGroupSize,
                        "createSameAsEdges", createSameAsEdges,
                        "batchScoped", batchScoped,
                        "fusionBatchId", batchScoped ? fusionBatchId : "",
                        "mode", mode,
                        "now", OffsetDateTime.now().toString()
                ));
        JsonNode row = root.path("results").path(0).path("data").path(0).path("row");
        int groups = row.isArray() && row.size() > 0 && row.path(0).isNumber() ? row.path(0).asInt() : 0;
        int sameAsEdges = row.isArray() && row.size() > 1 && row.path(1).isNumber() ? row.path(1).asInt() : 0;
        return Map.of(
                "entityFusionEnabled", true,
                "entityFusionMode", mode,
                "fusionBatchId", batchScoped ? fusionBatchId : "",
                "entityFusionBackfilled", backfilled,
                "entityFusionGroups", groups,
                "entityFusionSameAsEdges", sameAsEdges
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
                "CREATE CONSTRAINT hmrag_entity_cluster_key IF NOT EXISTS FOR (n:EntityCluster) REQUIRE n.fusionKey IS UNIQUE"
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
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", blankTo(id, UUID.randomUUID().toString()));
        node.put("label", blankTo(label, "未命名实体"));
        node.put("type", blankTo(type, "Other"));
        node.put("canonicalName", canonicalName);
        node.put("fusionKey", fusionKey);
        node.put("stateCount", stateCount);
        return node;
    }

    private String textAt(JsonNode row, int index) {
        JsonNode value = row.path(index);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private int intAt(JsonNode row, int index) {
        JsonNode value = row.path(index);
        return value.isNumber() ? value.asInt() : 0;
    }

    private String stateKey(String canonicalKey, Map<String, Object> entity) {
        String validFrom = normalize(stringValue(entity.get("validFrom")));
        String validTo = normalize(stringValue(entity.get("validTo")));
        String definition = normalize(stringValue(entity.get("definition")));
        return canonicalKey + ":state:" + validFrom + ":" + validTo + ":" + Integer.toHexString(definition.hashCode());
    }

    private String factKey(String subjectState, String objectState, Map<String, Object> relation) {
        return subjectState + ":fact:" + normalize(stringValue(relation.get("relationType"))) + ":"
                + objectState + ":" + normalize(stringValue(relation.get("validFrom"))) + ":" + normalize(stringValue(relation.get("validTo")));
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
}
