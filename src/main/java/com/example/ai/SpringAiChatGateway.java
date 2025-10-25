package com.example.ai;

import com.example.config.AiProperties;
import com.example.ai.tools.SpringAiToolAdapter;
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
        if (log.isDebugEnabled() && prompt.getOptions() instanceof FunctionCallingOptions opts) {
            log.debug("Executing chat call with model={} mode={}", opts.getModel(), mode);
        }
        ChatResponse response = chatModel.call(prompt);
        return formatResponse(response, mode);
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
        if (messagesObj == null) {
            throw new IllegalArgumentException("messages is required");
        }

        List<Map<String, Object>> messageMaps = convertMessagesObject(messagesObj);
        List<Message> messages = messageMaps.stream()
                .map(this::mapToMessage)
                .filter(Objects::nonNull)
                .toList();

        FunctionCallingOptions options = buildOptions(payload, mode);
        return new Prompt(messages, options);
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
        OptionsBuilder builder = mode == AiProperties.Mode.OPENAI
                ? new OpenAiOptionsBuilder(OpenAiChatOptions.builder())
                : new GenericOptionsBuilder(FunctionCallingOptions.builder());

        Object model = payload.getOrDefault("model", properties.getModel());
        if (model != null) builder.model(model.toString());

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

        if (hasClientTools) {
            builder.proxyToolCalls(Boolean.TRUE);   // 外部化（v2 用）
        } else {
            builder.proxyToolCalls(Boolean.FALSE);  // 内部执行（/ai/decide 用）
        }

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
        if ("none".equals(normalizedToolChoice)) return Collections.emptySet();
        if (forcedFunction != null && StringUtils.hasText(forcedFunction)) {
            LinkedHashSet<String> forced = new LinkedHashSet<>();
            forced.add(forcedFunction);
            return forced;
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (ToolDef def : defs) {
            if (StringUtils.hasText(def.name())) allowed.add(def.name());
        }
        if (allowed.isEmpty() && !"none".equals(normalizedToolChoice)) {
            Set<String> serverAll = toolAdapter.functionCallbacks().stream()
                    .map(FunctionCallback::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            allowed.addAll(serverAll);
        }
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
            String id = asString(source.get("tool_call_id"));
            String name = asString(source.get("name"));
            String data = normalizeContent(content);
            ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                    id != null ? id : "tool-" + System.nanoTime(),
                    name != null ? name : "",
                    data != null ? data : ""
            );
            return new ToolResponseMessage(List.of(response));
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

        if (mode == AiProperties.Mode.OLLAMA) {
            messageNode.put("model", Objects.toString(properties.getModel(), ""));
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
}
