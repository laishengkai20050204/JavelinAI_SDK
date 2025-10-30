package com.example.tools;

import com.example.api.dto.ToolResult;
import com.example.config.DedupProperties;
import com.example.tools.support.ToolDeduplicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolExecutorTests {

    static class CapturingTool implements AiTool {
        Map<String,Object> lastArgs;
        @Override public String name() { return "echo_tool"; }
        @Override public String description() { return "captures args and echoes"; }
        @Override public Map<String, Object> parametersSchema() { return Map.of("type","object","properties", Map.of()); }
        @Override public ToolResult execute(Map<String, Object> args) {
            lastArgs = args;
            return ToolResult.success(null, name(), false, Map.of("payload", Map.of("echo", args.get("p"))));
        }
    }

    @Test
    void executes_tool_and_serializes_data_as_content() throws Exception {
        ObjectMapper om = new ObjectMapper();
        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        DedupProperties dedup = new DedupProperties();
        ToolDeduplicator ledger = Mockito.mock(ToolDeduplicator.class);

        AiToolExecutor exec = new AiToolExecutor(registry, om, dedup, ledger);
        AiToolExecutor.ToolCall call = new AiToolExecutor.ToolCall("call1", tool.name(), om.writeValueAsString(Map.of("p","v","userId","X","conversationId","Y")));

        List<Map<String, Object>> msgs = exec.executeAll(List.of(call), Map.of("userId","u1","conversationId","c2"));
        assertEquals(1, msgs.size());
        Map<String,Object> m = msgs.get(0);
        assertEquals("tool", m.get("role"));
        assertEquals("call1", m.get("tool_call_id"));
        String content = String.valueOf(m.get("content"));
        JsonNode node = om.readTree(content);
        assertEquals("v", node.path("payload").path("echo").asText());

        // protected scope keys should be overridden by fallback
        assertEquals("u1", String.valueOf(tool.lastArgs.get("userId")));
        assertEquals("c2", String.valueOf(tool.lastArgs.get("conversationId")));
    }
}

