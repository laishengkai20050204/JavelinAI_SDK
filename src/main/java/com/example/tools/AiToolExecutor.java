package com.example.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AiToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(AiToolExecutor.class);

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public record ToolCall(String id, String name, String argumentsJson) {}

    public Map<String, Object> toAssistantToolCallsMessage(List<ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ToolCall call : calls) {
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
        List<Map<String, Object>> results = new ArrayList<>();
        for (ToolCall call : calls) {
            AiTool tool = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + call.name()));

            Map<String, Object> args = mapper.readValue(
                    call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            if (fallbackArgs != null) {
                fallbackArgs.forEach(args::putIfAbsent);
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
        }
        return results;
    }
}
