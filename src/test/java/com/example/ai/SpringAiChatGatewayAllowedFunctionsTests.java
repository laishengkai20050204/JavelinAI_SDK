package com.example.ai;

import com.example.ai.tools.SpringAiToolAdapter;
import com.example.config.AiProperties;
import com.example.service.impl.StepContextStore;
import com.example.tools.AiTool;
import com.example.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that allowed function list uses union strategy:
 * (request-declared tools) U (all server-registered tools),
 * except when toolChoice=none or forcing a single function.
 */
class SpringAiChatGatewayAllowedFunctionsTests {

    private ObjectMapper mapper;
    private ToolRegistry registry;
    private SpringAiToolAdapter toolAdapter;
    private SpringAiChatGateway gateway;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper();
        // Minimal server tool for registry
        AiTool serverTool = new AiTool() {
            @Override public String name() { return "server_tool"; }
            @Override public String description() { return "test server tool"; }
            @Override public Map<String, Object> parametersSchema() {
                return Map.of("type","object","properties", Map.of());
            }
            @Override public com.example.api.dto.ToolResult execute(Map<String, Object> args) { return com.example.api.dto.ToolResult.success(null, name(), false, Map.of()); }
        };
        registry = new ToolRegistry(List.of(serverTool));
        toolAdapter = new SpringAiToolAdapter(registry, mapper);
        AiProperties props = new AiProperties();
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        gateway = new SpringAiChatGateway(chatModel, toolAdapter, mapper, props, new StepContextStore());
    }

    @Test
    void union_allows_request_and_server_tools() throws Exception {
        List<Object> defs = List.of(toolDef("frontend_tool", "client"));
        Set<String> allowed = invokeBuildAllowedFunctions(defs, null, "auto");
        assertTrue(allowed.contains("frontend_tool"), "should allow request-declared tool");
        assertTrue(allowed.contains("server_tool"), "should also include server tool by union");
    }

    @Test
    void none_choice_disables_all() throws Exception {
        List<Object> defs = List.of(toolDef("anything", "client"));
        Set<String> allowed = invokeBuildAllowedFunctions(defs, null, "none");
        assertTrue(allowed.isEmpty(), "toolChoice=none should disable all tools");
    }

    @Test
    void forced_function_overrides() throws Exception {
        List<Object> defs = List.of(toolDef("frontend_tool", "client"));
        Set<String> allowed = invokeBuildAllowedFunctions(defs, "only_this", "auto");
        assertEquals(Set.of("only_this"), allowed, "forced single function should be the only allowed");
    }

    private Object toolDef(String name, String execTarget) throws Exception {
        Class<?> toolDefClass = Arrays.stream(SpringAiChatGateway.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("ToolDef"))
                .findFirst().orElseThrow();
        Constructor<?> ctor = toolDefClass.getDeclaredConstructor(String.class, String.class, JsonNode.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(name, "desc", mapper.createObjectNode().put("type","object"), execTarget);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeBuildAllowedFunctions(List<Object> defs, String forcedFunction, String normalizedChoice) throws Exception {
        Method m = SpringAiChatGateway.class.getDeclaredMethod("buildAllowedFunctions", List.class, String.class, String.class);
        m.setAccessible(true);
        Object out = m.invoke(gateway, defs, forcedFunction, normalizedChoice);
        return (Set<String>) out;
    }
}

