package com.hmrag.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.web.dto.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DomainSetupAssistantService {

    private static final Logger log = LoggerFactory.getLogger(DomainSetupAssistantService.class);
    private static final int MAX_ASSISTANT_ROUNDS = 3;
    private static final List<String> DIMENSIONS = List.of(
            "领域边界与知识对象",
            "智能体使用场景",
            "领域知识精炼策略",
            "正文证据与可回溯性要求",
            "幻觉风险与知识约束",
            "评估与维护"
    );

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public DomainSetupAssistantService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public ApiDtos.DomainSetupAssistantResponse assist(ApiDtos.DomainSetupAssistantRequest request) {
        List<ApiDtos.DomainSetupAssistantMessage> history = request.history() == null ? List.of() : request.history();
        if (!isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "领域引导模型未配置完成，请检查 hmrag.domain-knowledge.refinement-llm"
            );
        }
        try {
            return callLlm(request.name(), history);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Domain setup assistant LLM call failed for domain={}", request.name(), ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "领域引导模型调用失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    ex
            );
        }
    }

    public void assistStream(ApiDtos.DomainSetupAssistantRequest request, OutputStream outputStream) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            writeStreamEvent(writer, "status", Map.of(
                    "phase", "started",
                    "message", "已提交领域引导请求，正在连接模型..."
            ));
            if (!isEnabled()) {
                writeStreamEvent(writer, "error", Map.of(
                        "message", "领域引导模型未配置完成，请检查 hmrag.domain-knowledge.refinement-llm"
                ));
                return;
            }
            try {
                ApiDtos.DomainSetupAssistantResponse response = streamLlm(
                        request.name(),
                        safeHistory(request),
                        preview -> writeStreamEvent(writer, "delta", Map.of(
                                "preview", preview,
                                "content", preview
                        ))
                );
                writeStreamEvent(writer, "result", objectMapper.convertValue(response, new TypeReference<>() {}));
            } catch (IOException ex) {
                if (isClientAbort(ex)) {
                    log.warn("Domain setup assistant stream disconnected by client: domain={}, message={}", request.name(), ex.getMessage());
                    return;
                }
                throw ex;
            } catch (Exception ex) {
                log.error("Domain setup assistant streaming failed for domain={}", request.name(), ex);
                writeStreamEvent(writer, "error", Map.of(
                        "message", "领域引导模型调用失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
                ));
            }
        }
    }

    private ApiDtos.DomainSetupAssistantResponse callLlm(String domainName, List<ApiDtos.DomainSetupAssistantMessage> history) throws IOException {
        String raw = switch (provider()) {
            case "openai_compatible", "openai-compatible" -> callOpenAiCompatible(domainName, history);
            case "ollama" -> callOllama(domainName, history);
            default -> throw new IllegalStateException("Unsupported domain setup provider: " + provider());
        };
        Map<String, Object> parsed = parseJsonObject(raw);
        return normalizeResponse(new ApiDtos.DomainSetupAssistantResponse(
                textValue(parsed.get("question")),
                textValue(parsed.get("goal")),
                textValue(parsed.get("description")),
                stringList(parsed.get("seedQueries")),
                stringList(parsed.get("excludeTerms")),
                textValue(parsed.get("currentDimension")),
                stringList(parsed.get("coveredDimensions")),
                textValue(parsed.get("nextDimension")),
                Boolean.TRUE.equals(parsed.get("ready")),
                true,
                "LLM"
        ), history);
    }

    private ApiDtos.DomainSetupAssistantResponse streamLlm(
            String domainName,
            List<ApiDtos.DomainSetupAssistantMessage> history,
            StreamPreviewConsumer previewConsumer
    ) throws IOException {
        String raw = switch (provider()) {
            case "openai_compatible", "openai-compatible" -> callOpenAiCompatibleStreaming(domainName, history, previewConsumer);
            case "ollama" -> callOllamaStreaming(domainName, history, previewConsumer);
            default -> throw new IllegalStateException("Unsupported domain setup provider: " + provider());
        };
        Map<String, Object> parsed = parseJsonObject(raw);
        return normalizeResponse(new ApiDtos.DomainSetupAssistantResponse(
                textValue(parsed.get("question")),
                textValue(parsed.get("goal")),
                textValue(parsed.get("description")),
                stringList(parsed.get("seedQueries")),
                stringList(parsed.get("excludeTerms")),
                textValue(parsed.get("currentDimension")),
                stringList(parsed.get("coveredDimensions")),
                textValue(parsed.get("nextDimension")),
                Boolean.TRUE.equals(parsed.get("ready")),
                true,
                "LLM"
        ), history);
    }

    private String callOpenAiCompatible(String domainName, List<ApiDtos.DomainSetupAssistantMessage> history) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config().model());
        body.put("temperature", 0.2);
        body.put("max_tokens", maxCompletionTokens());
        body.put("max_completion_tokens", maxCompletionTokens());
        body.put("enable_thinking", setupAssistantEnableThinking());
        body.put("response_format", Map.of("type", "text"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(domainName, history))
        ));
        JsonNode root = sendJson(baseUrl() + "/chat/completions", body, config().apiKey());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Domain setup assistant response missing content");
        }
        return content.asText();
    }

    private String callOpenAiCompatibleStreaming(
            String domainName,
            List<ApiDtos.DomainSetupAssistantMessage> history,
            StreamPreviewConsumer previewConsumer
    ) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config().model());
        body.put("temperature", 0.2);
        body.put("max_tokens", maxCompletionTokens());
        body.put("max_completion_tokens", maxCompletionTokens());
        body.put("enable_thinking", setupAssistantEnableThinking());
        body.put("response_format", Map.of("type", "text"));
        body.put("stream", true);
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(domainName, history))
        ));
        return sendOpenAiStream(baseUrl() + "/chat/completions", body, config().apiKey(), previewConsumer);
    }

    private String callOllama(String domainName, List<ApiDtos.DomainSetupAssistantMessage> history) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config().model());
        body.put("stream", false);
        body.put("format", "json");
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(domainName, history))
        ));
        JsonNode root = sendJson(baseUrl() + "/api/chat", body, null);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Domain setup assistant response missing content");
        }
        return content.asText();
    }

    private String callOllamaStreaming(
            String domainName,
            List<ApiDtos.DomainSetupAssistantMessage> history,
            StreamPreviewConsumer previewConsumer
    ) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config().model());
        body.put("stream", true);
        body.put("format", "json");
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(domainName, history))
        ));
        return sendOllamaStream(baseUrl() + "/api/chat", body, previewConsumer);
    }

    private String buildSystemPrompt() {
        return """
                # Role
                你是“面向领域知识库构建的苏格拉底引导者”。

                # Project Context
                你当前服务的是一个已经具备以下能力的本地知识系统：
                - 已有本地文件全文检索库
                - 已有文档切片、知识单元、向量索引、正文回溯能力
                - 已有面向 LLM / 智能体消费的知识包设计方向
                - 重点不是“是否做知识库”，而是“如何围绕指定领域，把现有索引与知识精炼流程组织成高质量领域库”

                因此，你不要再追问这些已知前提，也不要讨论基础架构是否存在。
                你的任务是基于这些既有条件，帮助用户把“该领域库应该如何定义、组织、约束、评估”想清楚。

                # 用户输入
                用户会给出一个领域或主题名称。
                你必须完整保留用户给出的名称，不得擅自缩写、泛化、改写或只抽取其中一部分核心词。
                例如：
                - 如果用户输入“研究生教育知识智能管理平台”，你不能擅自把它改成“研究生教育”
                - 如果用户输入“心血管内科医疗器械合规知识库”，你不能擅自把它改成“医疗器械合规”

                你必须先判断用户输入更接近哪一类对象：
                1. 纯学科/行业领域
                2. 业务主题
                3. 平台/系统/产品
                4. 平台或系统中的某个专题域

                如果用户输入包含“平台、系统、管理平台、门户、工作台、应用、产品、智能体平台、知识平台”等词，
                默认优先把它视为“平台/系统/产品”或“平台中的领域库”，
                你的追问必须围绕“这个平台/系统的知识库要支持哪些对象、角色、流程、规则、文档与决策”展开，
                而不是把它退化成一个宽泛学科。

                # Task
                你不要直接给出方案、代码或技术实现步骤。
                你的任务是通过苏格拉底式追问，引导用户逐步澄清：
                这个领域库到底要服务什么问题、边界是什么、知识如何组织、哪些证据必须保留、智能体该如何使用。

                # 已知前提（不要再问）
                以下内容视为系统中已经成立的事实，不要再把它们当成问题抛给用户：
                1. 系统已有全文检索、向量检索、分块和知识单元结构
                2. 系统支持正文回溯和证据引用
                3. 领域库最终主要为 LLM / 智能体提供支持
                4. 系统会做知识精炼，而不仅仅是原文搜索
                5. 系统存在人工触发和自动汇聚两类流程
                6. 系统已经有基础的数据接入与索引构建能力

                # 咨询轮次约束
                这不是无限追问对话，而是一轮“知识库构建澄清咨询”。
                你最多只能进行 3 轮提问，并且每轮都必须紧贴“如何定义这个领域库框架”。

                第 1 轮：
                - 只聚焦智能体任务、领域边界、核心知识对象

                第 2 轮：
                - 只聚焦知识组织方式、精炼对象、正文证据与回溯粒度

                第 3 轮：
                - 只聚焦幻觉风险、强约束规则、评估与维护方式
                - 第 3 轮后必须收口，形成一版可保存的领域库配置草稿

                # 重点追问维度
                你必须围绕“当前项目还不明确、但对领域库质量至关重要”的问题来问，优先级如下：

                1. 领域边界与知识对象
                - 这个领域库的边界到底是什么，不包括什么
                - 明确不纳入哪些相邻主题、相似概念、噪声方向、宣传性内容或无关业务对象
                - 核心知识对象是什么：实体、事件、流程、法规、原则、方法、争议点，还是时间线
                - 哪类关系最重要：包含、引用、约束、对比、演化、因果、适用条件
                - 如果用户给的是平台/系统名称，要优先追问平台内的核心对象、角色、业务流程、规则体系、文档体系与生命周期状态

                2. 智能体使用场景
                - 智能体将如何使用这个领域库
                - 主要是问答、检索增强、长文写作、专题研究、报告生成，还是多步分析
                - 它最需要“精炼结论”还是“正文证据”，还是两者配合

                3. 领域知识精炼策略
                - 这个领域更适合做“专题页”“概念页”“时间线页”“争议页”还是“规则页”
                - 哪些信息应该被长期沉淀为知识对象，哪些只适合作为原始证据保留
                - 哪些内容必须被总结，哪些内容不能过度压缩

                4. 正文证据与可回溯性要求
                - 哪些场景下智能体必须拿到原文
                - 需要回溯到什么粒度：段落、章节、页面、法规条款、表格附近上下文
                - 哪些输出必须带证据支撑，哪些可以只用精炼结论

                5. 幻觉风险与知识约束
                - 在该领域，LLM 最容易在哪些地方“自作聪明”
                - 哪类结论必须强约束为“没有证据就不能说”
                - 哪类信息允许总结归纳，哪类信息必须忠实贴近原文

                6. 评估与维护
                - 如何判断这个领域库真的对智能体有帮助
                - 除了准确率，还要看哪些指标：覆盖率、回溯充分性、长文支持度、专题一致性、冲突识别能力、时效性
                - 自动汇聚结果哪些可以直接采用，哪些必须人工确认

                # 行为准则
                1. 每次只问 1 到 3 个问题。
                2. 优先问“当前项目未知但关键”的问题，不要问已知事实。
                3. 如果用户回答过于宽泛、跳步、矛盾，直接指出并追问。
                4. 不要直接给方案；如果用户催你给方案，继续追问其假设和取舍。
                5. 要有阶段感：按三轮咨询推进，不得无限扩问。
                6. 你的问题必须始终围绕“如何把当前项目的既有能力组织成高质量领域库”。

                # 输出格式
                你必须只返回一个 JSON 对象，字段只能包含：
                question,goal,description,seedQueries,excludeTerms,currentDimension,coveredDimensions,nextDimension,ready

                字段要求：
                - question: 当前轮次要问用户的 1 到 3 个问题。可以是一个字符串，内部用编号或换行组织。总字数尽量控制在 120 字以内，不要写成长段。
                - goal: 当前已知条件下，这个领域库最终要帮助智能体做什么。尽量控制在 40 字以内。
                - description: 当前已知条件下，这个领域库应覆盖什么信息、如何组织、边界大致是什么。尽量控制在 80 字以内。
                - seedQueries: 3 到 5 条可直接用于检索和证据收集的问题表达。每条尽量控制在 28 字以内。
                - excludeTerms: 0 到 8 条当前已经明确应排除的对象、相邻主题、噪声方向或关键词。只有用户已明确表达过，才允许写入；不要臆造。
                - currentDimension: 当前这一轮主要聚焦的维度，必须是以下之一：
                  “领域边界与知识对象”“智能体使用场景”“领域知识精炼策略”“正文证据与可回溯性要求”“幻觉风险与知识约束”“评估与维护”
                - coveredDimensions: 你认为已经初步澄清或已经问过的维度列表。
                - nextDimension: 下一步最可能进入的维度；如果当前维度还没聊透，也可以与 currentDimension 相同。
                - ready: 只有当至少 4 个维度已经被初步澄清时才允许设为 true；否则必须为 false。

                # 强约束
                - 严禁输出解释、前言、后记、思维过程、推理过程，只能输出 JSON。
                - 第一轮不要问技术实现，必须优先问：
                  1) 这个领域库要服务什么样的智能体任务
                  2) 这个领域的知识边界和核心知识对象是什么
                - 你必须遵守最多 3 轮咨询；如果当前已是第 3 轮或之后，ready 必须为 true，question 必须是收口性确认或空字符串，不能继续发散追问。
                - 不要再问本系统已有的索引结构、切片能力、正文回溯能力是否存在。
                - goal 和 description 必须体现“构建领域知识库”，不能写成行业建设口号。
                - 必须完整保留用户输入名称；如果是平台/系统名称，问题必须体现该平台/系统，不得擅自抽象成更宽泛学科。
                """;
    }

    private String buildUserPrompt(String domainName, List<ApiDtos.DomainSetupAssistantMessage> history) {
        StringBuilder builder = new StringBuilder();
        int round = currentRound(history);
        builder.append("领域名称: ").append(domainName).append("\n");
        builder.append("注意：必须完整使用上述名称，不要自行缩写或泛化成更宽的学科名称。\n");
        builder.append("任务目标: 围绕该领域构建知识库，而不是讨论这个领域本身如何建设。\n");
        builder.append("当前咨询轮次: 第 ").append(round).append(" 轮，共 ").append(MAX_ASSISTANT_ROUNDS).append(" 轮。\n");
        builder.append("如果当前已是第 ").append(MAX_ASSISTANT_ROUNDS).append(" 轮，请收口形成一版可保存草稿，不要再发散追问。\n");
        builder.append("请根据已有对话判断当前处于哪个追问维度，并继续递进。\n");
        builder.append("如果用户已经明确说了哪些内容不属于该领域库，请把它们整理进 excludeTerms，而不是继续用于检索词。\n");
        builder.append("已有对话:\n");
        if (history.isEmpty()) {
            builder.append("- 暂无\n");
        } else {
            for (ApiDtos.DomainSetupAssistantMessage message : history) {
                builder.append("- ").append(message.role()).append(": ").append(message.content()).append("\n");
            }
        }
        builder.append("\n请返回下一步提问和当前配置草稿。");
        return builder.toString();
    }

    private JsonNode sendJson(String url, Map<String, Object> body, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, config().connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, config().requestTimeoutSeconds())).toMillis());
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
            throw new IllegalStateException("Domain setup assistant failed: HTTP " + status + " " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private String sendOpenAiStream(String url, Map<String, Object> body, String apiKey, StreamPreviewConsumer previewConsumer) throws IOException {
        HttpURLConnection connection = openJsonPostConnection(url, apiKey);
        try (var output = connection.getOutputStream()) {
            output.write(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        try (InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) {
                throw new IllegalStateException("Domain setup assistant failed: empty stream");
            }
            if (status < 200 || status >= 300) {
                String responseBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Domain setup assistant failed: HTTP " + status + " " + responseBody);
            }
            StringBuilder combined = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if (data.isBlank() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode event = objectMapper.readTree(data);
                    JsonNode delta = event.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        combined.append(delta.asText());
                        previewConsumer.accept(shortPreview(combined.toString()));
                    }
                }
            }
            return combined.toString();
        }
    }

    private String sendOllamaStream(String url, Map<String, Object> body, StreamPreviewConsumer previewConsumer) throws IOException {
        HttpURLConnection connection = openJsonPostConnection(url, null);
        try (var output = connection.getOutputStream()) {
            output.write(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        try (InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) {
                throw new IllegalStateException("Domain setup assistant failed: empty stream");
            }
            if (status < 200 || status >= 300) {
                String responseBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Domain setup assistant failed: HTTP " + status + " " + responseBody);
            }
            StringBuilder combined = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank()) {
                        continue;
                    }
                    JsonNode event = objectMapper.readTree(trimmed);
                    JsonNode content = event.path("message").path("content");
                    if (!content.isMissingNode() && !content.isNull()) {
                        combined.append(content.asText());
                        previewConsumer.accept(shortPreview(combined.toString()));
                    }
                }
            }
            return combined.toString();
        }
    }

    private HttpURLConnection openJsonPostConnection(String url, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout((int) Duration.ofSeconds(Math.max(1, config().connectTimeoutSeconds())).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(Math.max(5, config().requestTimeoutSeconds())).toMillis());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        if (apiKey != null && !apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        return connection;
    }

    private void writeStreamEvent(BufferedWriter writer, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("payload", payload);
        writer.write(objectMapper.writeValueAsString(envelope));
        writer.write("\n");
        writer.flush();
    }

    private boolean isClientAbort(IOException ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)) {
                return true;
            }
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("broken pipe")
                    || message.contains("connection reset")
                    || message.contains("software caused connection abort")
                    || message.contains("你的主机中的软件中止了一个已建立的连接")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<ApiDtos.DomainSetupAssistantMessage> safeHistory(ApiDtos.DomainSetupAssistantRequest request) {
        return request.history() == null ? List.of() : request.history();
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

    private boolean isEnabled() {
        return !provider().equals("disabled")
                && !baseUrl().isBlank()
                && !config().model().isBlank();
    }

    private String provider() {
        return config().provider() == null
                ? "disabled"
                : config().provider().trim().toLowerCase(Locale.ROOT);
    }

    private String baseUrl() {
        String raw = config().baseUrl() == null ? "" : config().baseUrl().replaceAll("/+$", "");
        if ("ollama".equals(provider()) && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private AppProperties.RefinementLlm config() {
        return appProperties.domainKnowledge().refinementLlm();
    }

    private int maxCompletionTokens() {
        return Math.max(64, appProperties.domainKnowledge().setupAssistantMaxCompletionTokens());
    }

    private boolean setupAssistantEnableThinking() {
        return appProperties.domainKnowledge().setupAssistantEnableThinking();
    }

    private ApiDtos.DomainSetupAssistantResponse normalizeResponse(ApiDtos.DomainSetupAssistantResponse raw) {
        return normalizeResponse(raw, List.of());
    }

    private ApiDtos.DomainSetupAssistantResponse normalizeResponse(
            ApiDtos.DomainSetupAssistantResponse raw,
            List<ApiDtos.DomainSetupAssistantMessage> history
    ) {
        String currentDimension = normalizeDimension(raw.currentDimension(), true);
        List<String> coveredDimensions = normalizeCoveredDimensions(raw.coveredDimensions(), currentDimension);
        String nextDimension = normalizeDimension(raw.nextDimension(), false);
        if (nextDimension == null) {
            nextDimension = currentDimension;
        }
        int round = currentRound(history);
        boolean forceFinalize = round >= MAX_ASSISTANT_ROUNDS;
        boolean ready = forceFinalize || (raw.ready() && coveredDimensions.size() >= 4);
        String question = safeText(raw.question());
        if (forceFinalize && question.length() > 60) {
            question = "如果这版领域库边界和重点无误，你可以直接保存，后续再进入自动汇聚与迭代。";
        }
        return new ApiDtos.DomainSetupAssistantResponse(
                question,
                safeText(raw.goal()),
                safeText(raw.description()),
                raw.seedQueries() == null ? List.of() : raw.seedQueries().stream().map(this::safeText).filter(s -> !s.isBlank()).limit(5).toList(),
                raw.excludeTerms() == null ? List.of() : raw.excludeTerms().stream().map(this::safeText).filter(s -> !s.isBlank()).limit(8).toList(),
                currentDimension,
                coveredDimensions,
                forceFinalize ? currentDimension : nextDimension,
                ready,
                raw.llmBacked(),
                raw.reason()
        );
    }

    private List<String> normalizeCoveredDimensions(List<String> rawCovered, String currentDimension) {
        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        if (rawCovered != null) {
            for (String item : rawCovered) {
                String normalizedItem = normalizeDimension(item, false);
                if (normalizedItem != null && seen.add(normalizedItem)) {
                    normalized.add(normalizedItem);
                }
            }
        }
        if (!currentDimension.isBlank() && seen.add(currentDimension)) {
            normalized.add(currentDimension);
        }
        return normalized;
    }

    private String normalizeDimension(String raw, boolean fallbackToFirst) {
        String text = safeText(raw);
        for (String dimension : DIMENSIONS) {
            if (dimension.equals(text) || (!text.isBlank() && dimension.contains(text)) || (!text.isBlank() && text.contains(dimension))) {
                return dimension;
            }
        }
        return fallbackToFirst ? DIMENSIONS.get(0) : null;
    }

    private String safeText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private int currentRound(List<ApiDtos.DomainSetupAssistantMessage> history) {
        long userAnswers = history == null ? 0 : history.stream()
                .filter(item -> "user".equalsIgnoreCase(item.role()))
                .count();
        return (int) Math.min(MAX_ASSISTANT_ROUNDS, userAnswers + 1);
    }

    private String shortPreview(String raw) {
        String normalized = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    @FunctionalInterface
    private interface StreamPreviewConsumer {
        void accept(String preview) throws IOException;
    }
}
