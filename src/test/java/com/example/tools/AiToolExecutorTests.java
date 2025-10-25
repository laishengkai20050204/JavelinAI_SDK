package com.example.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolExecutorTests {

    @Test
    void executeAllOverridesProtectedIdentifiers() throws Exception {
        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        AiToolExecutor executor = new AiToolExecutor(registry, new ObjectMapper());

        AiToolExecutor.ToolCall call = new AiToolExecutor.ToolCall(
                "call-1",
                tool.name(),
                """
                {"userId":"user123","conversationId":"conv456","query":"custom","maxMessages":5}
                """
        );

        Map<String, Object> fallback = new HashMap<>();
        fallback.put("userId", "u1");
        fallback.put("conversationId", "c1");
        fallback.put("query", "fallbackQuery");
        fallback.put("maxMessages", 10);

        executor.executeAll(List.of(call), fallback);

        assertThat(tool.lastArgs.get("userId")).isEqualTo("u1");
        assertThat(tool.lastArgs.get("conversationId")).isEqualTo("c1");
        assertThat(tool.lastArgs.get("query")).isEqualTo("custom");
        assertThat(tool.lastArgs.get("maxMessages")).isEqualTo(5);
    }

    @Test
    void executeAllFillsMissingIdentifiersWhenAbsent() throws Exception {
        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        AiToolExecutor executor = new AiToolExecutor(registry, new ObjectMapper());

        AiToolExecutor.ToolCall call = new AiToolExecutor.ToolCall(
                "call-2",
                tool.name(),
                """
                {"query":"remember this"}
                """
        );

        Map<String, Object> fallback = new HashMap<>();
        fallback.put("userId", "u1");
        fallback.put("conversationId", "c1");

        executor.executeAll(List.of(call), fallback);

        assertThat(tool.lastArgs.get("userId")).isEqualTo("u1");
        assertThat(tool.lastArgs.get("conversationId")).isEqualTo("c1");
        assertThat(tool.lastArgs.get("query")).isEqualTo("remember this");
    }

    private static final class CapturingTool implements AiTool {
        private Map<String, Object> lastArgs = Map.of();

        @Override
        public String name() {
            return "find_relevant_memory";
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of();
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            this.lastArgs = new HashMap<>(args);
            return new ToolResult(name(), "{}");
        }
    }
}
