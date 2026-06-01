package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphExtractionServiceTest {

    private final KnowledgeGraphExtractionService service = new KnowledgeGraphExtractionService(
            (NamedParameterJdbcTemplate) null,
            new ObjectMapper(),
            new AppProperties(null, null, null, null, null, null, null, null)
    );

    @Test
    @SuppressWarnings("unchecked")
    void triplesAreNormalizedToCompatibleFactsAndBoundByEntityName() throws Exception {
        Map<String, Object> extracted = Map.of("triples", List.of(
                Map.of(
                        "subject", "中办、国办",
                        "subjectType", "Organization",
                        "predicate", "印发",
                        "object", "关于深化教育体制机制改革的意见",
                        "objectType", "Policy",
                        "chunkId", "chunk-1"
                ),
                Map.of(
                        "subject", "高校",
                        "subjectType", "Organization",
                        "predicate", "累计培养",
                        "object", "29万名博士、313万名硕士",
                        "objectType", "Concept",
                        "chunkId", "chunk-1"
                ),
                Map.of(
                        "subject", "双一流建设",
                        "subjectType", "Project",
                        "predicate", "是",
                        "object", "双一流建设",
                        "objectType", "Project",
                        "chunkId", "chunk-1"
                )
        ));

        List<Map<String, Object>> facts = (List<Map<String, Object>>) invoke("extractedFacts",
                new Class<?>[]{Map.class},
                extracted
        );
        List<Map<String, Object>> entities = new ArrayList<>();
        Object binding = invoke("prefixFacts",
                new Class<?>[]{List.class, Map.class, List.class, int.class, Map.class},
                facts,
                new LinkedHashMap<String, String>(),
                entities,
                0,
                Map.of("title", "测试文档")
        );

        List<Map<String, Object>> boundFacts = (List<Map<String, Object>>) binding.getClass()
                .getDeclaredMethod("facts")
                .invoke(binding);

        assertThat(entities)
                .extracting(entity -> entity.get("name"))
                .contains("中办、国办", "关于深化教育体制机制改革的意见", "高校")
                .doesNotContain("29万名博士、313万名硕士");
        assertThat(boundFacts)
                .filteredOn(fact -> "relation_fact".equals(fact.get("factKind")))
                .extracting(fact -> fact.get("predicate"))
                .contains("印发");
        assertThat(boundFacts)
                .filteredOn(fact -> "attribute_fact".equals(fact.get("factKind")))
                .hasSize(1)
                .first()
                .satisfies(fact -> assertThat(fact.get("value")).isEqualTo("29万名博士、313万名硕士"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserRepairsExtraArrayClosureBeforeTopLevelTriples() throws Exception {
        String raw = """
                {
                  "entities": [
                    {"mentionId":"e1","name":"研究生教育","type":"Concept","chunkId":"c1"}
                  ]],
                  "triples": [
                    {"subject":"研究生教育","predicate":"支撑","object":"高质量发展","objectType":"Concept","chunkId":"c1"}
                  ],
                  "facts": [],
                  "relations": [],
                  "attributes": [],
                  "events": []
                }
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        assertThat((List<?>) parsed.get("entities")).hasSize(1);
        assertThat((List<?>) parsed.get("triples")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserMergesSplitTopLevelEntityAndTripleObjects() throws Exception {
        String raw = """
                {"entities": [
                  {"mentionId":"e1","name":"国务院学位委员会","type":"Organization","chunkId":"c1"}
                ]}, {"triples": [
                  {"subject":"国务院学位委员会","predicate":"召开","object":"第34次会议","objectType":"Event","chunkId":"c1"}
                ], "facts": [], "relations": [], "attributes": [], "events": []}
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        assertThat((List<?>) parsed.get("entities")).hasSize(1);
        assertThat((List<?>) parsed.get("triples")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserRepairsExtraTopLevelObjectClosureBeforeTriples() throws Exception {
        String raw = """
                {"entities": [
                  {"mentionId":"e1","name":"SpringCloud","type":"Technology","chunkId":"c1"}
                ]}, "triples": [
                  {"subject":"分布式架构","predicate":"可集成","object":"SpringCloud","objectType":"Technology","chunkId":"c1"}
                ], "facts": [], "relations": [], "attributes": [], "events": []}
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        assertThat((List<?>) parsed.get("entities")).hasSize(1);
        assertThat((List<?>) parsed.get("triples")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserRepairsExtraQuoteAfterNumericValue() throws Exception {
        String raw = """
                {"entities": [
                  {"mentionId":"e1","name":"SOA架构","type":"Technology","chunkId":"c1","confidence":1.0"}
                ], "triples": [], "facts": [], "relations": [], "attributes": [], "events": []}
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        assertThat((List<?>) parsed.get("entities")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserEscapesBareNewlinesInsideStringValues() throws Exception {
        String raw = """
                {"entities": [
                  {"mentionId":"e1","name":"招生报名比","type":"Indicator","chunkId":"c1"}
                ], "triples": [
                  {"subject":"招生报名比","predicate":"数值为","object":"1:2.06","objectType":"Other","chunkId":"c1","statement":"2017年总体招生报名比为1:2.06。
                该指标来自同一段落。"}
                ], "facts": [], "relations": [], "attributes": [], "events": []}
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        List<Map<String, Object>> triples = (List<Map<String, Object>>) parsed.get("triples");
        assertThat(triples).hasSize(1);
        assertThat((String) triples.getFirst().get("statement")).contains("同一段落");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserRepairsExtraObjectAndArrayClosureBeforeTriples() throws Exception {
        String raw = """
                {"entities": [
                  {"mentionId":"e1","name":"双一流建设","type":"Project","chunkId":"c1"}
                ]}], "triples": [
                  {"subject":"双一流建设","predicate":"总体部署","object":"总体方案","objectType":"Document","chunkId":"c1"}
                ]}
                """;

        Map<String, Object> parsed = (Map<String, Object>) invoke("parseJsonObject",
                new Class<?>[]{String.class},
                raw
        );

        assertThat((List<?>) parsed.get("entities")).hasSize(1);
        assertThat((List<?>) parsed.get("triples")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectRepeatingPredicateIsDroppedAsNoise() throws Exception {
        List<Map<String, Object>> facts = List.of(new LinkedHashMap<>(Map.of(
                "factKind", "relation_fact",
                "subject", "高质量发展",
                "subjectType", "Concept",
                "predicate", "促进",
                "object", "促进",
                "objectType", "Concept",
                "chunkId", "chunk-1"
        )));

        List<Map<String, Object>> entities = new ArrayList<>();
        Object binding = invoke("prefixFacts",
                new Class<?>[]{List.class, Map.class, List.class, int.class, Map.class},
                facts,
                new LinkedHashMap<String, String>(),
                entities,
                0,
                Map.of("title", "测试文档")
        );

        List<Map<String, Object>> boundFacts = (List<Map<String, Object>>) binding.getClass()
                .getDeclaredMethod("facts")
                .invoke(binding);

        assertThat(boundFacts).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void misleadingLlmFactKindIsReclassifiedBeforeBinding() throws Exception {
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(new LinkedHashMap<>(Map.of(
                "factKind", "identity_fact",
                "subject", "习近平总书记",
                "subjectType", "Person",
                "predicate", "强调",
                "object", "中央深改组第二次会议",
                "objectType", "Event",
                "chunkId", "chunk-1"
        )));
        facts.add(new LinkedHashMap<>(Map.of(
                "factKind", "attribute_fact",
                "subject", "学位条例",
                "subjectType", "Policy",
                "predicate", "修订统筹考虑",
                "object", "教育现代化2035",
                "objectType", "Policy",
                "chunkId", "chunk-1"
        )));

        List<Map<String, Object>> entities = new ArrayList<>();
        Object binding = invoke("prefixFacts",
                new Class<?>[]{List.class, Map.class, List.class, int.class, Map.class},
                facts,
                new LinkedHashMap<String, String>(),
                entities,
                0,
                Map.of("title", "测试文档")
        );

        List<Map<String, Object>> boundFacts = (List<Map<String, Object>>) binding.getClass()
                .getDeclaredMethod("facts")
                .invoke(binding);

        assertThat(boundFacts)
                .filteredOn(fact -> "relation_fact".equals(fact.get("factKind")))
                .hasSize(2)
                .allSatisfy(fact -> assertThat(String.valueOf(fact.get("objectMentionId"))).isNotBlank());
    }

    @Test
    void unusedExtractedEntitiesAreKeptAsContextMentions() throws Exception {
        List<Map<String, Object>> entities = new ArrayList<>();
        entities.add(new LinkedHashMap<>(Map.of(
                "mentionId", "b1_e1",
                "name", "学位论文成果智慧管理系统",
                "type", "System"
        )));
        entities.add(new LinkedHashMap<>(Map.of(
                "mentionId", "b1_e2",
                "name", "SpringCloud",
                "type", "Technology"
        )));
        entities.add(new LinkedHashMap<>(Map.of(
                "mentionId", "b1_e3",
                "name", "备注",
                "type", "Concept"
        )));
        List<Map<String, Object>> facts = List.of(new LinkedHashMap<>(Map.of(
                "factKind", "relation_fact",
                "subjectMentionId", "b1_e1",
                "objectMentionId", "b1_e2",
                "predicate", "采用",
                "chunkId", "chunk-1"
        )));

        invoke("markEntitiesUsedByFacts",
                new Class<?>[]{List.class, List.class},
                entities,
                facts
        );

        assertThat(entities)
                .extracting(entity -> entity.get("name"))
                .containsExactly("学位论文成果智慧管理系统", "SpringCloud", "备注");
        assertThat(entities)
                .filteredOn(entity -> Boolean.TRUE.equals(entity.get("factEndpoint")))
                .extracting(entity -> entity.get("name"))
                .containsExactly("学位论文成果智慧管理系统", "SpringCloud");
        assertThat(entities)
                .filteredOn(entity -> "context_mention".equals(entity.get("entityRole")))
                .extracting(entity -> entity.get("name"))
                .containsExactly("备注");
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = KnowledgeGraphExtractionService.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
}
