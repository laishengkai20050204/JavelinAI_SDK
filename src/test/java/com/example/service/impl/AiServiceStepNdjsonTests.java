package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import com.example.service.impl.dto.ToolResultDTO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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

        when(memoryService.getContext(anyString(), anyString(), anyInt())).thenReturn(List.of());
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
    void emitStepPersistsDraftsOnce() throws Exception {
        V2StepNdjsonRequest request = new V2StepNdjsonRequest();
        request.setUserId("u1");
        request.setConversationId("c1");
        request.setQ("hello?");
        request.setResponseMode("step-json-ndjson");

        Class<?> stepStateClass = Class.forName("com.example.service.impl.AiServiceImpl$StepState");
        Constructor<?> ctor = stepStateClass.getDeclaredConstructor(AiServiceImpl.class, V2StepNdjsonRequest.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance(service, request);

        Field serverResultsField = stepStateClass.getDeclaredField("serverResults");
        serverResultsField.setAccessible(true);
        List<ToolResultDTO> serverResults = (List<ToolResultDTO>) serverResultsField.get(state);
        serverResults.add(new ToolResultDTO("call-1", "demo", "{\"ok\":true}"));

        Method emitStep = AiServiceImpl.class.getDeclaredMethod("emitStep", reactor.core.publisher.FluxSink.class, stepStateClass);
        emitStep.setAccessible(true);

        Flux.<String>create(sink -> {
            try {
                emitStep.invoke(service, sink, state);
                emitStep.invoke(service, sink, state);
            } catch (Exception ex) {
                sink.error(ex);
                return;
            }
            sink.complete();
        }).collectList().block();

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> seqCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);

        verify(memoryService, times(2)).upsertMessage(
                eq("u1"),
                eq("c1"),
                roleCaptor.capture(),
                contentCaptor.capture(),
                payloadCaptor.capture(),
                anyString(),
                seqCaptor.capture(),
                stateCaptor.capture()
        );

        assertThat(roleCaptor.getAllValues()).containsExactly("user", "tool");
        assertThat(contentCaptor.getAllValues()).containsExactly("hello?", null);
        assertThat(payloadCaptor.getAllValues()).containsExactly(null, "{\"ok\":true}");
        assertThat(seqCaptor.getAllValues()).containsExactly(1, 2);
        assertThat(stateCaptor.getAllValues()).containsExactly("DRAFT", "DRAFT");
    }

    @Test
    void emitStepPersistsFinalWithFallbackAndPromotesDrafts() throws Exception {
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
        finalAnswerField.set(state, "");

        Field summaryField = stepStateClass.getDeclaredField("assistantSummary");
        summaryField.setAccessible(true);
        summaryField.set(state, "OK");

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

        verify(memoryService, times(1)).upsertMessage(
                eq("u1"),
                eq("c1"),
                eq("assistant"),
                eq("OK"),
                isNull(),
                anyString(),
                eq(999),
                eq("FINAL")
        );
        verify(memoryService, times(1)).promoteDraftsToFinal(eq("u1"), eq("c1"), anyString());
    }

    @Test
    void stepStateUsesContextForInitialConversation() throws Exception {
        V2StepNdjsonRequest request = new V2StepNdjsonRequest();
        request.setUserId("u2");
        request.setConversationId("c2");

        Class<?> stepStateClass = Class.forName("com.example.service.impl.AiServiceImpl$StepState");
        Constructor<?> ctor = stepStateClass.getDeclaredConstructor(AiServiceImpl.class, V2StepNdjsonRequest.class);
        ctor.setAccessible(true);
        ctor.newInstance(service, request);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(memoryService, times(1)).getContext(eq("u2"), eq("c2"), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isPositive();
    }
}
