package com.example.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiToolExecutor {

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    private static final Set<String> PROTECTED_SCOPE_KEYS = Set.of("userId", "conversationId");

    public record ToolCall(String id, String name, String argumentsJson) {}

    public Map<String, Object> toAssistantToolCallsMessage(List<ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ToolCall call : calls) {
            log.trace("Preparing assistant tool call message id={} name={}", call.id(), call.name());
            arr.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", Objects.requireNonNullElse(call.argumentsJson(), "{}")
                    )
            ));
        }
        return Map.of(
                "role", "assistant",
                "tool_calls", arr,
                "content", ""
        );
    }

    public List<Map<String, Object>> executeAll(List<ToolCall> calls,
                                                Map<String, Object> fallbackArgs) throws Exception {
        log.debug("Executing {} tool call(s)", calls.size());
        List<Map<String, Object>> results = new ArrayList<>();
        for (ToolCall call : calls) {
            log.debug("Executing tool call id={} name={}", call.id(), call.name());
            AiTool tool = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + call.name()));

            Map<String, Object> args = mapper.readValue(
                    call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            if (fallbackArgs != null) {
                // Enforce scoped identifiers from the server context to avoid cross-user access.
                fallbackArgs.forEach((key, value) -> {
                    if (value != null && PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.put(key, value);
                    }
                });
                fallbackArgs.forEach((key, value) -> {
                    if (!PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.putIfAbsent(key, value);
                    }
                });
            }

            ToolResult result;
            try {
                result = tool.execute(args);
            } catch (Exception ex) {
                log.warn("Tool '{}' execution failed", tool.name(), ex);
                throw ex;
            }

            results.add(Map.of(
                    "role", "tool",
                    "tool_call_id", call.id(),
                    "content", result.contentJson()
            ));
            log.debug("Tool '{}' call id={} produced payloadLength={}",
                    tool.name(), call.id(), result.contentJson() != null ? result.contentJson().length() : 0);
        }
        log.debug("Completed execution of {} tool call(s)", results.size());
        return results;
    }
}
