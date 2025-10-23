package com.example.service;

import com.example.service.impl.dto.V2StepNdjsonRequest;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface AiService {

    String chatOnce(String userMessage);

    Mono<String> chatOnceAsync(String userMessage);

    Flux<String> chatStream(String userMessage);

    Mono<String> chatWithMemoryAsync(String userId, String conversationId, String userMessage);

    Mono<List<Map<String, Object>>> findRelevantMemoryAsync(String userId, String conversationId, String query, int limit);

    Mono<String> decideToolsAsync(Map<String, Object> payload);

    Flux<String> decideToolsStreamAsync(Map<String, Object> payload);

    Mono<String> continueAfterToolsAsync(Map<String, Object> payload);

    Flux<String> continueAfterToolsStreamAsync(Map<String, Object> payload);

    Mono<String> orchestrateChat(String userId, String conversationId, String prompt, @Nullable String toolChoice);

    Flux<String> orchestrateChatStream(String userId,
                                       String conversationId,
                                       String prompt,
                                       @org.springframework.lang.Nullable String toolChoice,
                                       @org.springframework.lang.Nullable Map<String, Object> options);

    Flux<String> orchestrateStepNdjson(V2StepNdjsonRequest req);
}
