package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DomainKnowledgeRefinementServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refineCallsOpenAiCompatibleEndpointWithBuildSpecAndParsesStructuredContent() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {
                      "summary":"测试摘要",
                      "keyPoints":["要点1","要点2"],
                      "markdown":"## 核心结论\\n测试",
                      "structuredContent":{
                        "version":"v1",
                        "catalog":[{"id":"cat_001","level":1,"title":"制度政策","evidenceRefs":["chunk:chunk-1"]}],
                        "cards":[{"id":"card_001","catalogId":"cat_001","type":"concept","title":"卡片","claims":[{"text":"结论","evidenceRefs":["chunk:chunk-1"]}]}],
                        "evidenceBindings":[{"evidenceRef":"chunk:chunk-1","catalogIds":["cat_001"],"cardIds":["card_001"],"claimTexts":["结论"]}],
                        "validation":{"status":"ready","warnings":[]}
                      }
                    }
                    """.replace("\n", "");
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DomainKnowledgeRefinementService service = new DomainKnowledgeRefinementService(
                    appProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    objectMapper
            );

            DomainKnowledgeRefinementService.RefinedResult result = service.refine(
                    "研究生教育知识智能管理平台",
                    "支持智能体构建领域知识库",
                    "覆盖平台功能、指标和排除项",
                    List.of("草稿要点"),
                    Map.of(
                            "domainName", "研究生教育知识智能管理平台",
                            "scope", Map.of("excludeTerms", List.of("非本校新闻")),
                            "knowledgeDimensions", List.of("制度政策", "业务流程")
                    ),
                    List.of("非本校新闻"),
                    List.of("平台核心功能模块", "关键技术指标"),
                    List.of(Map.of("docId", "doc-1", "title", "平台说明", "sourceFilename", "a.md")),
                    List.of(Map.of("knowledgeUnitId", "ku-1", "docId", "doc-1", "title", "功能模块", "content", "研究生教育平台功能模块")),
                    List.of(Map.of("chunkId", "chunk-1", "docId", "doc-1", "title", "功能模块", "snippet", "平台用于研究生教育知识管理"))
            );

            assertThat(result.summary()).isEqualTo("测试摘要");
            assertThat(result.structuredContent()).containsKey("catalog");
            assertThat(capturedBody.get()).contains("\"max_tokens\":32000");
            assertThat(capturedBody.get()).contains("DomainBuildSpec");
            assertThat(capturedBody.get()).contains("非本校新闻");
            assertThat(capturedBody.get()).contains("平台核心功能模块");
            assertThat(capturedBody.get()).contains("contentPreview");
            assertThat(capturedBody.get()).contains("snippetPreview");
            assertThat(capturedBody.get().length()).isLessThan(6000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void groupRefinementUsesSmallEvidenceIndexAndReturnsEvidenceRefs() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {
                      "name":"制度政策",
                      "summary":"制度政策组摘要",
                      "keyClaims":["结论1"],
                      "evidenceRefs":["knowledge_unit:ku-1","chunk:chunk-1"],
                      "warnings":[],
                      "metadata":{"stage":"group"}
                    }
                    """.replace("\n", "");
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DomainKnowledgeRefinementService service = new DomainKnowledgeRefinementService(
                    appProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    objectMapper
            );

            DomainKnowledgeRefinementService.GroupRefinedResult result = service.refineGroup(
                    "研究生教育知识智能管理平台",
                    Map.of("domainName", "研究生教育知识智能管理平台"),
                    List.of("非本校新闻"),
                    Map.of(
                            "name", "制度政策",
                            "evidenceRefs", List.of("knowledge_unit:ku-1", "chunk:chunk-1"),
                            "knowledgeUnits", List.of(Map.of(
                                    "evidenceRef", "knowledge_unit:ku-1",
                                    "title", "管理办法",
                                    "contentPreview", "研究生教育管理办法"
                            )),
                            "chunks", List.of(Map.of(
                                    "evidenceRef", "chunk:chunk-1",
                                    "snippetPreview", "平台用于研究生教育知识管理"
                            ))
                    )
            );

            assertThat(result.summary()).isEqualTo("制度政策组摘要");
            assertThat(result.evidenceRefs()).containsExactly("knowledge_unit:ku-1", "chunk:chunk-1");
            assertThat(capturedBody.get()).contains("\"max_tokens\":16000");
            assertThat(capturedBody.get()).contains("证据组");
            assertThat(capturedBody.get().length()).isLessThan(5000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void groupRefinementFallsBackWhenModelReturnsTruncatedJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", "{\"name\":\"综合证据\",\"keyClaims\":[\"未闭合\"")))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DomainKnowledgeRefinementService service = new DomainKnowledgeRefinementService(
                    appProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    objectMapper
            );

            DomainKnowledgeRefinementService.GroupRefinedResult result = service.refineGroup(
                    "研究生教育知识智能管理平台",
                    Map.of("domainName", "研究生教育知识智能管理平台"),
                    List.of("非本校新闻"),
                    Map.of(
                            "name", "综合证据",
                            "evidenceRefs", List.of("knowledge_unit:ku-1", "chunk:chunk-1"),
                            "knowledgeUnits", List.of(Map.of(
                                    "evidenceRef", "knowledge_unit:ku-1",
                                    "subject", "功能模块",
                                    "title", "管理办法"
                            )),
                            "chunks", List.of(Map.of(
                                    "evidenceRef", "chunk:chunk-1",
                                    "snippetPreview", "平台用于研究生教育知识管理"
                            ))
                    )
            );

            assertThat(result.summary()).contains("降级摘要");
            assertThat(result.evidenceRefs()).contains("knowledge_unit:ku-1");
            assertThat(result.warnings()).anyMatch(item -> item.contains("LLM_GROUP_JSON_INVALID"));
            assertThat(result.metadata()).containsEntry("fallback", true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synthesisWindowRespectsExistingDomainKnowledgeConfig() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {"summary":"测试摘要","keyPoints":["要点"],"markdown":"正文","structuredContent":{"catalog":[{"id":"cat_001","level":1,"title":"目录","evidenceRefs":["chunk:chunk-1"]}],"cards":[{"id":"card_001","catalogId":"cat_001","type":"concept","title":"卡片","claims":[{"text":"结论","evidenceRefs":["chunk:chunk-1"]}]}],"evidenceBindings":[],"validation":{"status":"ready","warnings":[]}}}
                    """;
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DomainKnowledgeRefinementService service = new DomainKnowledgeRefinementService(
                    appProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    objectMapper
            );
            List<Map<String, Object>> documents = manyItems("doc", 6);
            List<Map<String, Object>> units = manyItems("ku", 8);
            List<Map<String, Object>> chunks = manyItems("chunk", 8);

            service.synthesize(
                    "领域",
                    "目标",
                    "范围",
                    List.of("要点"),
                    Map.of("domainName", "领域"),
                    List.of(),
                    List.of("检索词"),
                    List.of(),
                    documents,
                    units,
                    chunks
            );

            String body = capturedBody.get();
            assertThat(countOccurrences(body, "evidenceRef=document:")).isEqualTo(1);
            assertThat(countOccurrences(body, "evidenceRef=knowledge_unit:")).isEqualTo(1);
            assertThat(countOccurrences(body, "evidenceRef=chunk:")).isEqualTo(1);
            assertThat(body).doesNotContain("doc-2");
            assertThat(body).doesNotContain("ku-2");
            assertThat(body).doesNotContain("chunk-2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void synthesisUsesCompressedGroupSummaryWhenGroupsAreAvailable() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {"summary":"测试摘要","keyPoints":["要点"],"markdown":"正文","structuredContent":{"catalog":[{"id":"cat_001","level":1,"title":"目录","evidenceRefs":["knowledge_unit:ku-1"]}],"cards":[{"id":"card_001","catalogId":"cat_001","type":"concept","title":"卡片","claims":[{"text":"结论","evidenceRefs":["knowledge_unit:ku-1"]}]}],"evidenceBindings":[],"validation":{"status":"ready","warnings":[]}}}
                    """;
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DomainKnowledgeRefinementService service = new DomainKnowledgeRefinementService(
                    appProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    objectMapper
            );

            service.synthesize(
                    "领域",
                    "目标",
                    "范围",
                    List.of("要点"),
                    Map.of(
                            "domainName", "领域",
                            "knowledgeDimensions", List.of("制度政策", "业务流程"),
                            "socraticSetup", Map.of("history", List.of("很长的对话不应进入最终合成"))
                    ),
                    List.of("排除项"),
                    List.of("检索词"),
                    List.of(Map.of(
                            "name", "制度政策",
                            "summary", "分组摘要",
                            "keyClaims", List.of("结论1", "结论2"),
                            "evidenceRefs", List.of("knowledge_unit:ku-1", "chunk:chunk-1"),
                            "warnings", List.of()
                    )),
                    manyItems("doc", 6),
                    manyItems("ku", 24),
                    manyItems("chunk", 24)
            );

            String body = capturedBody.get();
            assertThat(body).contains("分组摘要");
            assertThat(body).contains("已由“分组精炼结果”的 keyClaims/evidenceRefs 压缩承载");
            assertThat(body).doesNotContain("contentPreview");
            assertThat(body).doesNotContain("snippetPreview");
            assertThat(body).doesNotContain("很长的对话不应进入最终合成");
            assertThat(body.length()).isLessThan(5000);
        } finally {
            server.stop(0);
        }
    }

    private List<Map<String, Object>> manyItems(String type, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> switch (type) {
                    case "doc" -> Map.<String, Object>of("docId", "doc-" + index, "title", "文档" + index, "sourceFilename", "d" + index + ".md");
                    case "ku" -> Map.<String, Object>of("knowledgeUnitId", "ku-" + index, "docId", "doc-" + index, "title", "知识单元" + index, "content", "内容" + index);
                    default -> Map.<String, Object>of("chunkId", "chunk-" + index, "docId", "doc-" + index, "title", "片段" + index, "snippet", "片段内容" + index);
                })
                .toList();
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(pattern, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + pattern.length();
        }
    }

    private AppProperties appProperties(String baseUrl) {
        AppProperties.RefinementLlm refinementLlm = new AppProperties.RefinementLlm(
                "openai_compatible",
                baseUrl,
                "",
                "fake-model",
                1,
                10,
                true,
                32000,
                16000,
                8000
        );
        return new AppProperties(
                new AppProperties.Ingest(50, 45, 1000, 1, 1, false, "", 0.0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0, 0),
                new AppProperties.Scan(true),
                new AppProperties.Llm("disabled", "", "", "", 1, 5, false),
                new AppProperties.Embedding("disabled", "", "", "", 1, 5, 1, 384, false, 0.0, 0, 0, 0, 0, 0, 0, 0, 0),
                new AppProperties.Query(5, 1, 5, 5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                new AppProperties.Maintenance(1, 1, 5),
                new AppProperties.DomainKnowledge(1, 1, 3000, 60000, 600000, false, 1, 5, 168, 80, 8, 6, 12, 24, 24, 72, 300, 1, 200, 160, 12, 48, 1, 1, 1, 120, 384, false, refinementLlm),
                new AppProperties.KnowledgeGraph(
                        false,
                        "neo4j-http",
                        "",
                        "neo4j",
                        "",
                        "",
                        1,
                        5,
                        5000,
                        1,
                        600,
                        6,
                        120,
                        80,
                        new AppProperties.ExtractionLlm("disabled", "", "", "", 1, 5, true, 12000),
                        new AppProperties.EntityFusion(
                                true,
                                "deterministic",
                                true,
                                2,
                                50,
                                new AppProperties.ExtractionLlm("disabled", "", "", "", 1, 5, false, 8000)
                        )
                )
        );
    }
}
