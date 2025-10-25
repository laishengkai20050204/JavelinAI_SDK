package com.example.ai.tools;

import com.example.tools.AiTool;
import com.example.tools.ToolRegistry;
import com.example.tools.ToolResult;
import com.example.tools.impl.FindRelevantMemoryTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the existing {@link ToolRegistry} catalogue into Spring AI function-calling callbacks.
 *
 * <p>The adapter keeps the server-side execution semantics intact: JSON schema validation,
 * controlled propagation of scoped identifiers (userId, conversationId), and structured
 * response envelopes that carry both machine-readable JSON and a human-readable {@code text}
 * summary for LLM consumption.</p>
 *
 * <p>Sequence: model issues a tool call → Spring AI resolves the callback by name →
 *  parses/validates input →
 * {@link AiTool#execute(Map)} runs → result JSON is merged into the response envelope.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringAiToolAdapter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final List<String> PROTECTED_SCOPE_KEYS = List.of("userId", "conversationId");

    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public List<FunctionCallback> functionCallbacks() {
        return toolRegistry.allTools().stream()
                .map(this::wrapTool)
                .toList();
    }

    private FunctionCallback wrapTool(AiTool tool) {
        if (tool instanceof FindRelevantMemoryTool memoryTool) {
            return buildFindRelevantMemoryCallback(memoryTool);
        }
        return new DelegatingCallback(tool);
    }

    private FunctionCallback buildFindRelevantMemoryCallback(FindRelevantMemoryTool tool) {
        return new DelegatingCallback(tool) {
            @Override
            public String call(String argumentsJson, ToolContext context) {
                Map<String, Object> args = parseArguments(argumentsJson);
                if (context != null && context.getContext() != null) {
                    Map<String, Object> ctx = context.getContext();
                    ctx.forEach((key, value) -> args.putIfAbsent(key, value));
                }
                String userId = asString(args.get("userId"));
                String conversationId = asString(args.get("conversationId"));
                if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
                    log.debug("Skipping find_relevant_memory due to missing scope (userId={}, conversationId={})",
                            userId, conversationId);
                    return buildMissingScopeResponse(tool, args, List.of("userId", "conversationId"));
                }
                // enforce maxMessages sanity; relying on tool-side range check for detailed bounds.
                Object maxMessages = args.get("maxMessages");
                if (maxMessages instanceof Number number && number.intValue() <= 0) {
                    args.put("maxMessages", null);
                }
                return serializeResult(tool, args, context);
            }
        };
    }

    private class DelegatingCallback implements FunctionCallback {
        private final AiTool tool;
        private final String schema;

        private DelegatingCallback(AiTool tool) {
            this.tool = tool;
            this.schema = toSchema(tool);
        }

        @Override
        public String getName() {
            return tool.name();
        }

        @Override
        public String getDescription() {
            return tool.description();
        }

        @Override
        public String getInputTypeSchema() {
            return schema;
        }

        @Override
        public String call(String argumentsJson) {
            return call(argumentsJson, null);
        }

        @Override
        public String call(String argumentsJson, ToolContext context) {
            Map<String, Object> args = parseArguments(argumentsJson);
            return serializeResult(tool, args, context);
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson) || "{}".equals(argumentsJson.trim())) {
            return new HashMap<>();
        }
        try {
            return mapper.readValue(argumentsJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", e);
        }
    }

    private String serializeResult(AiTool tool, Map<String, Object> args, ToolContext context) {
        Map<String, Object> effectiveArgs = new HashMap<>(args != null ? args : Collections.emptyMap());
        if (context != null && context.getContext() != null) {
            context.getContext().forEach((key, value) -> {
                if (value != null && PROTECTED_SCOPE_KEYS.contains(key)) {
                    effectiveArgs.put(key, value);
                } else {
                    effectiveArgs.putIfAbsent(key, value);
                }
            });
        }
        ToolResult result;
        try {
            result = tool.execute(effectiveArgs);
        } catch (Exception e) {
            log.warn("Tool '{}' invocation failed", tool.name(), e);
            throw new IllegalStateException("Tool execution failed: " + tool.name(), e);
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("tool", tool.name());
        envelope.set("args", mapper.valueToTree(effectiveArgs));
        try {
            JsonNode payload = StringUtils.hasText(result.contentJson())
                    ? mapper.readTree(result.contentJson())
                    : mapper.nullNode();
            envelope.set("result", payload);
            envelope.put("text", payload.isTextual() ? payload.asText() : payload.toString());
        } catch (JsonProcessingException e) {
            log.debug("Tool '{}' produced non-JSON payload, returning raw text", tool.name());
            envelope.put("result_raw", result.contentJson());
            envelope.put("text", result.contentJson());
        }
        return envelope.toString();
    }

    private String buildMissingScopeResponse(AiTool tool, Map<String, Object> args, List<String> missingKeys) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("tool", tool.name());
        envelope.set("args", mapper.valueToTree(args));
        envelope.put("error", "missing required scope");
        envelope.set("missing", mapper.valueToTree(missingKeys));
        envelope.put("text", "Tool " + tool.name() + " skipped: missing required identifiers.");
        return envelope.toString();
    }

    private String toSchema(AiTool tool) {
        try {
            return mapper.writeValueAsString(tool.parametersSchema());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool schema for " + tool.name(), e);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
