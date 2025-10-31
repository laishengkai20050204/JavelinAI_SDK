package com.example.ai;

import com.example.config.AiProperties;
import com.example.ai.tools.SpringAiToolAdapter;
import com.example.config.EffectiveProps;
import com.example.service.impl.StepContextStore;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SpringAiChatGateway {

    private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatModel chatModel;
    private final SpringAiToolAdapter toolAdapter;
    private final ObjectMapper mapper;
    private final AiProperties properties;
    private final StepContextStore stepStore; // 新增
    private final EffectiveProps effectiveProps;

    public Mono<String> call(Map<String, Object> payload, AiProperties.Mode mode) {
        return Mono.fromCallable(() -> executeCall(payload, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<String> stream(Map<String, Object> payload, AiProperties.Mode mode) {
        return Flux.defer(() -> executeStream(payload, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String executeCall(Map<String, Object> payload, AiProperties.Mode mode) {
        Prompt prompt = toPrompt(payload, mode);

        // 请求预览（日志 + 对象）
        ObjectNode reqPreview = logOutgoingPayloadJson(prompt, payload, mode);

        // 调模型
        ChatResponse response = chatModel.call(prompt);

        // 响应元信息 + 决策日志
        if (log.isDebugEnabled()) {
            logIncomingRawJson(response);      // usage / rate limit 等
            logAssistantDecision(response);    // 把 tool_calls 打出来
        }

        // 归一化为你现有的网关返回
        String out = formatResponse(response, mode);

        // （仅调试时）把 请求预览 / 原生元信息 / 助手决策 一起塞回 JSON
        if (log.isDebugEnabled()) {
            try {
                ObjectNode root = (ObjectNode) mapper.readTree(out);
                root.set("_provider_request", reqPreview);
                root.set("_provider_raw", mapper.valueToTree(extractProviderRaw(response)));
                root.set("_provider_assistant", buildAssistantDecisionNode(response)); // ← 就放这里
                out = mapper.writeValueAsString(root);
            } catch (Exception ignore) {}
        }
        return out;
    }




    private Flux<String> executeStream(Map<String, Object> payload, AiProperties.Mode mode) {
        Prompt prompt = toPrompt(payload, mode);
        if (log.isDebugEnabled() && prompt.getOptions() instanceof FunctionCallingOptions opts) {
            log.debug("Executing chat stream with model={} mode={}", opts.getModel(), mode);
        }
        return chatModel.stream(prompt)
                .map(response -> formatStreamChunk(response, mode));
    }

    private Prompt toPrompt(Map<String, Object> payload, AiProperties.Mode mode) {
        Object messagesObj = payload.get("messages");
        if (messagesObj == null) throw new IllegalArgumentException("messages is required");

        List<Map<String, Object>> messageMaps = convertMessagesObject(messagesObj);

        String seal = String.valueOf(payload.get("_tamperSeal"));
        String m3Digest = MsgTrace.digest(messageMaps);
        log.debug("[TRACE M3] gateway.in  size={} last={} digest={}",
                messageMaps.size(), MsgTrace.lastLine(messageMaps), m3Digest);


        // --- 新增：若上游已扁平化，则跳过 structured 插入 ---
        boolean flattened = "true".equalsIgnoreCase(String.valueOf(payload.get("_flattened")));

        // —— 铅封校验 #1：如果 flattened=true 但 m3 与 seal 不同，说明 Decision→Gateway 之间有人改了
        if (flattened && seal != null && !seal.isBlank() && !seal.equals(m3Digest)) {
            log.error("[TRACE M3] TAMPER between Decision and Gateway: seal={} m3={}", seal, m3Digest);
            // 可选：抛异常来抓堆栈
            // throw new IllegalStateException("Messages tampered before toPrompt()");
        }

        List<Message> messages = new ArrayList<>(
                messageMaps.stream().map(this::mapToMessage).filter(Objects::nonNull).toList()
        );


        if (!flattened) {
            Object structuredObj = payload.get("structuredToolMessages");
            if (structuredObj == null) structuredObj = payload.get("structured"); // 兼容别名

            if (structuredObj != null) {
                List<Map<String, Object>> structuredMaps = convertMessagesObject(structuredObj);
                List<Message> structuredMsgs = structuredMaps.stream()
                        .map(this::mapToMessage).filter(Objects::nonNull).toList();

                int insertAt = indexBeforeLastUser(messages);
                if (insertAt < 0) insertAt = messages.size();
                messages.addAll(insertAt, structuredMsgs);
                log.debug("[AI-REQ:STRUCTURED] inserted={} atIndex={}", structuredMsgs.size(), insertAt);
            }
        }
        // M4: 出参（已经 map 成 Spring AI 的 Message 对象，方便对比数量）
        log.debug("[TRACE M4] gateway.out size={} last={}",
                messages.size(),
                (messages.isEmpty() ? "<empty>" : messages.get(messages.size()-1)));

        FunctionCallingOptions options = buildOptions(payload, mode);
        return new Prompt(messages, options);
    }


    // 将即将发送给模型的请求以“OpenAI风格”JSON预览形式打印到日志（DEBUG级）
    private ObjectNode logOutgoingPayloadJson(
            Prompt prompt, Map<String, Object> originalPayload, AiProperties.Mode mode) {

        if (!log.isDebugEnabled()) return mapper.createObjectNode();

        ObjectNode preview = buildOutgoingPayloadPreview(prompt, originalPayload, mode); // ← 把你现有方法体抽出来
        try {
            log.debug("[AI-REQ] {}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(preview));
        } catch (Exception ignore) {}

        return preview;
    }

    private ObjectNode buildOutgoingPayloadPreview(
            Prompt prompt, Map<String, Object> originalPayload, AiProperties.Mode mode) {

        ObjectNode root = mapper.createObjectNode();

        // model
//        Object model = originalPayload.getOrDefault("model", properties.getModel());
        // ★ 请求级覆盖模型名：
        String modelFromPayload = coerceString(originalPayload.get("model"));
        String model = StringUtils.hasText(modelFromPayload) ? modelFromPayload : effectiveProps.model();
        if (StringUtils.hasText(model)) {
            root.put("model", model);
        }

        // tool_choice
        Object rawToolChoice = originalPayload.containsKey("toolChoice")
                ? originalPayload.get("toolChoice")
                : originalPayload.get("tool_choice");
        if (rawToolChoice == null) {
            root.put("tool_choice", "auto");
        } else if (rawToolChoice instanceof String s) {
            root.put("tool_choice", s);
        } else {
            root.set("tool_choice", mapper.valueToTree(rawToolChoice));
        }

        // 显式回显代理/并行设置（与你的 buildOptions 一致）
        root.put("proxy_tool_calls", true);
        root.put("parallel_tool_calls", false);

        // tool_context
        ObjectNode toolCtx = root.putObject("tool_context");
        if (originalPayload.get("userId") != null) {
            toolCtx.put("userId", String.valueOf(originalPayload.get("userId")));
        }
        if (originalPayload.get("conversationId") != null) {
            toolCtx.put("conversationId", String.valueOf(originalPayload.get("conversationId")));
        }

        // messages
        ArrayNode msgs = root.putArray("messages");
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage sm) {
                ObjectNode n = msgs.addObject();
                n.put("role", "system");
                n.put("content", sm.getContent() == null ? "" : sm.getContent());
            } else if (m instanceof UserMessage um) {
                ObjectNode n = msgs.addObject();
                n.put("role", "user");
                n.put("content", um.getContent() == null ? "" : um.getContent());
            } else if (m instanceof AssistantMessage am) {
                ObjectNode n = msgs.addObject();
                n.put("role", "assistant");
                n.put("content", am.getContent() == null ? "" : am.getContent());
                if (am.hasToolCalls()) {
                    ArrayNode tcs = n.putArray("tool_calls");
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        ObjectNode t = tcs.addObject();
                        t.put("id", tc.id());
                        t.put("type", tc.type());
                        ObjectNode fn = t.putObject("function");
                        fn.put("name", tc.name());
                        fn.put("arguments", tc.arguments());
                    }
                }
            } else if (m instanceof ToolResponseMessage tm) {
                for (ToolResponseMessage.ToolResponse tr : tm.getResponses()) {
                    ObjectNode n = msgs.addObject();
                    n.put("role", "tool");
                    n.put("tool_call_id", tr.id());
                    n.put("name", tr.name() == null ? "" : tr.name());
                    n.put("content", tr.responseData() == null ? "" : tr.responseData());
                }
            }
        }

        // ====== tools ======
        ArrayNode tools = root.putArray("tools");

        // 1) 先拿请求体定义（tools + clientTools）
        List<ToolDef> toolDefs = parseAllToolDefs(originalPayload);

        String normalizedToolChoice = normalizeToolChoice(rawToolChoice);
        String forcedFunction = forcedFunctionName(rawToolChoice);
        Set<String> allowed = buildAllowedFunctions(toolDefs, forcedFunction, normalizedToolChoice);

        // 为了避免重复
        Set<String> added = new LinkedHashSet<>();

        // 1.1 请求体里的定义（如果在 allowed 里才展示）
        for (ToolDef def : toolDefs) {
            if (!allowed.isEmpty() && !allowed.contains(def.name())) continue;
            appendToolNode(tools, def.name(), def.desc(), def.schema(), def.execTarget());
            added.add(def.name());
        }

        // 2) 回落到“服务器注册”的工具（FunctionCallback）
        //    ——如果用户没有在 payload 里显式给 schema，就从回调里取 schema/desc
        for (FunctionCallback cb : toolAdapter.functionCallbacks()) {
            String name = cb.getName();
            if (!allowed.isEmpty() && !allowed.contains(name)) continue;
            if (added.contains(name)) continue;

            JsonNode schema = safeParseSchema(cb.getInputTypeSchema());
            String desc = cb.getDescription();
            appendToolNode(tools, name, desc, schema, "server");
            added.add(name);
        }

        // temperature（如有）
        Object temperature = originalPayload.get("temperature");
        if (temperature instanceof Number number) {
            root.put("temperature", number.doubleValue());
        } else if (temperature instanceof String str && org.springframework.util.StringUtils.hasText(str)) {
            try { root.put("temperature", Double.parseDouble(str)); } catch (NumberFormatException ignored) {}
        }

        AiProperties.Mode effMode = effectiveProps.mode(); // 或 modeOr(mode)
        root.put("compatibility", effMode.toString());
        return root;
    }

    private void appendToolNode(ArrayNode tools, String name, String desc, JsonNode schema, String execTarget) {
        ObjectNode t = tools.addObject();
        t.put("type", "function");
        ObjectNode fn = t.putObject("function");
        fn.put("name", name);
        if (org.springframework.util.StringUtils.hasText(desc)) {
            fn.put("description", desc);
        }
        fn.set("parameters", schema != null ? schema : mapper.createObjectNode());
        t.put("x-execTarget", execTarget != null ? execTarget : "server");
    }

    private JsonNode safeParseSchema(String schemaStr) {
        if (!org.springframework.util.StringUtils.hasText(schemaStr)) {
            // 默认空 schema：一个空对象
            return mapper.createObjectNode().put("type", "object");
        }
        try {
            return mapper.readTree(schemaStr);
        } catch (Exception e) {
            // 解析失败也给个兜底
            return mapper.createObjectNode().put("type", "object");
        }
    }



    private List<Map<String, Object>> convertMessagesObject(Object messagesObj) {
        if (messagesObj instanceof List<?> rawList) {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Object element : rawList) {
                if (element instanceof Map<?, ?> map) {
                    converted.add(castToMap(map));
                } else if (element instanceof JsonNode node) {
                    converted.add(mapper.convertValue(node, MAP_TYPE));
                } else if (element != null) {
                    throw new IllegalArgumentException("Unsupported message element type: " + element.getClass());
                }
            }
            return converted;
        }
        if (messagesObj instanceof JsonNode node) {
            return mapper.convertValue(node, MESSAGE_LIST_TYPE);
        }
        throw new IllegalArgumentException("messages must be a list");
    }

    private FunctionCallingOptions buildOptions(Map<String, Object> payload, AiProperties.Mode mode) {
        AiProperties.Mode effMode = effectiveProps.modeOr(mode);
        OptionsBuilder builder = (effMode == AiProperties.Mode.OPENAI)
                ? new OpenAiOptionsBuilder(OpenAiChatOptions.builder())
                : new GenericOptionsBuilder(FunctionCallingOptions.builder());

        String modelFromPayload = coerceString(payload.get("model"));
        String effectiveModel = StringUtils.hasText(modelFromPayload) ? modelFromPayload : effectiveProps.model();
        if (StringUtils.hasText(effectiveModel)) {
            builder.model(effectiveModel);
        }

        Object temperature = payload.get("temperature");
        if (temperature instanceof Number number) {
            builder.temperature(number.doubleValue());
        } else if (temperature instanceof String str && StringUtils.hasText(str)) {
            try { builder.temperature(Double.parseDouble(str)); } catch (NumberFormatException ignored) {}
        }

        Object rawToolChoice = payload.containsKey("toolChoice")
                ? payload.get("toolChoice")
                : payload.get("tool_choice");
        String normalizedToolChoice = normalizeToolChoice(rawToolChoice);
        String forcedFunction = forcedFunctionName(rawToolChoice);

        List<ToolDef> toolDefs = parseAllToolDefs(payload);
        Set<String> allowed = buildAllowedFunctions(toolDefs, forcedFunction, normalizedToolChoice);

        List<FunctionCallback> serverCallbacks = toolAdapter.functionCallbacks().stream()
                .filter(cb -> allowed.contains(cb.getName()))
                .toList();
        List<FunctionCallback> clientDefCallbacks = buildCallbacks(toolDefs, allowed, serverCallbacks);

        boolean hasClientTools = !clientDefCallbacks.isEmpty();

//        if (hasClientTools) {
//            builder.proxyToolCalls(Boolean.TRUE);   // 外部化（v2 用）
//        } else {
//            builder.proxyToolCalls(Boolean.FALSE);  // 内部执行（/ai/decide 用）
//        }
        // 始终外部化 tool-calls：模型只“提出调用”，真正执行由你的编排完成
        builder.proxyToolCalls(Boolean.TRUE);

        List<FunctionCallback> allCallbacks = new ArrayList<>(serverCallbacks.size() + clientDefCallbacks.size());
        allCallbacks.addAll(serverCallbacks);
        allCallbacks.addAll(clientDefCallbacks);

        builder.functionCallbacks(allCallbacks);
        builder.functions(allowed);

        if (!allowed.isEmpty()) {
            // ✅ 稳定起见，禁用并行工具
            builder.parallelToolCalls(Boolean.FALSE);
        }

        if (rawToolChoice != null) builder.toolChoice(rawToolChoice);

        Map<String, Object> toolContext = new HashMap<>();
        Object scopeUser = payload.get("userId");
        Object scopeConversation = payload.get("conversationId");
        if (scopeUser != null) toolContext.put("userId", scopeUser);
        if (scopeConversation != null) toolContext.put("conversationId", scopeConversation);
        if (!toolContext.isEmpty()) builder.toolContext(toolContext);

        log.debug("[TOOLS-ALLOWED] {}", allowed);
        log.debug("[CB-REGISTERED] server={}, client-def={}",
                serverCallbacks.stream().map(FunctionCallback::getName).toList(),
                clientDefCallbacks.stream().map(FunctionCallback::getName).toList());
        log.debug("[TOOL-CHOICE] {}", rawToolChoice);

        return builder.build();
    }

    private Set<String> buildAllowedFunctions(List<ToolDef> defs,
                                              @Nullable String forcedFunction,
                                              String normalizedToolChoice) {
        // 1) 显式禁用：不允许任何函数
        if ("none".equals(normalizedToolChoice)) return Collections.emptySet();

        // 2) 强制单函数：仅允许该函数
        if (forcedFunction != null && StringUtils.hasText(forcedFunction)) {
            LinkedHashSet<String> forced = new LinkedHashSet<>();
            forced.add(forcedFunction);
            return forced;
        }

        // 3) 并集策略：请求声明的函数 ∪ 服务端已注册的全部工具
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (ToolDef def : defs) {
            if (StringUtils.hasText(def.name())) allowed.add(def.name());
        }
        Set<String> serverAll = toolAdapter.functionCallbacks().stream()
                .map(FunctionCallback::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        allowed.addAll(serverAll);
        return allowed;
    }

    private List<ToolDef> parseAllToolDefs(Map<String, Object> payload) {
        Map<String, ToolDef> merged = new LinkedHashMap<>();
        collectToolDefs(payload.get("tools"), merged);
        collectToolDefs(payload.get("clientTools"), merged);
        return new ArrayList<>(merged.values());
    }

    private void collectToolDefs(Object toolsObj, Map<String, ToolDef> merged) {
        if (!(toolsObj instanceof List<?> list)) return;
        for (Object item : list) {
            Map<String, Object> tool = castToMap(item);
            if (tool == null) continue;
            Map<String, Object> function = castToMap(tool.get("function"));
            if (function == null) continue;
            String name = asString(function.get("name"));
            if (!StringUtils.hasText(name)) continue;
            Object descriptionObj = function.containsKey("description") ? function.get("description") : tool.get("description");
            String description = descriptionObj != null ? descriptionObj.toString() : "";
            JsonNode schema = mapper.valueToTree(function.get("parameters"));
            String execTarget = resolveExecTarget(function.get("x-execTarget"), tool.get("x-execTarget"));
            merged.put(name, new ToolDef(name, description, schema, execTarget));
        }
    }

    private String resolveExecTarget(Object functionLevel, Object toolLevel) {
        String functionTarget = normalizeExecTarget(asString(functionLevel));
        if (functionTarget != null) return functionTarget;
        String toolTarget = normalizeExecTarget(asString(toolLevel));
        if (toolTarget != null) return toolTarget;
        return "server";
    }

    private String normalizeExecTarget(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private @Nullable String forcedFunctionName(Object toolChoice) {
        if (toolChoice instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String str && "function".equalsIgnoreCase(str.trim())) {
                Map<String, Object> function = castToMap(map.get("function"));
                if (function != null) {
                    String name = asString(function.get("name"));
                    return StringUtils.hasText(name) ? name : null;
                }
            }
        }
        return null;
    }

    private List<FunctionCallback> buildCallbacks(List<ToolDef> defs,
                                                  Set<String> allowed,
                                                  List<FunctionCallback> serverCallbacks) {
        if (allowed.isEmpty() || defs.isEmpty()) return Collections.emptyList();
        Set<String> serverNames = serverCallbacks.stream().map(FunctionCallback::getName).collect(Collectors.toSet());
        List<FunctionCallback> placeholders = new ArrayList<>();
        for (ToolDef def : defs) {
            if (!allowed.contains(def.name())) continue;
            if (!"client".equalsIgnoreCase(def.execTarget())) continue;
            if (serverNames.contains(def.name())) continue;
            placeholders.add(new FrontendDefinitionCallback(def));
        }
        return placeholders;
    }

    private String normalizeToolChoice(Object toolChoice) {
        if (toolChoice instanceof String str && StringUtils.hasText(str)) {
            return str.trim().toLowerCase(Locale.ROOT);
        }
        return "auto";
    }

    private Message mapToMessage(Map<String, Object> source) {
        if (source == null) return null;
        String role = asString(source.get("role"));
        Object content = source.get("content");
        if ("system".equalsIgnoreCase(role)) return new SystemMessage(normalizeContent(content));
        if ("user".equalsIgnoreCase(role)) return new UserMessage(normalizeContent(content));
        if ("assistant".equalsIgnoreCase(role)) {
            String text = normalizeContent(content);
            List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(source.get("tool_calls"));
            Map<String, Object> metadata = castToMap(source.get("metadata"));
            if (metadata == null) metadata = new HashMap<>();
            return new AssistantMessage(text, metadata, toolCalls);
        }
        if ("tool".equalsIgnoreCase(role)) {
            String id   = asString(source.get("tool_call_id"));
            String name = asString(source.get("name"));

            // 先拿 content 字段（按你的设计应是 DB 的 content 列）
            String data = normalizeContent(source.get("content"));

            // ★ 兜底：如果 content 为空，再从 payload/data.payload.value 等常见位置提取
            if (!org.springframework.util.StringUtils.hasText(data)) {
                Object payload = source.get("payload");               // 你存进 DB 的 payload（JSON）
                if (payload instanceof Map<?,?> pm) {
                    // 典型结构：{ data: { payload: { type:"text", value:"..." }, _executedKey: "..." }, ... }
                    Object dataObj = pm.get("data");
                    Map<?,?> dataMap = (dataObj instanceof Map<?,?> dm) ? dm : pm; // 有的直接是 payload
                    Object inner = dataMap.get("payload");
                    if (inner instanceof Map<?,?> im) {
                        Object v = im.get("value");
                        if (v instanceof String sv && org.springframework.util.StringUtils.hasText(sv)) {
                            data = sv; // 成功提到 "debug from tool"
                        } else {
                            // 再兜一层：value 没有就把整个 payload 序列化成文本给模型看
                            try { data = mapper.writeValueAsString(im); } catch (Exception ignore) {}
                        }
                    }
                }
            }

            ToolResponseMessage.ToolResponse resp = new ToolResponseMessage.ToolResponse(
                    (id != null ? id : "tool-" + System.nanoTime()),
                    (name != null ? name : ""),
                    (data != null ? data : "")
            );
            return new ToolResponseMessage(java.util.List.of(resp));
        }
        return new UserMessage(normalizeContent(content));
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(Object toolCallsObj) {
        if (toolCallsObj == null) return Collections.emptyList();
        if (toolCallsObj instanceof List<?> list) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"));
                    String type = asString(map.get("type"));
                    Map<String, Object> fn = castToMap(map.get("function"));
                    String name = fn != null ? asString(fn.get("name")) : "";
                    String arguments = fn != null ? asString(fn.get("arguments")) : "{}";
                    calls.add(new AssistantMessage.ToolCall(
                            id != null ? id : "call-" + System.nanoTime(),
                            type != null ? type : "function",
                            name,
                            arguments != null ? arguments : "{}"
                    ));
                }
            }
            return calls;
        }
        return Collections.emptyList();
    }

    private String formatResponse(ChatResponse response, AiProperties.Mode mode) {
        Generation generation = response.getResult();
        AssistantMessage message = generation.getOutput();
        String content = message != null ? message.getContent() : "";

        ObjectNode root = mapper.createObjectNode();
        ObjectNode messageNode = root.putObject("message");
        messageNode.put("role", MessageType.ASSISTANT.toString().toLowerCase(Locale.ROOT));
        messageNode.put("content", content != null ? content : "");
        messageNode.put("thinking", "");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode choiceMessage = choice.putObject("message");
        choiceMessage.put("role", "assistant");
        choiceMessage.put("content", content != null ? content : "");

        if (message != null && message.hasToolCalls()) {
            ArrayNode toolCallsNode = choiceMessage.putArray("tool_calls");
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                ObjectNode toolCallNode = toolCallsNode.addObject();
                toolCallNode.put("id", call.id());
                toolCallNode.put("type", call.type());
                ObjectNode fnNode = toolCallNode.putObject("function");
                fnNode.put("name", call.name());
                fnNode.put("arguments", call.arguments());
            }
        }

        choice.put("finish_reason", "stop");

        // 👇 可选：把 provider 原始信息塞进返回，方便前端/你直接看到“原生”
        if (properties.getDebug() != null && properties.getDebug().isIncludeRawInGatewayJson()) {
            Map<String, Object> raw = extractProviderRaw(response);
            if (!raw.isEmpty()) {
                try {
                    root.set("_provider_raw", mapper.valueToTree(raw));
                } catch (Exception ignore) {}
            }
        }

        return root.toString();
    }


    private String formatStreamChunk(ChatResponse response, AiProperties.Mode mode) {
        Generation generation = response.getResult();
        AssistantMessage message = generation.getOutput();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode delta = choice.putObject("delta");

        if (message != null) {
            String content = message.getContent();
            if (StringUtils.hasText(content)) delta.put("content", content);
            if (message.hasToolCalls()) {
                ArrayNode toolCalls = delta.putArray("tool_calls");
                for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                    ObjectNode toolCallNode = toolCalls.addObject();
                    toolCallNode.put("id", call.id());
                    toolCallNode.put("type", call.type());
                    ObjectNode fnNode = toolCallNode.putObject("function");
                    fnNode.put("name", call.name());
                    fnNode.put("arguments", call.arguments());
                }
            }
        }
        choice.put("index", 0);
        return root.toString();
    }

    private String normalizeContent(Object content) {
        if (content == null) return "";
        if (content instanceof String str) return str;
        if (content instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text == null) text = map.get("content");
                    if (text == null) text = map.get("value");
                    if (text != null) builder.append(text);
                } else if (item != null) builder.append(item);
            }
            return builder.toString();
        }
        if (content instanceof JsonNode node) {
            return node.isTextual() ? node.asText() : node.toString();
        }
        return content.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new HashMap<>();
            raw.forEach((key, val) -> result.put(key == null ? null : key.toString(), val));
            return result;
        }
        if (value instanceof JsonNode node) {
            return mapper.convertValue(node, MAP_TYPE);
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private record ToolDef(String name, String desc, JsonNode schema, String execTarget) {}

    private interface OptionsBuilder {
        void model(String model);
        void temperature(Double temperature);
        void functionCallbacks(List<FunctionCallback> callbacks);
        void functions(Set<String> names);
        void proxyToolCalls(Boolean proxy);
        void toolContext(Map<String, Object> context);
        void toolChoice(Object toolChoice);
        void parallelToolCalls(Boolean parallel);
        FunctionCallingOptions build();
    }

    private static final class GenericOptionsBuilder implements OptionsBuilder {
        private final FunctionCallingOptions.Builder delegate;
        private GenericOptionsBuilder(FunctionCallingOptions.Builder delegate) { this.delegate = delegate; }
        @Override public void model(String model) { delegate.model(model); }
        @Override public void temperature(Double temperature) { delegate.temperature(temperature); }
        @Override public void functionCallbacks(List<FunctionCallback> callbacks) { delegate.functionCallbacks(callbacks); }
        @Override public void functions(Set<String> names) { delegate.functions(names); }
        @Override public void proxyToolCalls(Boolean proxy) { delegate.proxyToolCalls(proxy); }
        @Override public void toolContext(Map<String, Object> context) { delegate.toolContext(context); }
        @Override public void toolChoice(Object toolChoice) { /* not supported */ }
        @Override public void parallelToolCalls(Boolean parallel) { /* not supported */ }
        @Override public FunctionCallingOptions build() { return delegate.build(); }
    }

    private static final class OpenAiOptionsBuilder implements OptionsBuilder {
        private final OpenAiChatOptions.Builder delegate;
        private OpenAiOptionsBuilder(OpenAiChatOptions.Builder delegate) { this.delegate = delegate; }
        @Override public void model(String model) { delegate.model(model); }
        @Override public void temperature(Double temperature) { delegate.temperature(temperature); }
        @Override public void functionCallbacks(List<FunctionCallback> callbacks) { delegate.functionCallbacks(callbacks); }
        @Override public void functions(Set<String> names) { delegate.functions(names); }
        @Override public void proxyToolCalls(Boolean proxy) { delegate.proxyToolCalls(proxy); }
        @Override public void toolContext(Map<String, Object> context) { delegate.toolContext(context); }
        @Override public void toolChoice(Object toolChoice) {
            if (toolChoice == null) return;
            if (toolChoice instanceof String str) delegate.toolChoice(str);
            else delegate.toolChoice(toolChoice);
        }
        @Override public void parallelToolCalls(Boolean parallel) { delegate.parallelToolCalls(parallel); }
        @Override public FunctionCallingOptions build() { return delegate.build(); }
    }

    private class FrontendDefinitionCallback implements FunctionCallback {
        private final ToolDef toolDef;
        private final String schema;
        private FrontendDefinitionCallback(ToolDef toolDef) {
            this.toolDef = toolDef; this.schema = toolDef.schema() != null ? toolDef.schema().toString() : "{}";
        }
        @Override public String getName() { return toolDef.name(); }
        @Override public String getDescription() { return toolDef.desc() != null ? toolDef.desc() : ""; }
        @Override public String getInputTypeSchema() { return schema; }
        @Override public String call(String argumentsJson) { throw new UnsupportedOperationException("frontend tool: " + toolDef.name()); }
        @Override public String call(String argumentsJson, ToolContext context) { throw new UnsupportedOperationException("frontend tool: " + toolDef.name()); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object meta) {
        if (meta == null) return Collections.emptyMap();
        if (meta instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        try {
            return mapper.convertValue(meta, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            return Map.of("value", String.valueOf(meta));
        }
    }

    private Map<String, Object> extractProviderRaw(org.springframework.ai.chat.model.ChatResponse response) {
        Map<String, Object> raw = new LinkedHashMap<>();
        try {
            Object respMeta = (response != null) ? response.getMetadata() : null;
            if (respMeta != null) raw.put("response_metadata", asMap(respMeta));
        } catch (Exception ignore) {}
        try {
            List<org.springframework.ai.chat.model.Generation> gens =
                    (response != null) ? response.getResults() : null;
            if (gens != null && !gens.isEmpty()) {
                Object genMeta = gens.get(0).getMetadata();
                if (genMeta != null) raw.put("generation_metadata", asMap(genMeta));
            }
        } catch (Exception ignore) {}
        return raw;
    }

    private void logIncomingRawJson(org.springframework.ai.chat.model.ChatResponse response) {
        try {
            Map<String, Object> raw = extractProviderRaw(response);
            if (!raw.isEmpty()) {
                log.debug("[AI-RESP] {}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
            } else {
                log.debug("[AI-RESP] <no metadata available>");
            }
        } catch (Exception e) {
            log.debug("[AI-RESP] <failed to serialize>: {}", e.toString());
        }
    }

    private void logAssistantDecision(ChatResponse response) {
        try {
            ObjectNode n = buildAssistantDecisionNode(response);
            if (n.size() > 0) {
                log.debug("[AI-RESP:MSG] {}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n));
            } else {
                log.debug("[AI-RESP:MSG] <no assistant output>");
            }
        } catch (Exception e) {
            log.debug("[AI-RESP:MSG] <failed to serialize>: {}", e.toString());
        }
    }

    private ObjectNode buildAssistantDecisionNode(ChatResponse response) {
        ObjectNode n = mapper.createObjectNode();
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) return n;

        Generation gen = response.getResults().get(0);
        if (gen == null || gen.getOutput() == null) return n;

        AssistantMessage am = gen.getOutput();
        n.put("role", "assistant");
        n.put("content", am.getContent() == null ? "" : am.getContent());

        if (am.hasToolCalls()) {
            ArrayNode tcs = n.putArray("tool_calls");
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                ObjectNode t = tcs.addObject();
                t.put("id", tc.id());
                t.put("type", tc.type());
                ObjectNode fn = t.putObject("function");
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments());
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private String readableFromToolResult(Object data) {
        if (data == null) return "";
        if (data instanceof String s) return s;
        if (data instanceof Map<?, ?> m) {
            // 优先取 data.payload.value
            Object payload = m.get("payload");
            if (payload instanceof Map<?, ?> pm) {
                Object v = pm.get("value");
                if (v instanceof String sv && !sv.isBlank()) return sv;
            }
            // 退化：常见键位
            for (String k : List.of("value","text","content","message","delta")) {
                Object v = m.get(k);
                if (v instanceof String sv && !sv.isBlank()) return sv;
            }
            // 最后兜底：序列化成 JSON
            try { return mapper.writeValueAsString(m); } catch (Exception ignore) {}
            return String.valueOf(m);
        }
        if (data instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            for (Object x : it) {
                String part = readableFromToolResult(x);
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        return String.valueOf(data);
    }

    private int indexBeforeLastUser(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof UserMessage) {
                return i; // 把 structured 插到这条 user 之前
            }
        }
        return -1; // 没有 user，就追加到末尾
    }

    @Nullable
    private String coerceString(Object v) {
        if (v == null) return null;
        if (v instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) return null;
            return node.asText(); // 非文本也会转成字符串
        }
        String s = v.toString();
        return (s == null || s.isBlank()) ? null : s;
    }


}
