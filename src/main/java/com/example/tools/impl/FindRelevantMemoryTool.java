package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.service.ConversationMemoryService;
import com.example.tools.AiTool;
import com.example.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@AiToolComponent
@RequiredArgsConstructor
@Slf4j
public class FindRelevantMemoryTool implements AiTool {
    private static final int MIN_MESSAGES = 1;
    private static final int MAX_MESSAGES = 50;

    private final ConversationMemoryService memoryService;
    private final ObjectMapper mapper;

    @Value("${ai.memory.max-messages:12}")
    private int defaultWindow;

    @Override
    public String name() {
        return "find_relevant_memory";
    }

    @Override
    public String description() {
        return "Retrieve historical messages related to the current question on demand.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "userId", Map.of("type", "string"),
                        "conversationId", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "maxMessages", Map.of(
                                "type", "integer",
                                "minimum", MIN_MESSAGES,
                                "maximum", MAX_MESSAGES,
                                "default", Math.max(MIN_MESSAGES, normalizeWindow(defaultWindow))
                        )
                ),
                "required", List.of("userId", "conversationId")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        String userId = (String) args.get("userId");
        String conversationId = (String) args.get("conversationId");
        String query = (String) args.getOrDefault("query", "");
        int maxMessages = normalizeInt(args.get("maxMessages"),
                normalizeWindow(defaultWindow), MIN_MESSAGES, MAX_MESSAGES);

        if (userId == null || conversationId == null) {
            throw new IllegalArgumentException("userId and conversationId are required");
        }

        List<Map<String, Object>> relevant = memoryService.findRelevant(userId, conversationId, query, maxMessages);
        String json = mapper.writeValueAsString(relevant);
        log.debug("Tool '{}' returned {} message(s)", name(), relevant.size());
        return new ToolResult(name(), json);
    }

    private int normalizeWindow(int configured) {
        return configured > 0 ? Math.min(Math.max(configured, MIN_MESSAGES), MAX_MESSAGES) : 12;
    }

    private int normalizeInt(Object value, int defaultValue, int min, int max) {
        int candidate = defaultValue;
        if (value instanceof Number number) {
            candidate = number.intValue();
        } else if (value instanceof String str) {
            try {
                candidate = Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return Math.min(Math.max(candidate, min), max);
    }
}
