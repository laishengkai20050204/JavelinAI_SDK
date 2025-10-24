package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import com.example.service.impl.dto.V2StepNdjsonRequest;
import com.example.tools.AiToolExecutor;
import com.example.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceStepNdjsonTests {

    private ConversationMemoryService memoryService;
    private ToolRegistry toolRegistry;
    private AiToolExecutor toolExecutor;
    private AiServiceImpl service;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(ConversationMemoryService.class);
        toolRegistry = Mockito.mock(ToolRegistry.class);
        toolExecutor = Mockito.mock(AiToolExecutor.class);

        when(memoryService.getHistory(anyString(), anyString())).thenReturn(List.of());
        when(toolRegistry.openAiServerToolsSchema()).thenReturn(List.of());

        service = new AiServiceImpl(
                WebClient.builder().baseUrl("http://localhost").build(),
                new ObjectMapper(),
                memoryService,
                toolRegistry,
                toolExecutor,
                "OPENAI",
                "/v1/chat/completions",
                "test-model"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitStepPersistsFinalAnswerOnce() throws Exception {
        V2StepNdjsonRequest request = new V2StepNdjsonRequest();
        request.setUserId("u1");
        request.setConversationId("c1");
        request.setQ("hello?");
        request.setResponseMode("step-json-ndjson");

        Class<?> stepStateClass = Class.forName("com.example.service.impl.AiServiceImpl$StepState");
        Constructor<?> ctor = stepStateClass.getDeclaredConstructor(AiServiceImpl.class, V2StepNdjsonRequest.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance(service, request);

        Field finishedField = stepStateClass.getDeclaredField("finished");
        finishedField.setAccessible(true);
        finishedField.setBoolean(state, true);

        Field finalAnswerField = stepStateClass.getDeclaredField("finalAnswer");
        finalAnswerField.setAccessible(true);
        finalAnswerField.set(state, "好的");

        Method emitStep = AiServiceImpl.class.getDeclaredMethod("emitStep", reactor.core.publisher.FluxSink.class, stepStateClass);
        emitStep.setAccessible(true);

        Flux.<String>create(sink -> {
            try {
                emitStep.invoke(service, sink, state);
            } catch (Exception ex) {
                sink.error(ex);
                return;
            }
            sink.complete();
        }).collectList().block();

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryService, times(1)).appendMessages(eq("u1"), eq("c1"), captor.capture());

        List<Map<String, Object>> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("user");
        assertThat(messages.get(0).get("content")).isEqualTo("hello?");
        assertThat(messages.get(1).get("role")).isEqualTo("assistant");
        assertThat(messages.get(1).get("content")).isEqualTo("好的");

        Flux.<String>create(sink -> {
            try {
                emitStep.invoke(service, sink, state);
            } catch (Exception ex) {
                sink.error(ex);
                return;
            }
            sink.complete();
        }).collectList().block();

        verify(memoryService, times(1)).appendMessages(any(), any(), any());
    }
}
