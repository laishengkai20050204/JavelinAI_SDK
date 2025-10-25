package com.example.service.impl;

import com.example.ai.SpringAiChatGateway;
import com.example.config.AiProperties;
import com.example.service.ConversationMemoryService;
import com.example.service.impl.AiServiceImpl;
import com.example.tools.AiToolExecutor;
import com.example.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
// 删掉：org.springframework.web.reactive.function.client.WebClient

import static org.mockito.Mockito.*;

class AiServiceStepNdjsonTests {

    private AiServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();

        // 新依赖：网关用 mock
        SpringAiChatGateway chatGateway = mock(SpringAiChatGateway.class);

        // 新配置：至少给 mode / model
        AiProperties props = new AiProperties();
        props.setMode(AiProperties.Mode.OPENAI); // 你的测试如果验证 OpenAI 风格 NDJSON 合并，需要设成 OPENAI
        props.setModel("test-model");

        service = new AiServiceImpl(
                chatGateway,
                mapper,
                mock(ConversationMemoryService.class),
                mock(ToolRegistry.class),
                mock(AiToolExecutor.class),
                props
        );
    }

    // 其余测试用例不需要改动（除非你在测试里直接用了 WebClient 或依赖旧的 path 字段）
}
