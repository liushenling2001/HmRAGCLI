package com.hmrag.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DomainKnowledgeRefinementService {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeRefinementService.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public DomainKnowledgeRefinementService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public RefinedResult refine(
            String title,
            String goal,
            String scopeDescription,
            List<String> keyPoints,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            List<String> retrievalTerms,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks
    ) {
        return synthesize(
                title,
                goal,
                scopeDescription,
                keyPoints,
                domainBuildSpec,
                excludedTerms,
                retrievalTerms,
                List.of(),
                documents,
                knowledgeUnits,
                chunks
        );
    }

    public GroupRefinedResult refineGroup(
            String title,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            Map<String, Object> group
    ) {
        AppProperties.RefinementLlm config = refinementLlm();
        if (!isEnabled()) {
            throw new DomainKnowledgePauseException(
                    "LLM_DISABLED_OR_NOT_CONFIGURED",
                    Map.of("enabled", false, "reason", "LLM_DISABLED_OR_NOT_CONFIGURED")
            );
        }
        try {
            String prompt = buildGroupPrompt(title, domainBuildSpec, excludedTerms, group);
            log.info(
                    "Domain knowledge LLM group refinement request: provider={}, model={}, baseUrl={}, group={}, promptChars={}",
                    provider(),
                    config.model(),
                    baseUrl(),
                    group.get("name"),
                    prompt.length()
            );
            String raw = switch (provider()) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatibleGroup(prompt);
                case "ollama" -> callOllamaGroup(prompt);
                default -> throw new IllegalStateException("Unsupported llm provider: " + provider());
            };
            Map<String, Object> parsed;
            try {
                parsed = parseJsonObject(raw);
            } catch (JsonProcessingException ex) {
                log.warn(
                        "Domain knowledge LLM group refinement returned invalid JSON: provider={}, model={}, group={}, responseChars={}, error={}",
                        provider(),
                        config.model(),
                        group.get("name"),
                        raw == null ? 0 : raw.length(),
                        ex.getMessage()
                );
                return fallbackGroupResult(group, "LLM_GROUP_JSON_INVALID", ex.getMessage());
            }
            return new GroupRefinedResult(
                    textValue(parsed.get("name")),
                    textValue(parsed.get("summary")),
                    stringList(parsed.get("keyClaims")),
                    stringList(parsed.get("evidenceRefs")),
                    stringList(parsed.get("warnings")),
                    mapValue(parsed.get("metadata"))
            );
        } catch (TaskCancelledException ex) {
            throw ex;
        } catch (SocketTimeoutException ex) {
            log.warn(
                    "Domain knowledge LLM group refinement timed out, using fallback: provider={}, model={}, group={}, error={}",
                    provider(),
                    config.model(),
                    group.get("name"),
                    ex.getMessage()
            );
            return fallbackGroupResult(group, "LLM_GROUP_TIMEOUT", ex.getMessage());
        } catch (Exception ex) {
            throw new DomainKnowledgePauseException(
                    "LLM_GROUP_REFINEMENT_UNAVAILABLE: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    Map.of(
                            "enabled", true,
                            "reason", "LLM_GROUP_REFINEMENT_UNAVAILABLE",
                            "provider", provider(),
                            "model", config.model(),
                            "baseUrl", baseUrl(),
                            "group", String.valueOf(group.get("name")),
                            "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    )
            );
        }
    }

    public RefinedResult synthesize(
            String title,
            String goal,
            String scopeDescription,
            List<String> keyPoints,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            List<String> retrievalTerms,
            List<Map<String, Object>> groupSummaries,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks
    ) {
        AppProperties.RefinementLlm config = refinementLlm();
        if (!isEnabled()) {
            throw new DomainKnowledgePauseException(
                    "LLM_DISABLED_OR_NOT_CONFIGURED",
                    Map.of(
                            "enabled", false,
                            "reason", "LLM_DISABLED_OR_NOT_CONFIGURED"
                    )
            );
        }
        try {
            String prompt = buildPrompt(title, goal, scopeDescription, keyPoints, domainBuildSpec, excludedTerms, retrievalTerms, groupSummaries, documents, knowledgeUnits, chunks);
            log.info(
                    "Domain knowledge LLM synthesis request: provider={}, model={}, baseUrl={}, promptChars={}, groups={}, documents={}, knowledgeUnits={}, chunks={}",
                    provider(),
                    config.model(),
                    baseUrl(),
                    prompt.length(),
                    groupSummaries == null ? 0 : groupSummaries.size(),
                    documents == null ? 0 : documents.size(),
                    knowledgeUnits == null ? 0 : knowledgeUnits.size(),
                    chunks == null ? 0 : chunks.size()
            );
            String raw = switch (provider()) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(
                        prompt
                );
                case "ollama" -> callOllama(
                        prompt
                );
                default -> throw new IllegalStateException("Unsupported llm provider: " + provider());
            };
            log.info(
                    "Domain knowledge LLM synthesis response received: provider={}, model={}, responseChars={}",
                    provider(),
                    config.model(),
                    raw == null ? 0 : raw.length()
            );
            Map<String, Object> parsed = parseJsonObject(raw);
            String summary = textValue(parsed.get("summary"));
            String markdown = textValue(parsed.get("markdown"));
            List<String> refinedPoints = stringList(parsed.get("keyPoints"));
            Map<String, Object> structuredContent = mapValue(parsed.get("structuredContent"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("llmProvider", provider());
            metadata.put("llmModel", config.model());
            metadata.put("refined", true);
            return new RefinedResult(summary, refinedPoints, markdown, structuredContent, metadata);
        } catch (TaskCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainKnowledgePauseException(
                    "LLM_REFINEMENT_UNAVAILABLE: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    Map.of(
                            "enabled", true,
                            "reason", "LLM_REFINEMENT_UNAVAILABLE",
                            "provider", provider(),
                            "model", config.model(),
                            "baseUrl", baseUrl(),
                            "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    )
            );
        }
    }

    public List<String> planRetrievalTerms(
            String domainName,
            String goal,
            String scopeDescription,
            List<String> seedQueries,
            List<String> excludeTerms,
            List<String> setupHistory,
            String topicName,
            String topicDescription,
            List<String> topicSeedQueries
    ) {
        RetrievalPlanResult plan = planRetrievalPlan(
                domainName,
                goal,
                scopeDescription,
                seedQueries,
                excludeTerms,
                setupHistory,
                topicName,
                topicDescription,
                topicSeedQueries
        );
        if (plan.terms().isEmpty()) {
            return List.of();
        }
        return plan.terms().stream().limit(24).toList();
    }

    public RetrievalPlanResult planRetrievalPlan(
            String domainName,
            String goal,
            String scopeDescription,
            List<String> seedQueries,
            List<String> excludeTerms,
            List<String> setupHistory,
            String topicName,
            String topicDescription,
            List<String> topicSeedQueries
    ) {
        if (!isEnabled()) {
            return RetrievalPlanResult.empty();
        }
        try {
            String prompt = buildRetrievalTermPrompt(domainName, goal, scopeDescription, seedQueries, excludeTerms, setupHistory, topicName, topicDescription, topicSeedQueries);
            log.info(
                    "Domain knowledge LLM retrieval-term request: provider={}, model={}, baseUrl={}, promptChars={}",
                    provider(),
                    refinementLlm().model(),
                    baseUrl(),
                    prompt.length()
            );
            String raw = switch (provider()) {
                case "openai_compatible", "openai-compatible" -> callOpenAiCompatibleTermPlan(
                        prompt
                );
                case "ollama" -> callOllamaTermPlan(
                        prompt
                );
                default -> throw new IllegalStateException("Unsupported llm provider: " + provider());
            };
            Map<String, Object> parsed = parseJsonObject(raw);
            List<String> terms = stringList(parsed.get("terms")).stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .limit(24)
                    .toList();
            List<RetrievalDimensionPlan> dimensions = toRetrievalDimensions(parsed.get("dimensions"));
            return new RetrievalPlanResult(terms, dimensions, parsed);
        } catch (Exception ex) {
            log.warn(
                    "Domain knowledge LLM retrieval-term planning failed: provider={}, model={}, baseUrl={}, error={}",
                    provider(),
                    refinementLlm().model(),
                    baseUrl(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            );
            return RetrievalPlanResult.empty();
        }
    }

    private String buildPrompt(
            String title,
            String goal,
            String scopeDescription,
            List<String> keyPoints,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            List<String> retrievalTerms,
            List<Map<String, Object>> groupSummaries,
            List<Map<String, Object>> documents,
            List<Map<String, Object>> knowledgeUnits,
            List<Map<String, Object>> chunks
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("请根据下面的领域知识草稿与证据，生成一个供智能体使用的精炼结果。\n");
        builder.append("只允许返回一个 JSON 对象，字段只能是 summary,keyPoints,markdown,structuredContent。\n");
        builder.append("要求：\n");
        builder.append("1. summary 不超过 220 字。\n");
        builder.append("2. keyPoints 返回 4 到 8 条字符串。\n");
        builder.append("3. markdown 用中文，结构清晰，包含“核心结论”“重要证据”“使用建议”三个小节。\n");
        builder.append("4. structuredContent 必须包含 version,catalog,cards,evidenceBindings,validation。\n");
        builder.append("5. catalog 是最多 3 层的知识目录，一级目录必须是领域子域，不要使用论文题名、文件名或长句当目录。\n");
        builder.append("6. catalog 控制在 4 到 8 个节点，cards 控制在 6 到 10 张，每张卡片必须有 catalogId,type,title,summary,claims。\n");
        builder.append("7. 每张卡片 1 到 2 条 claim；每条 claim 必须绑定下面提供的 evidenceRef，不允许编造 evidenceRef。\n");
        builder.append("8. 明显证据不足的内容放入 validation.warnings，不要伪造成确定结论。\n");
        builder.append("9. 不要编造证据，不要加入未提供的新事实。\n\n");
        builder.append("10. 必须严格执行领域构建规格中的 scope.excludeTerms/negativeTerms；排除项不能进入目录、卡片、结论和证据绑定。\n");
        builder.append("11. 必须围绕领域构建规格中的 knowledgeDimensions/agentUseCases/catalogRules 组织目录，不能只围绕领域名称或文件标题组织。\n");
        builder.append("12. 只能基于分组精炼摘要和少量证据索引做总融合；后续智能体可通过 evidenceRef 回溯正文。\n\n");
        builder.append("structuredContent 示例结构:\n");
        builder.append("{\"version\":\"v1\",\"catalog\":[{\"id\":\"cat_001\",\"parentId\":null,\"level\":1,\"title\":\"目录名\",\"summary\":\"...\",\"keywords\":[],\"evidenceRefs\":[\"chunk:...\"]}],\"cards\":[{\"id\":\"card_001\",\"catalogId\":\"cat_001\",\"type\":\"concept\",\"title\":\"卡片名\",\"summary\":\"...\",\"claims\":[{\"text\":\"结论\",\"confidence\":\"high\",\"evidenceRefs\":[\"chunk:...\"]}]}],\"evidenceBindings\":[{\"evidenceRef\":\"chunk:...\",\"catalogIds\":[\"cat_001\"],\"cardIds\":[\"card_001\"],\"claimTexts\":[\"结论\"]}],\"validation\":{\"status\":\"ready\",\"warnings\":[]}}\n\n");
        builder.append("标题: ").append(safe(title)).append("\n");
        builder.append("目标: ").append(safe(goal)).append("\n");
        builder.append("范围: ").append(safe(scopeDescription)).append("\n\n");
        builder.append("领域构建规格 DomainBuildSpec:\n");
        builder.append(safeJson(compactDomainBuildSpecForPrompt(domainBuildSpec))).append("\n\n");
        builder.append("明确排除项:\n");
        appendList(builder, excludedTerms, 16);
        builder.append("\n实际检索计划/检索词:\n");
        appendList(builder, retrievalTerms, 40);
        builder.append("草稿要点:\n");
        appendList(builder, keyPoints, 8);
        builder.append("\n分组精炼结果:\n");
        appendGroupSummaries(builder, groupSummaries, 8);
        builder.append("\n证据覆盖统计:\n");
        builder.append("- documents=").append(documents == null ? 0 : documents.size())
                .append(", knowledgeUnits=").append(knowledgeUnits == null ? 0 : knowledgeUnits.size())
                .append(", chunks=").append(chunks == null ? 0 : chunks.size()).append("\n");
        builder.append("- 注意：下面是代表性证据窗口；完整证据清单已保存在知识包 sourceSnapshot/evidenceRefs，不能把未展示证据理解为不存在。\n");
        builder.append("\n证据文档索引:\n");
        appendDocumentEvidence(builder, documents, hasGroupSummaries(groupSummaries) ? Math.min(4, synthesisDocumentLimit()) : synthesisDocumentLimit());
        if (hasGroupSummaries(groupSummaries)) {
            builder.append("\n知识单元/正文片段索引:\n");
            builder.append("- 已由“分组精炼结果”的 keyClaims/evidenceRefs 压缩承载；不要再次要求完整正文窗口。\n");
        } else {
            builder.append("\n知识单元索引:\n");
            appendKnowledgeUnitEvidence(builder, knowledgeUnits, synthesisKnowledgeUnitLimit());
            builder.append("\n正文片段索引:\n");
            appendChunkEvidence(builder, chunks, synthesisChunkLimit());
        }
        return builder.toString();
    }

    private boolean hasGroupSummaries(List<Map<String, Object>> groupSummaries) {
        return groupSummaries != null && !groupSummaries.isEmpty();
    }

    private Map<String, Object> compactDomainBuildSpecForPrompt(Map<String, Object> domainBuildSpec) {
        if (domainBuildSpec == null || domainBuildSpec.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        copyIfPresent(domainBuildSpec, compact, "version");
        copyIfPresent(domainBuildSpec, compact, "domainName");
        copyIfPresent(domainBuildSpec, compact, "topicName");
        copyIfPresent(domainBuildSpec, compact, "goal");
        copyIfPresent(domainBuildSpec, compact, "description");
        copyIfPresent(domainBuildSpec, compact, "scope");
        compact.put("agentUseCases", limitedStringList(domainBuildSpec.get("agentUseCases"), 8));
        compact.put("knowledgeDimensions", limitedStringList(domainBuildSpec.get("knowledgeDimensions"), 8));
        copyIfPresent(domainBuildSpec, compact, "catalogRules");
        compact.put("seedQueries", limitedStringList(domainBuildSpec.get("seedQueries"), 8));
        compact.put("retrievalTerms", limitedStringList(domainBuildSpec.get("retrievalTerms"), 24));
        compact.put("evidenceWarnings", limitedStringList(domainBuildSpec.get("evidenceWarnings"), 8));
        Object socratic = domainBuildSpec.get("socraticSetup");
        if (socratic instanceof Map<?, ?> map) {
            Map<String, Object> compactSocratic = new LinkedHashMap<>();
            compactSocratic.put("coveredDimensions", limitedStringList(map.get("coveredDimensions"), 8));
            compactSocratic.put("currentDimension", map.get("currentDimension"));
            compactSocratic.put("nextDimension", map.get("nextDimension"));
            compactSocratic.put("reason", limitForPrompt(map.get("reason"), 240));
            compact.put("socraticSetup", compactSocratic);
        }
        return compact;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            Object value = source.get(key);
            target.put(key, value instanceof String text ? limitForPrompt(text, 360) : value);
        }
    }

    private String buildGroupPrompt(
            String title,
            Map<String, Object> domainBuildSpec,
            List<String> excludedTerms,
            Map<String, Object> group
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("请对一个领域知识证据组做受控精炼，只返回 JSON 对象，字段只能是 name,summary,keyClaims,evidenceRefs,warnings,metadata。\n");
        builder.append("要求：\n");
        builder.append("1. summary 不超过 180 字。\n");
        builder.append("2. keyClaims 返回 2 到 5 条，每条必须能被本组 evidenceRefs 支撑。\n");
        builder.append("3. evidenceRefs 只能使用本组提供的 ref，不得编造。\n");
        builder.append("4. 严格排除 excludedTerms，证据不足写入 warnings。\n");
        builder.append("5. 不要展开全文，不要生成最终目录，只做本组摘要。\n\n");
        builder.append("领域标题: ").append(safe(title)).append("\n");
        builder.append("DomainBuildSpec: ").append(safeJson(compactDomainBuildSpecForPrompt(domainBuildSpec))).append("\n");
        builder.append("excludedTerms:\n");
        appendList(builder, excludedTerms, 16);
        builder.append("\n证据组:\n");
        builder.append(safeJson(compactGroupForPrompt(group))).append("\n");
        return builder.toString();
    }

    private Map<String, Object> compactGroupForPrompt(Map<String, Object> group) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("name", group == null ? "" : group.get("name"));
        compact.put("purpose", group == null ? "" : group.get("purpose"));
        compact.put("evidenceRefs", limitedStringList(group == null ? null : group.get("evidenceRefs"), 24));
        compact.put("documents", limitedObjectList(group == null ? null : group.get("documents"), 4));
        compact.put("knowledgeUnits", limitedObjectList(group == null ? null : group.get("knowledgeUnits"), 5));
        compact.put("chunks", limitedObjectList(group == null ? null : group.get("chunks"), 5));
        compact.put("note", "这里只提供代表性证据窗口；完整证据引用已在后端保存，返回 evidenceRefs 时只能从上述 evidenceRefs 中选择。");
        return compact;
    }

    private List<String> limitedStringList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                result.add(text);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> limitedObjectList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> compact = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                }
                Object rawValue = entry.getValue();
                    compact.put(String.valueOf(entry.getKey()), rawValue instanceof String text ? limitForPrompt(text, 180) : rawValue);
            }
                result.add(compact);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String buildRetrievalTermPrompt(
            String domainName,
            String goal,
            String scopeDescription,
            List<String> seedQueries,
            List<String> excludeTerms,
            List<String> setupHistory,
            String topicName,
            String topicDescription,
            List<String> topicSeedQueries
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("请为领域知识精炼任务生成结构化 Retrieval Plan。\n");
        builder.append("只允许返回一个 JSON 对象，字段只能是 terms,dimensions,coverage,notes。\n");
        builder.append("要求：\n");
        builder.append("1. terms 返回 12 到 24 条全局短检索词，用于候选文档召回。\n");
        builder.append("2. dimensions 返回 4 到 8 个维度，每个维度字段只能是 name,queries,synonyms,requiredQuestions,evidenceTypes,minEvidence。\n");
        builder.append("3. 每个 queries 返回 3 到 8 条短词或短问题，synonyms 返回 2 到 8 条同义/相关表达。\n");
        builder.append("4. requiredQuestions 是该维度必须回答的问题，evidenceTypes 只能取 document,knowledge_unit,chunk。\n");
        builder.append("5. 必须覆盖主题边界、核心对象、关键流程、规则约束、历史演进、评价指标或风险约束中的相关项。\n");
        builder.append("6. 不要只重复领域名称，不要输出空泛标题。\n");
        builder.append("7. 必须避开明确要求排除的主题、对象或噪声词，不要把排除项改写成检索词。\n\n");
        builder.append("领域名称: ").append(safe(domainName)).append("\n");
        builder.append("目标: ").append(safe(goal)).append("\n");
        builder.append("范围说明: ").append(safe(scopeDescription)).append("\n");
        if (topicName != null && !topicName.isBlank()) {
            builder.append("专题名称: ").append(safe(topicName)).append("\n");
            builder.append("专题说明: ").append(safe(topicDescription)).append("\n");
        }
        builder.append("已有种子问题:\n");
        appendList(builder, seedQueries, 8);
        if (excludeTerms != null && !excludeTerms.isEmpty()) {
            builder.append("明确排除项:\n");
            appendList(builder, excludeTerms, 8);
        }
        if (topicSeedQueries != null && !topicSeedQueries.isEmpty()) {
            builder.append("专题种子问题:\n");
            appendList(builder, topicSeedQueries, 6);
        }
        if (setupHistory != null && !setupHistory.isEmpty()) {
            builder.append("AI 引导对话摘录:\n");
            appendList(builder, setupHistory, 10);
        }
        return builder.toString();
    }

    private void appendList(StringBuilder builder, List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        int count = 0;
        for (String item : items) {
            builder.append("- ").append(safe(item)).append("\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private List<RetrievalDimensionPlan> toRetrievalDimensions(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<RetrievalDimensionPlan> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = safe(map.get("name")).trim();
            if (name.isBlank()) {
                continue;
            }
            result.add(new RetrievalDimensionPlan(
                    name,
                    stringList(map.get("queries")).stream().map(String::trim).filter(text -> !text.isBlank()).limit(8).toList(),
                    stringList(map.get("synonyms")).stream().map(String::trim).filter(text -> !text.isBlank()).limit(8).toList(),
                    stringList(map.get("requiredQuestions")).stream().map(String::trim).filter(text -> !text.isBlank()).limit(8).toList(),
                    stringList(map.get("evidenceTypes")).stream().map(String::trim).filter(text -> !text.isBlank()).limit(3).toList(),
                    clampInt(map.get("minEvidence"), 3, 40, 8)
            ));
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    private int clampInt(Object value, int min, int max, int fallback) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private void appendDocumentEvidence(StringBuilder builder, List<Map<String, Object>> items, int limit) {
        if (items == null || items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        int count = 0;
        for (Map<String, Object> item : items) {
            builder.append("- ")
                    .append("evidenceRef=document:")
                    .append(safe(item.get("docId")))
                    .append(" | ")
                    .append(safe(item.get("title")))
                    .append(" | ")
                    .append(safe(item.get("sourceFilename")))
                    .append("\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private void appendGroupSummaries(StringBuilder builder, List<Map<String, Object>> groups, int limit) {
        if (groups == null || groups.isEmpty()) {
            builder.append("- 暂无，使用代表性证据索引直接生成需复核草稿\n");
            return;
        }
        int count = 0;
        for (Map<String, Object> group : groups) {
            builder.append("- group=").append(safe(group.get("name")))
                    .append(" | summary=").append(limitForPrompt(group.get("summary"), 220))
                    .append(" | keyClaims=").append(limitedStringList(group.get("keyClaims"), 6))
                    .append(" | evidenceRefs=").append(limitedStringList(group.get("evidenceRefs"), 24))
                    .append(" | warnings=").append(limitedStringList(group.get("warnings"), 6))
                    .append("\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private void appendKnowledgeUnitEvidence(StringBuilder builder, List<Map<String, Object>> items, int limit) {
        if (items == null || items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        int count = 0;
        for (Map<String, Object> item : items) {
            builder.append("- title=").append(safe(item.get("title")))
                    .append(" | evidenceRef=knowledge_unit:")
                    .append(safe(item.get("knowledgeUnitId")))
                    .append(" | subject=").append(safe(item.get("subject")))
                    .append(" | indicator=").append(safe(item.get("indicator")))
                    .append(" | contentPreview=").append(limitForPrompt(item.get("content"), promptPreviewChars()))
                    .append("\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private void appendChunkEvidence(StringBuilder builder, List<Map<String, Object>> items, int limit) {
        if (items == null || items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        int count = 0;
        for (Map<String, Object> item : items) {
            builder.append("- title=").append(safe(item.get("title")))
                    .append(" | evidenceRef=chunk:")
                    .append(safe(item.get("chunkId")))
                    .append(" | snippetPreview=").append(limitForPrompt(item.get("snippet"), promptPreviewChars()))
                    .append("\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private String callOpenAiCompatible(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", 0.2);
        body.put("max_tokens", synthesisMaxCompletionTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是知识库精炼器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, config.apiKey());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM refinement response missing content");
        }
        return content.asText();
    }

    private String callOpenAiCompatibleGroup(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", 0.1);
        body.put("max_tokens", groupMaxCompletionTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是领域证据组精炼器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, config.apiKey());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM group refinement response missing content");
        }
        return content.asText();
    }

    private String callOpenAiCompatibleTermPlan(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", 0.1);
        body.put("max_tokens", termPlanMaxCompletionTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是检索词规划器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, config.apiKey());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM retrieval term response missing content");
        }
        return content.asText();
    }

    private String callOllama(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("options", Map.of("num_predict", synthesisMaxCompletionTokens()));
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是知识库精炼器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Ollama refinement response missing content");
        }
        return content.asText();
    }

    private String callOllamaGroup(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("options", Map.of("num_predict", groupMaxCompletionTokens()));
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是领域证据组精炼器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Ollama group refinement response missing content");
        }
        return content.asText();
    }

    private String callOllamaTermPlan(String prompt) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("options", Map.of("num_predict", termPlanMaxCompletionTokens()));
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是检索词规划器，只能返回一个 JSON 对象，不要返回 Markdown 代码块。"),
                Map.of("role", "user", "content", prompt)
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Ollama retrieval term response missing content");
        }
        return content.asText();
    }

    private JsonNode sendJson(String url, Map<String, Object> body, String apiKey) throws IOException, InterruptedException {
        AppProperties.RefinementLlm config = refinementLlm();
        throwIfCancelled();
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, config.connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, config.requestTimeoutSeconds())).toMillis());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        if (apiKey != null && !apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
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
            throw new IllegalStateException("LLM refinement failed: HTTP " + status + " " + responseBody);
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

    private GroupRefinedResult fallbackGroupResult(Map<String, Object> group, String reason, String error) {
        String name = group == null ? "综合证据" : textValue(group.get("name"));
        List<String> refs = limitedStringList(group == null ? null : group.get("evidenceRefs"), 20);
        List<String> claims = new ArrayList<>();
        if (group != null) {
            for (Map<String, Object> item : limitedObjectList(group.get("knowledgeUnits"), 3)) {
                String claim = firstNonBlank(
                        textValue(item.get("subject")),
                        textValue(item.get("indicator")),
                        textValue(item.get("title"))
                );
                if (!claim.isBlank()) {
                    claims.add(claim);
                }
            }
        }
        if (claims.isEmpty()) {
            claims.add("本组证据已召回，但 LLM 分组输出 JSON 不完整，系统保留证据并交由总精炼阶段继续处理。");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fallback", true);
        metadata.put("reason", reason);
        metadata.put("error", error == null ? "" : error);
        return new GroupRefinedResult(
                name == null || name.isBlank() ? "综合证据" : name,
                "本组证据已保留，分组精炼因模型 JSON 输出不完整而采用降级摘要。",
                claims.stream().limit(5).toList(),
                refs,
                List.of(reason + ": " + (error == null ? "" : error)),
                metadata
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("CANCELLED_BY_USER");
        }
    }

    private boolean isEnabled() {
        return !provider().equals("disabled")
                && !baseUrl().isBlank()
                && !refinementLlm().model().isBlank();
    }

    private String provider() {
        return refinementLlm().provider() == null
                ? "disabled"
                : refinementLlm().provider().trim().toLowerCase(Locale.ROOT);
    }

    private String baseUrl() {
        String raw = refinementLlm().baseUrl() == null ? "" : refinementLlm().baseUrl().replaceAll("/+$", "");
        if ("ollama".equals(provider()) && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private AppProperties.RefinementLlm refinementLlm() {
        return appProperties.domainKnowledge().refinementLlm();
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private int synthesisDocumentLimit() {
        return clamp(refinementDomainKnowledge().documentLimitPerTerm(), 1, 12);
    }

    private int synthesisKnowledgeUnitLimit() {
        return clamp(refinementDomainKnowledge().knowledgeUnitLimitPerTerm(), 1, 24);
    }

    private int synthesisChunkLimit() {
        return clamp(refinementDomainKnowledge().chunkLimitPerTerm(), 1, 24);
    }

    private int promptPreviewChars() {
        return clamp(refinementDomainKnowledge().snippetChars(), 80, 240);
    }

    private int synthesisMaxCompletionTokens() {
        int configured = refinementLlm().maxCompletionTokens();
        int fallback = refinementDomainKnowledge().setupAssistantMaxCompletionTokens();
        return completionTokenBudget(configured, fallback, 4096);
    }

    private int groupMaxCompletionTokens() {
        int configured = refinementLlm().groupMaxCompletionTokens();
        return completionTokenBudget(configured, synthesisMaxCompletionTokens(), 4096);
    }

    private int termPlanMaxCompletionTokens() {
        int configured = refinementLlm().termPlanMaxCompletionTokens();
        return completionTokenBudget(configured, Math.min(synthesisMaxCompletionTokens(), 8192), 4096);
    }

    private int completionTokenBudget(int configured, int fallback, int defaultValue) {
        int value = configured > 0 ? configured : fallback;
        if (value <= 0) {
            value = defaultValue;
        }
        return clamp(value, 256, 131072);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String limitForPrompt(Object value, int limit) {
        String text = safe(value);
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit)) + "...";
    }

    private String safeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private AppProperties.DomainKnowledge refinementDomainKnowledge() {
        return appProperties.domainKnowledge();
    }

    public record RefinedResult(
            String summary,
            List<String> keyPoints,
            String markdown,
            Map<String, Object> structuredContent,
            Map<String, Object> metadata
    ) {
    }

    public record GroupRefinedResult(
            String name,
            String summary,
            List<String> keyClaims,
            List<String> evidenceRefs,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
    }

    public record RetrievalPlanResult(
            List<String> terms,
            List<RetrievalDimensionPlan> dimensions,
            Map<String, Object> raw
    ) {
        static RetrievalPlanResult empty() {
            return new RetrievalPlanResult(List.of(), List.of(), Map.of());
        }
    }

    public record RetrievalDimensionPlan(
            String name,
            List<String> queries,
            List<String> synonyms,
            List<String> requiredQuestions,
            List<String> evidenceTypes,
            int minEvidence
    ) {
    }
}
