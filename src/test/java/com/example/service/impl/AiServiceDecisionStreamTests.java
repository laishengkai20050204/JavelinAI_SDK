package com.example.service.impl;

import com.example.ai.SpringAiChatGateway;
import com.example.config.AiProperties;
import com.example.service.ConversationMemoryService;
import com.example.tools.AiToolExecutor;
import com.example.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiServiceDecisionStreamTests {

    private AiServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();

        // ✅ 新签名：传入 SpringAiChatGateway 和 AiProperties
        SpringAiChatGateway chatGateway = Mockito.mock(SpringAiChatGateway.class);

        AiProperties props = new AiProperties();
        props.setMode(AiProperties.Mode.OPENAI);   // 测试用 OPENAI 路径，才能走 OpenAI 风格合并
        props.setModel("test-model");

        service = new AiServiceImpl(
                chatGateway,
                mapper,
                Mockito.mock(ConversationMemoryService.class),
                Mockito.mock(ToolRegistry.class),
                Mockito.mock(AiToolExecutor.class),
                props
        );
    }

    @Test
    void buildDecisionChunkEventWrapsPayload() throws Exception {
        String payload = invokeStringMethod("buildDecisionChunkEvent", String.class, "{\"foo\":42}");
        JsonNode node = mapper.readTree(payload);

        assertThat(node.path("stage").asText()).isEqualTo("decision");
        assertThat(node.path("type").asText()).isEqualTo("chunk");
        assertThat(node.path("data").path("foo").asInt()).isEqualTo(42);
    }

    @Test
    void buildDecisionHeartbeatProvidesMetadata() throws Exception {
        String payload = invokeStringMethod("buildDecisionHeartbeat", long.class, 3L);
        JsonNode node = mapper.readTree(payload);

        assertThat(node.path("stage").asText()).isEqualTo("decision");
        assertThat(node.path("type").asText()).isEqualTo("heartbeat");
        assertThat(node.path("count").asLong()).isEqualTo(3L);
        assertThat(node.hasNonNull("timestamp")).isTrue();
    }

    @Test
    void buildDecisionErrorEventDefaultsMessage() throws Exception {
        String payload = invokeStringMethod("buildDecisionErrorEvent", String.class, "");
        JsonNode node = mapper.readTree(payload);

        assertThat(node.path("stage").asText()).isEqualTo("decision");
        assertThat(node.path("type").asText()).isEqualTo("error");
        assertThat(node.path("message").asText()).isEqualTo("decision stream failed");
    }

    @Test
    void mergeDecisionChunksAggregatesOpenAiDelta() throws Exception {
        List<String> chunks = List.of(
                """
                {"choices":[{"delta":{"role":"assistant"}}]}
                """,
                """
                {"choices":[{"delta":{"content":"您好"}}]}
                """,
                """
                {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"find_relevant_memory","arguments":"{\\"query\\":\\"test\\"}"}}]}}]}
                """
        );

        Method method = AiServiceImpl.class.getDeclaredMethod("mergeDecisionChunks", List.class);
        method.setAccessible(true);
        String merged = (String) method.invoke(service, chunks);

        JsonNode root = mapper.readTree(merged);
        JsonNode message = root.path("choices").path(0).path("message");

        assertThat(message.path("role").asText()).isEqualTo("assistant");
        assertThat(message.path("content").asText()).contains("您好");
        JsonNode toolCalls = message.path("tool_calls");
        assertThat(toolCalls.isArray()).isTrue();
        assertThat(toolCalls.get(0).path("function").path("name").asText()).isEqualTo("find_relevant_memory");
    }

    private String invokeStringMethod(String name, Class<?> paramType, Object arg) throws Exception {
        Method method = AiServiceImpl.class.getDeclaredMethod(name, paramType);
        method.setAccessible(true);
        return (String) method.invoke(service, arg);
    }
}
