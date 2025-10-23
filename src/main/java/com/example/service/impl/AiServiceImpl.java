package com.example.service.impl;

import com.example.service.AiService;
import com.example.service.ConversationMemoryService;
import com.example.service.impl.dto.ExecTarget;
import com.example.service.impl.dto.ModelDecision;
import com.example.service.impl.dto.NdjsonEvent;
import com.example.service.impl.dto.OrchestrationStep;
import com.example.service.impl.dto.PlanAction;
import com.example.service.impl.dto.ToolCall;
import com.example.service.impl.dto.ToolCallDTO;
import com.example.service.impl.dto.ToolResultDTO;
import com.example.service.impl.dto.V2StepNdjsonRequest;
import com.example.tools.AiToolExecutor;
import com.example.tools.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.FluxSink;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiServiceImpl implements AiService {

    private static final Set<String> SUPPORTED_THINK_LEVELS = Set.of("low", "medium", "high");
    private static final Set<String> SUPPORTED_TOOL_CHOICES = Set.of("auto", "none", "required");
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<ServerSentEvent<String>>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public enum Compatibility { OLLAMA, OPENAI }

    private static final int MAX_TOOL_LIMIT = 50;
    private static final String SYSTEM_PROMPT_CORE = "You are a helpful assistant who writes concise, accurate answers.";
    private static final String SYSTEM_PROMPT_DECISION = "You decide whether to call the function find_relevant_memory before answering. Only call it when the user references previous context or when recalling history will help.";
    private static final String SYSTEM_PROMPT_OLLAMA_PLAN = "你是对话编排器。当需要查询历史时，只能输出：\n{\"action\":\"find_relevant_memory\",\"args\":{\"query\":\"...\", \"maxMessages\":12}}\n如果不需要工具，输出：{\"action\":\"final\"}";
    private static final String SYSTEM_PROMPT_OLLAMA_FINAL = "以上是检索结果和对话，请基于这些内容生成最终回答（只输出答案文本）。";

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final ConversationMemoryService memoryService;
    private final ToolRegistry toolRegistry;
    private final AiToolExecutor toolExecutor;
    private final Compatibility compatibility;
    private final String path;
    private final String model;

    @Value("${ai.think.enabled:false}")
    private boolean thinkEnabled;

    @Value("${ai.think.level:}")
    private String thinkLevel; // blank means boolean true; non-blank attempts provided string

    @Value("${ai.tools.max-loops:2}")
    private int maxToolLoops;

    @Value("${ai.memory.max-messages:12}")
    private int configuredMemoryWindow;

    @Value("${ai.client.timeout-ms:60000}")
    private long clientTimeoutMs;

    @Value("${ai.client.stream-timeout-ms:120000}")
    private long clientStreamTimeoutMs;

    @Value("${ai.client.retry.max-attempts:2}")
    private int clientRetryMaxAttempts;

    @Value("${ai.client.retry.backoff-ms:300}")
    private long clientRetryBackoffMs;

    @Value("${ai.stepjson.heartbeat-seconds:5}")
    private long stepJsonHeartbeatSeconds;

    public AiServiceImpl(
            WebClient aiWebClient,
            ObjectMapper mapper,
            ConversationMemoryService memoryService,
            ToolRegistry toolRegistry,
            AiToolExecutor toolExecutor,
            @Value("${ai.compatibility}") String compatibility,
            @Value("${ai.path}") String path,
            @Value("${ai.model}") String model
    ) {
        this.webClient = aiWebClient;
        this.mapper = mapper;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.compatibility = Compatibility.valueOf(compatibility.toUpperCase());
        this.path = path;
        this.model = model;
    }

    // ========= synchronous =========
    @Override
    public String chatOnce(String userMessage) {
        log.debug("chatOnce invoked userMessageLength={}", userMessage != null ? userMessage.length() : 0);
        return chatOnceAsync(userMessage).block();
    }

    // ========= async (non-streaming) =========
    @Override
    public Mono<String> chatOnceAsync(String userMessage) {
        log.debug("chatOnceAsync invoked userMessageLength={}", userMessage != null ? userMessage.length() : 0);
        List<Map<String, Object>> messages = buildSingleUserMessage(userMessage);
        return chatOnceCore(messages)
                .map(this::extractContentSafely)
                .doOnSuccess(response -> log.debug("chatOnceAsync completed contentLength={}",
                        response != null ? response.length() : 0))
                .doOnError(error -> log.error("chatOnceAsync failed", error));
    }

    private Mono<String> chatOnceCore(List<Map<String, Object>> messages) {
        Map<String, Object> body = buildRequestBody(messages, false);

        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> mapToResponseStatus(response, "Upstream returned 4xx during chat"))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> mapToResponseStatus(response, "Upstream returned 5xx during chat"))
                .bodyToMono(String.class)
                .timeout(requestTimeout())
                .retryWhen(retrySpec());
    }

    // ========= streaming (SSE/chunked) =========
    @Override
    public Flux<String> chatStream(String userMessage) {
        log.debug("chatStream invoked userMessageLength={}", userMessage != null ? userMessage.length() : 0);
        Map<String, Object> body = buildRequestBody(buildSingleUserMessage(userMessage), true);

        return webClient.post()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> mapToResponseStatus(response, "Upstream returned an error during stream"))
                .bodyToFlux(SSE_TYPE)
                .timeout(streamTimeout())
                .doOnNext(event -> log.trace("chatStream SSE event: {}", event))
                .map(ServerSentEvent::data)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .takeWhile(data -> !"[DONE]".equalsIgnoreCase(data))
                .map(this::extractDeltaSafely)
                .filter(text -> text != null && !text.isEmpty())
                .doOnSubscribe(subscription -> log.debug("chatStream subscribed"))
                .doOnNext(chunk -> log.trace("chatStream emitted chunkLength={}", chunk.length()))
                .doOnComplete(() -> log.debug("chatStream completed"))
                .doOnError(error -> log.error("chatStream failed", error));
    }

    @Override
    public Mono<String> chatWithMemoryAsync(String userId, String conversationId, String userMessage) {
        log.debug("chatWithMemoryAsync invoked userId={} conversationId={} userMessageLength={}",
                userId, conversationId, userMessage != null ? userMessage.length() : 0);
        int window = resolveMemoryWindow(MAX_TOOL_LIMIT);
        List<Map<String, Object>> relevant = memoryService.findRelevant(
                userId,
                conversationId,
                userMessage,
                window
        );
        List<Map<String, Object>> conversation = new ArrayList<>(relevant);
        log.debug("chatWithMemoryAsync relevantMessages={} userId={} conversationId={}",
                relevant.size(), userId, conversationId);

        if (conversation.isEmpty()) {
            conversation.addAll(limitWindow(
                    memoryService.getHistory(userId, conversationId),
                    window
            ));
            log.debug("chatWithMemoryAsync fallbackHistorySize={} userId={} conversationId={}",
                    conversation.size(), userId, conversationId);
        }

        Map<String, Object> userEntry = createMessage("user", userMessage);
        conversation.add(userEntry);

        return chatOnceCore(conversation)
                .map(this::extractContentSafely)
                .doOnNext(reply -> appendToMemory(
                        userId,
                        conversationId,
                        List.of(userEntry, createMessage("assistant", reply))
                ))
                .doOnSuccess(reply -> log.debug("chatWithMemoryAsync completed userId={} conversationId={} responseLength={}",
                        userId, conversationId, reply != null ? reply.length() : 0))
                .doOnError(error -> log.error("chatWithMemoryAsync failed userId={} conversationId={}",
                        userId, conversationId, error));
    }

    @Override
    public Mono<List<Map<String, Object>>> findRelevantMemoryAsync(String userId, String conversationId, String query, int limit) {
        log.debug("findRelevantMemoryAsync invoked userId={} conversationId={} query='{}' limit={}",
                userId, conversationId, query, limit);
        int safeLimit = positiveOrFallback(limit, configuredMemoryWindow);
        return Mono.fromCallable(() -> {
                    List<Map<String, Object>> results = new ArrayList<>(
                            memoryService.findRelevant(userId, conversationId, query, safeLimit)
                    );
                    return results;
                })
                .doOnSuccess(results -> log.debug("findRelevantMemoryAsync completed userId={} conversationId={} resultCount={}",
                        userId, conversationId, results != null ? results.size() : 0))
                .doOnError(error -> log.error("findRelevantMemoryAsync failed userId={} conversationId={}",
                        userId, conversationId, error));
    }

    @Override
    public Mono<String> orchestrateChat(String userId, String conversationId, String prompt, @Nullable String toolChoice) {
        log.debug("orchestrateChat invoked userId={} conversationId={} rawToolChoice={} promptLength={}",
                userId, conversationId, toolChoice, prompt != null ? prompt.length() : 0);
        String normalizedChoice = normalizeToolChoice(toolChoice);
        log.debug("orchestrateChat normalized toolChoice={}", normalizedChoice);
        int historyWindow = resolveMemoryWindow(MAX_TOOL_LIMIT);

        List<Map<String, Object>> history = limitWindow(
                memoryService.getHistory(userId, conversationId),
                historyWindow
        );
        Map<String, Object> userMessage = createMessage("user", prompt);

        List<Map<String, Object>> conversation = new ArrayList<>();
        conversation.add(createMessage("system", SYSTEM_PROMPT_CORE));
        conversation.addAll(history);
        conversation.add(userMessage);

        Mono<String> orchestration = orchestrateLoop(
                userId,
                conversationId,
                prompt,
                normalizedChoice,
                conversation,
                0,
                false,
                null
        ).onErrorResume(ex -> {
            log.warn("Tool orchestration failed, falling back to direct completion", ex);
            return continueAfterTools(conversation, "none")
                    .onErrorResume(inner -> {
                        log.error("Fallback completion failed", inner);
                        return Mono.just("Sorry, something went wrong.");
                    });
        });

        return orchestration.flatMap(answer -> Mono.fromRunnable(() -> appendToMemory(
                                userId,
                                conversationId,
                                List.of(createMessage("user", prompt), createMessage("assistant", answer))
                        ))
                        .thenReturn(answer))
                .doOnSuccess(answer -> log.debug("orchestrateChat completed userId={} conversationId={} toolChoice={} responseLength={}",
                        userId, conversationId, normalizedChoice, answer != null ? answer.length() : 0))
                .doOnError(error -> log.error("orchestrateChat failed userId={} conversationId={} toolChoice={}",
                        userId, conversationId, normalizedChoice, error));
    }

    private Mono<String> orchestrateLoop(String userId,
                                         String conversationId,
                                         String prompt,
                                         String toolChoice,
                                         List<Map<String, Object>> currentMessages,
                                         int loopIndex,
                                         boolean toolExecuted,
                                         @Nullable ModelDecision nextDecision) {
        if ("none".equals(toolChoice)) {
            log.debug("Tool choice 'none' - skipping tool orchestration");
            return continueAfterTools(currentMessages, "none");
        }

        int maxLoops = Math.max(1, maxToolLoops);
        if (loopIndex >= maxLoops) {
            log.warn("Reached max tool loops {} - returning without further tools", maxLoops);
            return continueAfterTools(currentMessages, "none");
        }

        Mono<ModelDecision> decisionMono = nextDecision != null
                ? Mono.just(nextDecision)
                : decideToolUse(userId, conversationId, prompt)
                .doOnNext(decision -> {
                    if (decision != null && decision.hasToolCalls()) {
                        log.info("Model requested {} tool call(s) on loop {}", decision.getToolCalls().size(), loopIndex);
                    } else {
                        log.debug("Model skipped tool calls on loop {}", loopIndex);
                    }
                });

        return decisionMono.flatMap(decision -> handleDecisionOrContinue(
                userId,
                conversationId,
                prompt,
                toolChoice,
                currentMessages,
                loopIndex,
                toolExecuted,
                decision
        ));
    }

    private Mono<String> handleDecisionOrContinue(String userId,
                                                  String conversationId,
                                                  String prompt,
                                                  String toolChoice,
                                                  List<Map<String, Object>> currentMessages,
                                                  int loopIndex,
                                                  boolean toolExecuted,
                                                  @Nullable ModelDecision decision) {
        if (decision == null || !decision.hasToolCalls()) {
            if ("required".equals(toolChoice) && !toolExecuted) {
                log.info("tool_choice=requirement not satisfied - forcing memory lookup");
                ModelDecision forced = buildForcedDecision(userId, conversationId, prompt);
                return handleDecision(
                        userId,
                        conversationId,
                        prompt,
                        toolChoice,
                        currentMessages,
                        loopIndex,
                        true,
                        forced
                );
            }
            return continueAfterTools(currentMessages, "none");
        }

        return handleDecision(
                userId,
                conversationId,
                prompt,
                toolChoice,
                currentMessages,
                loopIndex,
                true,
                decision
        );
    }

    private Mono<String> handleDecision(String userId,
                                        String conversationId,
                                        String prompt,
                                        String toolChoice,
                                        List<Map<String, Object>> currentMessages,
                                        int loopIndex,
                                        boolean toolExecuted,
                                        ModelDecision decision) {
        List<Map<String, Object>> toolMessages;
        try {
            toolMessages = runToolsAndBuildMessages(decision, userId, conversationId, prompt);
        } catch (Exception e) {
            log.warn("Failed to execute tool calls, falling back to direct completion", e);
            return continueAfterTools(currentMessages, "none");
        }

        List<Map<String, Object>> updatedMessages = new ArrayList<>(currentMessages);
        updatedMessages.addAll(toolMessages);

        String downstreamChoice = "none";
        if ("required".equals(toolChoice) && toolExecuted) {
            downstreamChoice = "none";
        } else if ("auto".equals(toolChoice)) {
            downstreamChoice = "none";
        }

        return continueAfterTools(updatedMessages, downstreamChoice)
                .onErrorResume(ToolLoopException.class, loopEx -> {
                    if (loopIndex + 1 >= Math.max(1, maxToolLoops)) {
                        log.warn("Continuation requested more tools but max loops reached; returning without running tools again");
                        return continueAfterTools(updatedMessages, "none");
                    }
                    log.info("Continuation requested another tool cycle (loop {}); repeating orchestration", loopIndex + 1);
                    return orchestrateLoop(
                            userId,
                            conversationId,
                            prompt,
                            toolChoice,
                            updatedMessages,
                            loopIndex + 1,
                            true,
                            loopEx.getDecision()
                    );
                })
                .onErrorResume(ex -> {
                    if (ex instanceof ToolLoopException) {
                        return Mono.error(ex);
                    }
                    log.warn("Continuation failed, falling back to direct completion", ex);
                    return continueAfterTools(currentMessages, "none");
                });
    }

    private ModelDecision buildForcedDecision(String userId, String conversationId, String prompt) {
        try {
            if (toolRegistry.get("find_relevant_memory").isEmpty()) {
                log.warn("Required tool 'find_relevant_memory' not registered; skipping forced decision");
                return ModelDecision.finalOnly();
            }
            Map<String, Object> args = new HashMap<>();
            args.put("userId", userId);
            args.put("conversationId", conversationId);
            args.put("query", prompt);
            args.put("maxMessages", resolveMemoryWindow(12));
            String argsJson = mapper.writeValueAsString(args);
            ToolCall call = new ToolCall("forced-" + UUID.randomUUID(), "find_relevant_memory", argsJson);
            return new ModelDecision(List.of(call), false);
        } catch (JsonProcessingException e) {
            log.warn("Failed to build forced tool arguments", e);
            ToolCall call = new ToolCall("forced-" + UUID.randomUUID(), "find_relevant_memory", "{}");
            return new ModelDecision(List.of(call), false);
        }
    }

    private String normalizeToolChoice(@Nullable String toolChoice) {
        if (toolChoice == null || toolChoice.isBlank()) {
            return "auto";
        }
        String normalized = toolChoice.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TOOL_CHOICES.contains(normalized)) {
            log.warn("Unsupported tool_choice '{}' received - defaulting to auto", toolChoice);
            return "auto";
        }
        return normalized;
    }

    private Mono<ModelDecision> decideToolUse(String userId, String conversationId, String prompt) {
        try {
            int historyWindow = resolveMemoryWindow(MAX_TOOL_LIMIT);
            List<Map<String, Object>> history = limitWindow(
                    memoryService.getHistory(userId, conversationId),
                    historyWindow
            );

            List<Map<String, Object>> messages = new ArrayList<>();
            if (compatibility == Compatibility.OPENAI) {
                messages.add(createMessage("system", SYSTEM_PROMPT_DECISION));
            } else {
                messages.add(createMessage("system", SYSTEM_PROMPT_OLLAMA_PLAN));
            }
            messages.addAll(history);
            messages.add(createMessage("user", prompt));

            if (compatibility == Compatibility.OPENAI) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", model);
                payload.put("messages", messages);
                List<Map<String, Object>> schemas = toolRegistry.openAiToolsSchema();
                if (!schemas.isEmpty()) {
                    payload.put("tools", schemas);
                    payload.put("tool_choice", "auto");
                } else {
                    payload.put("tool_choice", "none");
                }
                payload.put("stream", false);

                return callChatCompletions(payload, false)
                        .map(this::parseDecision)
                        .onErrorResume(ex -> {
                            log.warn("Failed to decide tool usage (OpenAI mode)", ex);
                            return Mono.just(ModelDecision.finalOnly());
                        });
            }

            Map<String, Object> body = buildRequestBody(messages, false);
            return webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> mapToResponseStatus(response, "Upstream returned 4xx during tool planning"))
                    .onStatus(HttpStatusCode::is5xxServerError,
                            response -> mapToResponseStatus(response, "Upstream returned 5xx during tool planning"))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                            .filter(this::isRetryableError))
                    .map(this::parseDecision)
                    .onErrorResume(ex -> {
                        log.warn("Failed to decide tool usage (Ollama mode)", ex);
                        return Mono.just(ModelDecision.finalOnly());
                    });
        } catch (Exception ex) {
            log.warn("Exception building tool decision request", ex);
            return Mono.just(ModelDecision.finalOnly());
        }
    }

    private List<Map<String, Object>> runToolsAndBuildMessages(ModelDecision decision,
                                                               String userId,
                                                               String conversationId,
                                                               String prompt) {
        List<Map<String, Object>> responses = new ArrayList<>();
        if (decision == null || !decision.hasToolCalls()) {
            return responses;
        }

        List<AiToolExecutor.ToolCall> calls = decision.getToolCalls().stream()
                .map(call -> new AiToolExecutor.ToolCall(call.getId(), call.getName(), call.getArgumentsJson()))
                .toList();

        if (calls.isEmpty()) {
            return responses;
        }

        log.info("Executing {} tool call(s)", calls.size());
        responses.add(toolExecutor.toAssistantToolCallsMessage(calls));

        int fallbackWindow = Math.min(resolveMemoryWindow(12), MAX_TOOL_LIMIT);

        Map<String, Object> fallbackArgs = new HashMap<>();
        fallbackArgs.put("userId", userId);
        fallbackArgs.put("conversationId", conversationId);
        fallbackArgs.put("query", prompt);
        fallbackArgs.put("maxMessages", fallbackWindow);

        try {
            responses.addAll(toolExecutor.executeAll(calls, fallbackArgs));
        } catch (IllegalArgumentException iae) {
            log.warn("Tool execution failed due to bad request: {}", iae.getMessage());
            throw iae;
        } catch (Exception ex) {
            log.warn("Tool execution encountered an error", ex);
            throw new RuntimeException("Tool execution failed", ex);
        }

        return responses;
    }

    private Mono<String> continueAfterTools(List<Map<String, Object>> messages, String toolChoice) {
        if (compatibility == Compatibility.OPENAI) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("tool_choice", toolChoice == null ? "none" : toolChoice);
            payload.put("stream", false);

            return callChatCompletions(payload, false)
                    .flatMap(json -> {
                        ModelDecision followUp = parseDecision(json);
                        if (followUp != null && followUp.hasToolCalls()) {
                            log.info("Continuation produced additional tool calls - restarting loop");
                            return Mono.error(new ToolLoopException(followUp));
                        }
                        return Mono.just(extractContentSafely(json));
                    });
        }

        List<Map<String, Object>> enriched = new ArrayList<>(messages);
        enriched.add(createMessage("system", SYSTEM_PROMPT_OLLAMA_FINAL));

        Map<String, Object> body = buildRequestBody(enriched, false);

        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> mapToResponseStatus(response, "Upstream returned 4xx during final response"))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> mapToResponseStatus(response, "Upstream returned 5xx during final response"))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(this::isRetryableError))
                .flatMap(json -> {
                    ModelDecision followUp = parseDecision(json);
                    if (followUp != null && followUp.hasToolCalls()) {
                        log.info("Ollama continuation produced a tool plan - restarting loop");
                        return Mono.error(new ToolLoopException(followUp));
                    }
                    return Mono.just(extractContentSafely(json));
                });
    }

    private @Nullable ModelDecision parseDecision(String json) {
        if (json == null || json.isBlank()) {
            return ModelDecision.finalOnly();
        }

        try {
            JsonNode root = mapper.readTree(json);
            if (compatibility == Compatibility.OPENAI) {
                JsonNode messageNode = root.path("choices").path(0).path("message");
                if (messageNode.isMissingNode()) {
                    return ModelDecision.finalOnly();
                }

                List<ToolCall> calls = new ArrayList<>();
                JsonNode toolCalls = messageNode.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode node : toolCalls) {
                        String id = node.path("id").asText("call-" + UUID.randomUUID());
                        String name = node.path("function").path("name").asText("");
                        String arguments = node.path("function").path("arguments").asText("{}");
                        if (name != null && !name.isBlank()) {
                            calls.add(new ToolCall(id, name, arguments));
                        }
                    }
                }
                boolean wantsFinal = messageNode.path("content").asText("").trim().length() > 0;
                return new ModelDecision(calls, wantsFinal);
            }

            JsonNode messageNode = root.path("message");
            String content = messageNode.path("content").asText(root.path("content").asText(""));
            if (content == null || content.isBlank()) {
                return ModelDecision.finalOnly();
            }
            String trimmed = content.trim();
            if (!trimmed.startsWith("{")) {
                return ModelDecision.finalOnly();
            }

            PlanAction plan = mapper.readValue(trimmed, PlanAction.class);
            if (plan == null || plan.getAction() == null) {
                return ModelDecision.finalOnly();
            }

            String action = plan.getAction();
            if (action != null) {
                action = action.trim();
            }
            if ("final".equalsIgnoreCase(action)) {
                return ModelDecision.finalOnly();
            }

            if (action == null || action.isEmpty()) {
                return ModelDecision.finalOnly();
            }

            if (toolRegistry.get(action).isPresent()) {
                Map<String, Object> args = plan.getArgs() == null ? Map.of() : plan.getArgs();
                String argsJson = mapper.writeValueAsString(args);
                ToolCall call = new ToolCall("plan-" + UUID.randomUUID(), action, argsJson);
                return new ModelDecision(List.of(call), false);
            }

            log.warn("Unsupported plan action '{}' received from model", action);
            return ModelDecision.finalOnly();
        } catch (Exception e) {
            log.warn("Failed to parse model decision", e);
            return ModelDecision.finalOnly();
        }
    }

    // ========= JSON parsing =========
    private String extractContentSafely(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return switch (compatibility) {
                case OLLAMA -> {
                    String thinking = root.path("message").path("thinking").asText("");
                    String content = root.path("message").path("content").asText("");
                    yield (thinking.isEmpty() ? "" : "[THINK] " + thinking + "\n\n")
                            + (content.isEmpty() ? json : content);
                }
                case OPENAI -> root.path("choices").path(0).path("message").path("content").asText(json);
            };
        } catch (Exception e) {
            return json;
        }
    }

    private String extractDeltaSafely(String data) {
        if (data == null || data.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(data);

            if (compatibility == Compatibility.OPENAI) {
                JsonNode deltaNode = root.path("choices").path(0).path("delta");
                String deltaContent = coerceText(deltaNode.path("content"));
                if (deltaContent != null && !deltaContent.isEmpty()) {
                    return deltaContent;
                }

                String reasoning = coerceText(deltaNode.path("reasoning"));
                if (reasoning != null && !reasoning.isEmpty()) {
                    return "[THINK] " + reasoning;
                }

                String messageContent = coerceText(root.path("choices").path(0).path("message").path("content"));
                if (messageContent != null && !messageContent.isEmpty()) {
                    return messageContent;
                }

                String rootContent = coerceText(root.path("content"));
                if (rootContent != null && !rootContent.isEmpty()) {
                    return rootContent;
                }
                return "";
            } else {
                JsonNode messageNode = root.path("message");
                String thinking = coerceText(messageNode.path("thinking"));
                if (thinking != null && !thinking.isEmpty()) {
                    return "[THINK] " + thinking;
                }

                String content = coerceText(messageNode.path("content"));
                if (content != null && !content.isEmpty()) {
                    return content;
                }

                String rootContent = coerceText(root.path("content"));
                if (rootContent != null && !rootContent.isEmpty()) {
                    return rootContent;
                }
                return "";
            }
        } catch (Exception e) {
            return data; // some providers stream plain text
        }
    }

    private String coerceText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode child : node) {
                String text = coerceText(child);
                if (text != null && !text.isEmpty()) {
                    builder.append(text);
                }
            }
            return builder.length() > 0 ? builder.toString() : null;
        }
        if (node.isObject()) {
            if (node.has("text")) {
                String text = coerceText(node.get("text"));
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
            if (node.has("value")) {
                String text = coerceText(node.get("value"));
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
            if (node.has("content")) {
                String text = coerceText(node.get("content"));
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
            if (node.has("data")) {
                String text = coerceText(node.get("data"));
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
            // Fallback: iterate fields
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if ("type".equals(entry.getKey()) || "id".equals(entry.getKey())) {
                    continue;
                }
                String text = coerceText(entry.getValue());
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
            return null;
        }
        return node.asText();
    }

    private Map<String, Object> buildRequestBody(List<Map<String, Object>> messages, boolean stream) {
        if (compatibility == Compatibility.OLLAMA) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("stream", stream);
            applyThinkOptions(payload);
            return payload;
        }

        return Map.of(
                "model", model,
                "messages", messages,
                "stream", stream
        );
    }

    private List<Map<String, Object>> buildSingleUserMessage(String userMessage) {
        return List.of(createMessage("user", userMessage));
    }

    private Map<String, Object> createMessage(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private void appendToMemory(String userId, String conversationId, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        memoryService.appendMessages(userId, conversationId, timestampMessages(messages));
    }

    private List<Map<String, Object>> timestampMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> enriched = new ArrayList<>(messages.size());
        for (Map<String, Object> original : messages) {
            if (original == null) {
                continue;
            }
            Map<String, Object> copy = new HashMap<>(original);
            copy.putIfAbsent("timestamp", Instant.now().toString());
            enriched.add(copy);
        }
        return enriched;
    }

    private List<Map<String, Object>> limitWindow(List<Map<String, Object>> history, int limit) {
        if (history == null || history.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (history.size() <= limit) {
            return new ArrayList<>(history);
        }
        int start = history.size() - limit;
        return new ArrayList<>(history.subList(start, history.size()));
    }

    private void applyThinkOptions(Map<String, Object> payload) {
        if (!thinkEnabled || compatibility != Compatibility.OLLAMA) {
            return;
        }

        if (thinkLevel == null || thinkLevel.isBlank()) {
            payload.put("think", true);
            return;
        }

        String normalized = thinkLevel.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_THINK_LEVELS.contains(normalized)) {
            payload.put("think", normalized);
            return;
        }

        if ("true".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized)) {
            payload.put("think", Boolean.parseBoolean(normalized));
            return;
        }

        log.warn("Ignoring unsupported think level '{}'; falling back to boolean true", thinkLevel);
        payload.put("think", true);
    }

    private Mono<ResponseStatusException> mapToResponseStatus(ClientResponse response, String context) {
        return response.createException()
                .map(ex -> {
                    String reason = extractBody(ex);
                    String message = reason.isEmpty() ? context : context + ": " + reason;
                    return new ResponseStatusException(response.statusCode(), message, ex);
                });
    }

    private String extractBody(WebClientResponseException exception) {
        try {
            String body = exception.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return body;
            }
        } catch (Exception ignored) {
            // fall through and use exception message
        }
        String message = exception.getMessage();
        return message == null ? "" : message;
    }

    private boolean isRetryableError(Throwable throwable) {
        if (throwable instanceof ResponseStatusException) {
            return false;
        }
        if (throwable instanceof WebClientResponseException) {
            return false;
        }
        return !(throwable instanceof IllegalArgumentException);
    }

    private Duration requestTimeout() {
        long millis = Math.max(clientTimeoutMs, 1000);
        return Duration.ofMillis(millis);
    }

    private Duration streamTimeout() {
        long millis = Math.max(clientStreamTimeoutMs, 1000);
        return Duration.ofMillis(millis);
    }

    private @Nullable String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int resolveMemoryWindow(int fallback) {
        return positiveOrFallback(configuredMemoryWindow, fallback);
    }

    private int positiveOrFallback(int value, int fallback) {
        int candidate = value > 0 ? value : fallback;
        return Math.max(1, candidate);
    }

    private Retry retrySpec() {
        int attempts = Math.max(clientRetryMaxAttempts, 1);
        Duration backoff = Duration.ofMillis(Math.max(clientRetryBackoffMs, 100));
        return Retry.backoff(attempts, backoff)
                .filter(this::isRetryableError);
    }

    @Override
    public Mono<String> decideToolsAsync(Map<String, Object> payload) {
        log.debug("decideToolsAsync invoked payloadKeys={}", payload != null ? payload.keySet() : "null");
        return callChatCompletions(payload, false);
    }

    @Override
    public Flux<String> decideToolsStreamAsync(Map<String, Object> payload) {
        log.debug("decideToolsStreamAsync invoked payloadKeys={}", payload != null ? payload.keySet() : "null");

        boolean mergeFinal = false;
        if (payload != null && payload.containsKey("_merge_final")) {
            Object v = payload.get("_merge_final");
            mergeFinal = (v instanceof Boolean) ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v));
        }

        if (!mergeFinal) {
            // 保持原样：透传后端 SSE（rawStream=true 不做内容提取）
            return callChatCompletionsStream(payload, true)
                    .doOnSubscribe(subscription -> log.debug("decideToolsStreamAsync subscribed payloadKeys={}",
                            payload != null ? payload.keySet() : "null"))
                    .doOnNext(chunk -> log.trace("decideToolsStreamAsync chunk length={}",
                            chunk != null ? chunk.length() : 0))
                    .doOnComplete(() -> log.debug("decideToolsStreamAsync completed payloadKeys={}",
                            payload != null ? payload.keySet() : "null"))
                    .doOnError(error -> log.error("decideToolsStreamAsync failed payloadKeys={}",
                            payload != null ? payload.keySet() : "null", error));
        }

        // merge_final=true：边透传边累积，结束后追加一条“合并后的完整 JSON”事件
        StreamAccumulator acc = new StreamAccumulator();

        return callChatCompletionsStream(payload, true)
                .doOnNext(chunk -> {
                    try {
                        acc.applyChunk(mapper, chunk);
                    } catch (Exception e) {
                        log.warn("Failed to apply streaming chunk to accumulator, forwarding anyway", e);
                        acc.appendFallbackText(chunk);
                    }
                })
                // 先透传原始分片
                .concatWith(Mono.fromSupplier(() -> {
                    try {
                        String merged = acc.toOpenAIStyleJson(mapper);
                        log.debug("Emitting merged final JSON length={}", merged.length());
                        return merged;
                    } catch (Exception e) {
                        log.error("Failed to build merged final JSON", e);
                        return acc.fallbackAsSimpleJson(mapper);
                    }
                }))
                .doOnSubscribe(s -> log.debug("decideToolsStreamAsync (merge_final) subscribed"))
                .doOnComplete(() -> log.debug("decideToolsStreamAsync (merge_final) completed"))
                .doOnError(err -> log.error("decideToolsStreamAsync (merge_final) failed", err));
    }

    @Override
    public Mono<String> continueAfterToolsAsync(Map<String, Object> payload) {
        log.debug("continueAfterToolsAsync invoked payloadKeys={}", payload != null ? payload.keySet() : "null");
        return callChatCompletions(payload, false);
    }

    /**
     * Shared helper for tool-enabled chat completions (non-streaming by default).
     */
    private Mono<String> callChatCompletions(Map<String, Object> payload, boolean stream) {
        if (payload == null) {
            log.error("callChatCompletions invoked with null payload");
            return Mono.error(new IllegalArgumentException("payload is required"));
        }
        Object reqModel = payload.getOrDefault("model", model);
        Object messages = payload.get("messages");
        Object tools = payload.get("tools");
        Object toolChoice = payload.getOrDefault("tool_choice", "auto");
        log.debug("callChatCompletions invoked stream={} model={} toolChoice={} toolsPresent={}",
                stream, reqModel, toolChoice, tools != null);

        if (messages == null) {
            return Mono.error(new IllegalArgumentException("messages is required"));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", reqModel);
        body.put("messages", messages);
        if (tools != null) {
            body.put("tools", tools);
        }
        body.put("tool_choice", toolChoice);
        body.put("stream", stream);

        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> mapToResponseStatus(response, "Upstream returned 4xx during tool call"))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> mapToResponseStatus(response, "Upstream returned 5xx during tool call"))
                .bodyToMono(String.class)
                .timeout(requestTimeout())
                .retryWhen(retrySpec())
                .doOnSuccess(response -> log.debug("callChatCompletions completed stream={} responseLength={}",
                        stream, response != null ? response.length() : 0))
                .doOnError(error -> log.error("callChatCompletions failed stream={}", stream, error));
    }

    private Flux<String> callChatCompletionsStream(Map<String, Object> payload) {
        return callChatCompletionsStream(payload, false);
    }

    private Flux<String> callChatCompletionsStream(Map<String, Object> payload, boolean rawStream) {
        if (payload == null) {
            log.error("callChatCompletionsStream invoked with null payload");
            return Flux.error(new IllegalArgumentException("payload is required"));
        }

        Object reqModel = payload.getOrDefault("model", model);
        Object messages = payload.get("messages");
        Object tools = payload.get("tools");
        Object toolChoice = payload.getOrDefault("tool_choice", "auto");
        log.debug("callChatCompletionsStream invoked model={} toolChoice={} toolsPresent={}",
                reqModel, toolChoice, tools != null);

        if (messages == null) {
            return Flux.error(new IllegalArgumentException("messages is required"));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", reqModel);
        body.put("messages", messages);
        if (tools != null) {
            body.put("tools", tools);
        }
        body.put("tool_choice", toolChoice);
        body.put("stream", true);

        Flux<String> base = webClient.post()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> mapToResponseStatus(response, "Upstream returned 4xx during tool call (stream)"))
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> mapToResponseStatus(response, "Upstream returned 5xx during tool call (stream)"))
                .bodyToFlux(SSE_TYPE)
                .timeout(streamTimeout())
                .doOnNext(event -> log.trace("callChatCompletionsStream SSE event: {}", event))
                .map(ServerSentEvent::data)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .takeWhile(data -> !"[DONE]".equalsIgnoreCase(data));

        Flux<String> processed = rawStream
                ? base
                : base.map(this::extractDeltaSafely)
                .filter(text -> text != null && !text.isEmpty());

        return processed.retryWhen(retrySpec());
    }

    private static class ToolLoopException extends RuntimeException {
        private final ModelDecision decision;

        ToolLoopException(ModelDecision decision) {
            super("Model requested additional tool execution");
            this.decision = decision;
        }

        ModelDecision getDecision() {
            return decision;
        }
    }

    private static final class DecisionStreamResult {
        private final Flux<String> frames;
        private final Mono<ModelDecision> decision;

        DecisionStreamResult(Flux<String> frames, Mono<ModelDecision> decision) {
            this.frames = frames;
            this.decision = decision;
        }

        Flux<String> frames() {
            return frames;
        }

        Mono<ModelDecision> decision() {
            return decision;
        }
    }

    private DecisionStreamResult streamToolDecision(String userId,
                                                    String conversationId,
                                                    String prompt,
                                                    List<Map<String, Object>> history) {
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            if (compatibility == Compatibility.OPENAI) {
                messages.add(createMessage("system", SYSTEM_PROMPT_DECISION));
            } else {
                messages.add(createMessage("system", SYSTEM_PROMPT_OLLAMA_PLAN));
            }
            messages.addAll(history);
            messages.add(createMessage("user", prompt));

            Map<String, Object> payload;
            if (compatibility == Compatibility.OPENAI) {
                payload = new HashMap<>();
                payload.put("model", model);
                payload.put("messages", messages);
                List<Map<String, Object>> schemas = toolRegistry.openAiToolsSchema();
                if (!schemas.isEmpty()) {
                    payload.put("tools", schemas);
                    payload.put("tool_choice", "auto");
                } else {
                    payload.put("tool_choice", "none");
                }
            } else {
                payload = new HashMap<>(buildRequestBody(messages, true));
            }

            Sinks.Many<String> sink = Sinks.many().replay().limit(64);

            Mono<List<String>> chunkList = callChatCompletionsStream(payload, true)
                    .doOnSubscribe(s -> log.debug("Streaming tool decision started userId={} conversationId={}", userId, conversationId))
                    .doOnNext(chunk -> {
                        if (chunk == null) {
                            return;
                        }
                        Sinks.EmitResult result = sink.tryEmitNext(chunk);
                        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                            log.trace("Decision stream emit result={} chunkLength={}", result, chunk.length());
                        }
                    })
                    .collectList()
                    .doOnSuccess(list -> {
                        sink.tryEmitComplete();
                        log.debug("Decision stream completed userId={} conversationId={} chunkCount={}",
                                userId, conversationId, list != null ? list.size() : 0);
                    });

            Mono<ModelDecision> decisionMono = chunkList
                    .map(this::mergeDecisionChunks)
                    .map(this::parseDecision)
                    .defaultIfEmpty(ModelDecision.finalOnly())
                    .onErrorResume(error -> {
                        log.warn("Decision stream failed userId={} conversationId={}", userId, conversationId, error);
                        emitDecisionError(sink, error);
                        return Mono.just(ModelDecision.finalOnly());
                    })
                    .cache();

            Flux<String> frames = sink.asFlux()
                    .doOnSubscribe(s -> log.debug("Subscribed to decision frames userId={} conversationId={}", userId, conversationId));

            return new DecisionStreamResult(frames, decisionMono);
        } catch (Exception ex) {
            log.warn("Exception building decision stream userId={} conversationId={}", userId, conversationId, ex);
            return new DecisionStreamResult(Flux.empty(), Mono.just(ModelDecision.finalOnly()));
        }
    }

    private String mergeDecisionChunks(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        if (compatibility == Compatibility.OPENAI) {
            StreamAccumulator accumulator = new StreamAccumulator();
            for (String chunk : chunks) {
                if (chunk == null || chunk.isBlank()) {
                    continue;
                }
                try {
                    accumulator.applyChunk(mapper, chunk);
                } catch (Exception e) {
                    log.debug("Failed to merge decision chunk, appending fallback", e);
                    accumulator.appendFallbackText(chunk);
                }
            }
            try {
                return accumulator.toOpenAIStyleJson(mapper);
            } catch (Exception e) {
                log.warn("Failed to build OpenAI-style decision JSON, falling back", e);
                return accumulator.fallbackAsSimpleJson(mapper);
            }
        }

        StringBuilder builder = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(chunk);
        }
        return builder.toString();
    }

    private String buildDecisionChunkEvent(@Nullable String rawChunk) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("stage", "decision");
            envelope.put("type", "chunk");
            if (rawChunk == null || rawChunk.isBlank()) {
                envelope.putNull("data");
            } else {
                envelope.set("data", mapper.readTree(rawChunk));
            }
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("stage", "decision");
            envelope.put("type", "chunk");
            envelope.put("raw", rawChunk == null ? "" : rawChunk);
            return envelope.toString();
        }
    }

    private String buildDecisionHeartbeat(long counter) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("stage", "decision");
            envelope.put("type", "heartbeat");
            envelope.put("count", counter);
            envelope.put("timestamp", System.currentTimeMillis());
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{\"stage\":\"decision\",\"type\":\"heartbeat\",\"count\":" + counter + "}";
        }
    }

    private String buildDecisionErrorEvent(@Nullable String message) {
        String effective = (message == null || message.isBlank()) ? "decision stream failed" : message;
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("stage", "decision");
            envelope.put("type", "error");
            envelope.put("message", effective);
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            String safe = effective.replace("\"", "'");
            return "{\"stage\":\"decision\",\"type\":\"error\",\"message\":\"" + safe + "\"}";
        }
    }

    private void emitDecisionError(Sinks.Many<String> sink, Throwable error) {
        String payload = buildDecisionErrorEvent(error != null ? error.getMessage() : null);
        Sinks.EmitResult result = sink.tryEmitNext(payload);
        if (result.isFailure()) {
            log.debug("Decision error emit result={}", result);
        }
        Sinks.EmitResult completion = sink.tryEmitComplete();
        if (completion.isFailure()) {
            log.trace("Decision stream completion emit result={}", completion);
        }
    }

    // ================== Streaming 合并累加器 ==================
    /**
     * 把 OpenAI 风格的流式 delta（choices[].delta.*）合并成一次性 JSON。
     * 支持的片段：content / reasoning / tool_calls[index].function.{name,arguments} / id / type / finish_reason / role
     */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<ToolCallAgg> toolCalls = new ArrayList<>();
        private String finishReason = null;
        private String role = "assistant";

        void applyChunk(ObjectMapper mapper, String chunkJson) throws Exception {
            if (chunkJson == null || chunkJson.isBlank()) return;

            JsonNode root = mapper.readTree(chunkJson);

            // 期望 OpenAI 结构：choices[0].delta...
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                // 非标准：兼容顶层 message / content
                appendMaybeTopLevel(root);
                return;
            }

            JsonNode choice0 = choices.get(0);
            if (choice0.hasNonNull("finish_reason")) {
                String fr = choice0.get("finish_reason").asText(null);
                if (fr != null && !fr.isBlank()) this.finishReason = fr;
            }

            JsonNode delta = choice0.path("delta");
            if (!delta.isMissingNode()) {
                // role
                String r = textOrNull(delta, "role");
                if (r != null) this.role = r;

                // content
                String c = textOrNull(delta, "content");
                if (c != null) this.content.append(c);

                // reasoning
                String rs = textOrNull(delta, "reasoning");
                if (rs != null) this.reasoning.append(rs);

                // tool_calls[]
                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    for (JsonNode tc : tcs) {
                        int index = tc.path("index").asInt(-1);
                        if (index < 0) {
                            index = toolCalls.size();
                        }
                        while (toolCalls.size() <= index) toolCalls.add(new ToolCallAgg());

                        ToolCallAgg agg = toolCalls.get(index);
                        String id = textOrNull(tc, "id");
                        if (id != null) agg.id = id;
                        String type = textOrNull(tc, "type");
                        if (type != null) agg.type = type;

                        JsonNode fn = tc.path("function");
                        if (!fn.isMissingNode()) {
                            String name = textOrNull(fn, "name");
                            if (name != null) agg.name = name;
                            String argsPiece = textOrNull(fn, "arguments");
                            if (argsPiece != null) agg.arguments.append(argsPiece);
                        }
                    }
                }
            } else {
                // 非标准：兼容顶层
                appendMaybeTopLevel(root);
            }
        }

        void appendFallbackText(String txt) {
            if (txt != null && !txt.isBlank()) {
                this.content.append(txt);
            }
        }

        private void appendMaybeTopLevel(JsonNode root) {
            // 兼容一些非标准字段
            String rc = textDeep(root, "message", "content");
            if (rc != null) this.content.append(rc);

            String rt = textDeep(root, "message", "thinking");
            if (rt != null) this.reasoning.append(rt);

            String c = textOrNull(root, "content");
            if (c != null) this.content.append(c);
        }

        String toOpenAIStyleJson(ObjectMapper mapper) throws Exception {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode choices = mapper.createArrayNode();

            ObjectNode choice = mapper.createObjectNode();
            ObjectNode message = mapper.createObjectNode();
            message.put("role", role);
            message.put("content", content.toString());

            // 非标准扩展：保留 reasoning（如果有）
            if (reasoning.length() > 0) {
                message.put("reasoning", reasoning.toString());
            }

            if (!toolCalls.isEmpty()) {
                ArrayNode tca = mapper.createArrayNode();
                for (ToolCallAgg t : toolCalls) {
                    ObjectNode tcn = mapper.createObjectNode();
                    if (t.id != null) tcn.put("id", t.id);
                    tcn.put("type", t.type != null ? t.type : "function");

                    ObjectNode fn = mapper.createObjectNode();
                    if (t.name != null) fn.put("name", t.name);
                    // 注意：arguments 必须是字符串（模型流式输出的 JSON 片段）
                    fn.put("arguments", t.arguments.toString());
                    tcn.set("function", fn);
                    tca.add(tcn);
                }
                message.set("tool_calls", tca);
            }

            choice.set("message", message);
            if (finishReason != null) choice.put("finish_reason", finishReason);

            choices.add(choice);
            root.set("choices", choices);
            return mapper.writeValueAsString(root);
        }

        String fallbackAsSimpleJson(ObjectMapper mapper) {
            try {
                ObjectNode root = mapper.createObjectNode();
                ArrayNode choices = mapper.createArrayNode();
                ObjectNode choice = mapper.createObjectNode();
                ObjectNode msg = mapper.createObjectNode();
                msg.put("role", role);
                msg.put("content", content.toString());
                choice.set("message", msg);
                choices.add(choice);
                root.set("choices", choices);
                return mapper.writeValueAsString(root);
            } catch (Exception e) {
                return content.toString();
            }
        }

        String contentText() {
            return content.toString();
        }

        String reasoningText() {
            return reasoning.toString();
        }

        String effectiveText() {
            if (content.length() > 0) {
                return content.toString();
            }
            if (reasoning.length() > 0) {
                return reasoning.toString();
            }
            return "";
        }

        private static String textOrNull(JsonNode node, String field) {
            if (node == null || node.isMissingNode()) return null;
            JsonNode v = node.get(field);
            return (v != null && !v.isNull()) ? v.asText() : null;
        }

        private static String textDeep(JsonNode node, String f1, String f2) {
            if (node == null) return null;
            JsonNode n1 = node.path(f1);
            if (n1.isMissingNode()) return null;
            return textOrNull(n1, f2);
        }

        private static final class ToolCallAgg {
            String id;
            String type;
            String name;
            final StringBuilder arguments = new StringBuilder();
        }
    }

    @Override
    public Flux<String> continueAfterToolsStreamAsync(Map<String, Object> payload) {
        log.debug("continueAfterToolsStreamAsync invoked payloadKeys={}", payload != null ? payload.keySet() : "null");

        boolean deltaText = false;
        boolean mergeFinal = false;
        boolean rawStream = true; // 默认透传

        if (payload != null) {
            Object dt = payload.get("_delta_text");
            if (dt != null) deltaText = (dt instanceof Boolean) ? (Boolean) dt : Boolean.parseBoolean(String.valueOf(dt));

            Object mf = payload.get("_merge_final");
            if (mf != null) mergeFinal = (mf instanceof Boolean) ? (Boolean) mf : Boolean.parseBoolean(String.valueOf(mf));

            Object rs = payload.get("_raw_stream");
            if (rs != null) rawStream = (rs instanceof Boolean) ? (Boolean) rs : Boolean.parseBoolean(String.valueOf(rs));
        }

        // 若指定 _delta_text 为 true，则输出“纯文本增量”，不再透传 JSON
        if (deltaText) {
            return callChatCompletionsStream(payload, false) // false => 使用 extractDeltaSafely 输出可读文本
                    .doOnSubscribe(s -> log.debug("continueAfterToolsStreamAsync (_delta_text) subscribed"))
                    .doOnComplete(() -> log.debug("continueAfterToolsStreamAsync (_delta_text) completed"))
                    .doOnError(err -> log.error("continueAfterToolsStreamAsync (_delta_text) failed", err));
        }

        // 若需要合并最终 JSON：边透传 raw JSON 边累积，结束时再追加一条合并后的完整 JSON
        if (mergeFinal) {
            StreamAccumulator acc = new StreamAccumulator();
            return callChatCompletionsStream(payload, true) // 原样透传
                    .doOnNext(chunk -> {
                        try {
                            acc.applyChunk(mapper, chunk);
                        } catch (Exception e) {
                            log.warn("continueAfterToolsStreamAsync applyChunk failed, forwarding anyway", e);
                            acc.appendFallbackText(chunk);
                        }
                    })
                    .concatWith(Mono.fromSupplier(() -> {
                        try {
                            String merged = acc.toOpenAIStyleJson(mapper);
                            log.debug("continueAfterToolsStreamAsync emit merged final JSON length={}", merged.length());
                            return merged;
                        } catch (Exception e) {
                            log.error("continueAfterToolsStreamAsync build merged final JSON failed", e);
                            return acc.fallbackAsSimpleJson(mapper);
                        }
                    }))
                    .doOnSubscribe(s -> log.debug("continueAfterToolsStreamAsync (_merge_final) subscribed"))
                    .doOnComplete(() -> log.debug("continueAfterToolsStreamAsync (_merge_final) completed"))
                    .doOnError(err -> log.error("continueAfterToolsStreamAsync (_merge_final) failed", err));
        }

        // 默认：raw 透传（逐帧 JSON），可用 _raw_stream=false 强制走 delta 文本解析
        if (!rawStream) {
            return callChatCompletionsStream(payload, false)
                    .doOnSubscribe(s -> log.debug("continueAfterToolsStreamAsync (forced-delta) subscribed"))
                    .doOnComplete(() -> log.debug("continueAfterToolsStreamAsync (forced-delta) completed"))
                    .doOnError(err -> log.error("continueAfterToolsStreamAsync (forced-delta) failed", err));
        }

        return callChatCompletionsStream(payload, true)
                .doOnSubscribe(s -> log.debug("continueAfterToolsStreamAsync (raw) subscribed"))
                .doOnComplete(() -> log.debug("continueAfterToolsStreamAsync (raw) completed"))
                .doOnError(err -> log.error("continueAfterToolsStreamAsync (raw) failed", err));
    }

    @Override
    public Flux<String> orchestrateChatStream(String userId,
                                              String conversationId,
                                              String prompt,
                                              @Nullable String toolChoice,
                                              @Nullable Map<String, Object> options) {
        log.debug("orchestrateChatStream invoked userId={} conversationId={} toolChoice={} promptLen={}",
                userId, conversationId, toolChoice, prompt != null ? prompt.length() : 0);

        String normalizedChoice = normalizeToolChoice(toolChoice);

        boolean deltaText = getBool(options, "_delta_text", false);
        boolean mergeFinal = getBool(options, "_merge_final", false);
        boolean rawStream  = getBool(options, "_raw_stream", !deltaText && !mergeFinal);

        int historyWindow = resolveMemoryWindow(MAX_TOOL_LIMIT);
        List<Map<String, Object>> history = limitWindow(
                memoryService.getHistory(userId, conversationId),
                historyWindow
        );

        // 对话骨架（和 v2 非流式一致）
        List<Map<String, Object>> conversation = new ArrayList<>();
        conversation.add(createMessage("system", SYSTEM_PROMPT_CORE));
        conversation.addAll(history);
        Map<String, Object> userMsg = createMessage("user", prompt);
        conversation.add(userMsg);

        // 启动工具决策流，实时向前端推送规划片段
        DecisionStreamResult decisionStream = streamToolDecision(userId, conversationId, prompt, history);
        Mono<ModelDecision> decisionMono = decisionStream.decision();

        Flux<String> planningEvents = decisionStream.frames()
                .map(this::buildDecisionChunkEvent)
                .onErrorResume(err -> {
                    log.warn("Decision frame stream failed userId={} conversationId={}", userId, conversationId, err);
                    return Flux.just(buildDecisionErrorEvent(err.getMessage()));
                });

        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(4))
                .map(this::buildDecisionHeartbeat)
                .takeUntilOther(decisionMono.then());

        Flux<String> finalFlux = decisionMono.flatMapMany(decision -> {
            List<Map<String, Object>> updated = new ArrayList<>(conversation);

            if (decision != null && decision.hasToolCalls()) {
                try {
                    List<Map<String, Object>> toolMsgs = runToolsAndBuildMessages(decision, userId, conversationId, prompt);
                    updated.addAll(toolMsgs);
                } catch (Exception e) {
                    log.warn("Tool execution failed during v2 stream; fallback to no tools", e);
                }
            } else if ("required".equals(normalizedChoice)) {
                ModelDecision forced = buildForcedDecision(userId, conversationId, prompt);
                try {
                    List<Map<String, Object>> toolMsgs = runToolsAndBuildMessages(forced, userId, conversationId, prompt);
                    updated.addAll(toolMsgs);
                } catch (Exception e) {
                    log.warn("Forced tool execution failed during v2 stream; fallback to no tools", e);
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("messages", updated);
            payload.put("tool_choice", "none");
            if (deltaText)  payload.put("_delta_text", true);
            if (mergeFinal) payload.put("_merge_final", true);
            if (!deltaText && !mergeFinal) payload.put("_raw_stream", rawStream);

            StringBuilder finalText = new StringBuilder();
            StreamAccumulator rawAccumulator = (!deltaText && !mergeFinal) ? new StreamAccumulator() : null;

            Flux<String> stream = continueAfterToolsStreamAsync(payload)
                    .doOnNext(chunk -> {
                        if (deltaText) {
                            finalText.append(chunk);
                        } else if (mergeFinal) {
                            try {
                                JsonNode root = mapper.readTree(chunk);
                                JsonNode msgNode = root.path("choices").path(0).path("message");
                                if (!msgNode.isMissingNode() && msgNode.has("content")) {
                                    String c = msgNode.path("content").asText(null);
                                    if (c != null) {
                                        finalText.setLength(0);
                                        finalText.append(c);
                                    }
                                }
                            } catch (Exception ignored) {
                                // ignored: intermediate frames may not be valid JSON
                            }
                        } else if (rawAccumulator != null) {
                            try {
                                rawAccumulator.applyChunk(mapper, chunk);
                            } catch (Exception e) {
                                log.debug("Failed to accumulate raw stream chunk", e);
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (!deltaText && !mergeFinal && rawAccumulator != null) {
                            String aggregated = rawAccumulator.contentText();
                            if (aggregated == null || aggregated.isBlank()) {
                                aggregated = rawAccumulator.reasoningText();
                            }
                            if ((aggregated == null || aggregated.isBlank())) {
                                aggregated = rawAccumulator.effectiveText();
                            }
                            if (aggregated != null && !aggregated.isBlank()) {
                                finalText.setLength(0);
                                finalText.append(aggregated);
                            }
                        }
                        try {
                            appendToMemory(userId, conversationId,
                                    List.of(userMsg, createMessage("assistant", finalText.toString())));
                        } catch (Exception e) {
                            log.warn("Failed to append messages to memory after v2 stream", e);
                        }
                    });

            return stream;
        });

        return Flux.merge(heartbeat, planningEvents, finalFlux)
                .onErrorResume(ex -> {
                    log.error("orchestrateChatStream failed userId={} conversationId={}", userId, conversationId, ex);
                    return Flux.just("抱歉，流式会话发生异常，请稍后重试。");
                });
    }

    @Override
    public Flux<String> orchestrateStepNdjson(V2StepNdjsonRequest req) {
        if (req == null) {
            return Flux.just(toNdjson(errorEvent("request body is required")));
        }

        return Flux.create(sink -> {
            StepState state = new StepState(req);
            AtomicBoolean completed = new AtomicBoolean(false);

            sink.onDispose(() -> {
                completed.set(true);
                if (state.heartbeat != null && !state.heartbeat.isDisposed()) {
                    state.heartbeat.dispose();
                }
            });

            sink.next(toNdjson(NdjsonEvent.builder()
                    .event("started")
                    .ts(now())
                    .data(state.startedData())
                    .build()));

            state.heartbeat = Flux.interval(Duration.ofSeconds(Math.max(1L, stepJsonHeartbeatSeconds)))
                    .subscribe(tick -> {
                        if (!completed.get()) {
                            sink.next(toNdjson(NdjsonEvent.builder()
                                    .event("heartbeat")
                                    .ts(now())
                                    .build()));
                        }
                    });

            runStepFlow(state, sink)
                    .subscribe(unused -> { },
                            error -> {
                                handleStepError(sink, state, error);
                                completed.set(true);
                                if (state.heartbeat != null && !state.heartbeat.isDisposed()) {
                                    state.heartbeat.dispose();
                                }
                                sink.complete();
                            },
                            () -> {
                                completed.set(true);
                                if (state.heartbeat != null && !state.heartbeat.isDisposed()) {
                                    state.heartbeat.dispose();
                                }
                                sink.complete();
                            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Mono<Void> runStepFlow(StepState state, FluxSink<String> sink) {
        if (!state.hasIdentifiers()) {
            String message = "userId and conversationId are required";
            sink.next(toNdjson(errorEvent(message)));
            state.error = message;
            state.finished = true;
            state.remainingLoops = 0;
            state.assistantSummary = Optional.ofNullable(state.assistantSummary).orElse(message);
            emitStep(sink, state);
            return Mono.empty();
        }

        state.appendClientResults();

        if (!state.hasUserPrompt && !state.hasClientResults) {
            String message = "Either q or clientResults must be provided";
            sink.next(toNdjson(errorEvent(message)));
            state.error = message;
            state.finished = true;
            state.remainingLoops = 0;
            state.assistantSummary = Optional.ofNullable(state.assistantSummary).orElse(message);
            emitStep(sink, state);
            return Mono.empty();
        }

        if (state.hasUserPrompt) {
            sink.next(toNdjson(progressEvent("deciding")));
            return decideStep(state)
                    .flatMap(decision -> handleDecision(state, sink, decision));
        } else {
            sink.next(toNdjson(progressEvent("continuing")));
            return continueWithoutDecision(state, sink);
        }
    }

    private Mono<DecisionPayload> decideStep(StepState state) {
        List<Map<String, Object>> messages = new ArrayList<>(state.conversation);
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);

        if (!state.mergedTools.isEmpty()) {
            payload.put("tools", state.mergedTools);
            payload.put("tool_choice", normalizeToolChoice(state.request.getToolChoice()));
        } else {
            payload.put("tool_choice", "none");
        }

        return callChatCompletions(payload, false)
                .map(json -> new DecisionPayload(parseDecision(json), json, extractContentSafely(json)));
    }

    private Mono<Void> handleDecision(StepState state, FluxSink<String> sink, DecisionPayload payload) {
        return Mono.defer(() -> {
            ModelDecision decision = payload.decision();
            String summary = payload.assistantSummary();
            if (summary != null && !summary.isBlank()) {
                state.assistantSummary = summary;
            }

            if (decision == null || !decision.hasToolCalls()) {
                if (summary != null && !summary.isBlank()) {
                    state.conversation.add(createMessage("assistant", summary));
                    state.finalAnswer = summary;
                }
                state.finished = true;
                state.remainingLoops = 0;
                emitStep(sink, state);
                return Mono.empty();
            }

            List<ToolCall> toolCalls = decision.getToolCalls();
            List<AiToolExecutor.ToolCall> allCalls = new ArrayList<>();
            List<AiToolExecutor.ToolCall> serverCalls = new ArrayList<>();
            Map<String, String> serverNameById = new HashMap<>();
            List<ToolCallDTO> clientCalls = new ArrayList<>();

            for (ToolCall call : toolCalls) {
                Map<String, Object> arguments = parseArguments(call);
                AiToolExecutor.ToolCall execCall = new AiToolExecutor.ToolCall(
                        call.getId(),
                        call.getName(),
                        call.getArgumentsJson()
                );
                allCalls.add(execCall);
                if (toolRegistry.isServerTool(call.getName())) {
                    serverCalls.add(execCall);
                    serverNameById.put(call.getId(), call.getName());
                } else if (state.clientToolNames.contains(call.getName())) {
                    clientCalls.add(buildToolCallDto(call, ExecTarget.CLIENT, arguments));
                } else {
                    clientCalls.add(buildToolCallDto(call, ExecTarget.CLIENT, arguments));
                }
            }

            if (!allCalls.isEmpty()) {
                state.conversation.add(toolExecutor.toAssistantToolCallsMessage(allCalls));
            }

            Mono<ServerExecutionResult> serverExecution = serverCalls.isEmpty()
                    ? Mono.just(new ServerExecutionResult(Collections.emptyList(), Collections.emptyList()))
                    : Mono.fromCallable(() -> executeServerCalls(serverCalls, state, serverNameById))
                    .subscribeOn(Schedulers.boundedElastic());

            return serverExecution.flatMap(result -> {
                if (!result.messages().isEmpty()) {
                    state.conversation.addAll(result.messages());
                }
                if (!result.results().isEmpty()) {
                    state.serverResults.addAll(result.results());
                    sink.next(toNdjson(NdjsonEvent.builder()
                            .event("serverResults")
                            .ts(now())
                            .data(Map.of("results", result.results()))
                            .build()));
                }

                if (!clientCalls.isEmpty()) {
                    state.pendingClientCalls.addAll(clientCalls);
                    sink.next(toNdjson(NdjsonEvent.builder()
                            .event("pendingClientCalls")
                            .ts(now())
                            .data(Map.of("calls", clientCalls))
                            .build()));
                    state.finished = false;
                    state.remainingLoops = Math.max(0, maxToolLoops - 1);
                    if (state.assistantSummary == null || state.assistantSummary.isBlank()) {
                        state.assistantSummary = "等待客户端工具执行。";
                    }
                    emitStep(sink, state);
                    return Mono.empty();
                }

                return continueAfterServerTools(state, sink, summary);
            });
        });
    }

    private Mono<Void> continueAfterServerTools(StepState state, FluxSink<String> sink, String fallbackSummary) {
        sink.next(toNdjson(progressEvent("continuing")));

        List<Map<String, Object>> messages = new ArrayList<>(state.conversation);
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        if (!state.mergedTools.isEmpty()) {
            payload.put("tools", state.mergedTools);
            payload.put("tool_choice", normalizeToolChoice(state.request.getToolChoice()));
        } else {
            payload.put("tool_choice", "none");
        }

        return callChatCompletions(payload, false)
                .flatMap(json -> {
                    ModelDecision followUp = parseDecision(json);
                    String content = extractContentSafely(json);
                    if (content != null && !content.isBlank()) {
                        state.assistantSummary = content;
                    } else if (fallbackSummary != null && !fallbackSummary.isBlank()) {
                        state.assistantSummary = fallbackSummary;
                    }

                    if (followUp != null && followUp.hasToolCalls()) {
                        ToolCallSplit split = splitToolCalls(followUp.getToolCalls(), state);
                        if (!split.client().isEmpty()) {
                            state.pendingClientCalls.addAll(split.client());
                            sink.next(toNdjson(NdjsonEvent.builder()
                                    .event("pendingClientCalls")
                                    .ts(now())
                                    .data(Map.of("calls", split.client()))
                                    .build()));
                            state.finished = false;
                            state.remainingLoops = Math.max(0, maxToolLoops - 1);
                            emitStep(sink, state);
                            return Mono.empty();
                        }
                        if (!split.server().isEmpty()) {
                            log.warn("Follow-up produced {} server tool call(s) after single-round execution", split.server().size());
                            state.finished = false;
                            state.remainingLoops = Math.max(0, maxToolLoops - 1);
                            if (state.assistantSummary == null || state.assistantSummary.isBlank()) {
                                state.assistantSummary = "额外的服务端操作将在下一轮执行。";
                            }
                            emitStep(sink, state);
                            return Mono.empty();
                        }
                    }

                    if (content != null && !content.isBlank()) {
                        state.conversation.add(createMessage("assistant", content));
                        state.finalAnswer = content;
                    }
                    state.finished = true;
                    state.remainingLoops = 0;
                    emitStep(sink, state);
                    return Mono.empty();
                });
    }

    private Mono<Void> continueWithoutDecision(StepState state, FluxSink<String> sink) {
        List<Map<String, Object>> messages = new ArrayList<>(state.conversation);
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        if (!state.mergedTools.isEmpty()) {
            payload.put("tools", state.mergedTools);
            payload.put("tool_choice", normalizeToolChoice(state.request.getToolChoice()));
        } else {
            payload.put("tool_choice", "none");
        }

        return callChatCompletions(payload, false)
                .flatMap(json -> {
                    ModelDecision followUp = parseDecision(json);
                    String content = extractContentSafely(json);
                    if (content != null && !content.isBlank()) {
                        state.assistantSummary = content;
                    }

                    if (followUp != null && followUp.hasToolCalls()) {
                        ToolCallSplit split = splitToolCalls(followUp.getToolCalls(), state);
                        if (!split.client().isEmpty()) {
                            state.pendingClientCalls.addAll(split.client());
                            sink.next(toNdjson(NdjsonEvent.builder()
                                    .event("pendingClientCalls")
                                    .ts(now())
                                    .data(Map.of("calls", split.client()))
                                    .build()));
                            state.finished = false;
                            state.remainingLoops = Math.max(0, maxToolLoops - 1);
                            emitStep(sink, state);
                            return Mono.empty();
                        }
                        if (!split.server().isEmpty()) {
                            log.warn("Continuation produced {} server tool call(s) without new prompt", split.server().size());
                            state.finished = false;
                            state.remainingLoops = Math.max(0, maxToolLoops - 1);
                            if (state.assistantSummary == null || state.assistantSummary.isBlank()) {
                                state.assistantSummary = "服务端将继续处理后续操作。";
                            }
                            emitStep(sink, state);
                            return Mono.empty();
                        }
                    }

                    if (content != null && !content.isBlank()) {
                        state.conversation.add(createMessage("assistant", content));
                        state.finalAnswer = content;
                    }
                    state.finished = true;
                    state.remainingLoops = 0;
                    emitStep(sink, state);
                    return Mono.empty();
                });
    }

    private ServerExecutionResult executeServerCalls(List<AiToolExecutor.ToolCall> serverCalls,
                                                     StepState state,
                                                     Map<String, String> serverNameById) throws Exception {
        Map<String, Object> fallbackArgs = new HashMap<>();
        fallbackArgs.put("userId", state.userId);
        fallbackArgs.put("conversationId", state.conversationId);
        fallbackArgs.put("maxMessages", resolveMemoryWindow(12));
        if (state.request.getQ() != null && !state.request.getQ().isBlank()) {
            fallbackArgs.put("query", state.request.getQ());
        }

        List<Map<String, Object>> messages = toolExecutor.executeAll(serverCalls, fallbackArgs);
        List<ToolResultDTO> results = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            String id = Objects.toString(message.get("tool_call_id"), null);
            String name = serverNameById.getOrDefault(id, "");
            String content = Objects.toString(message.get("content"), "");
            results.add(new ToolResultDTO(id, name, content));
        }
        return new ServerExecutionResult(messages, results);
    }

    private ToolCallSplit splitToolCalls(List<ToolCall> calls, StepState state) {
        if (calls == null || calls.isEmpty()) {
            return new ToolCallSplit(Collections.emptyList(), Collections.emptyList());
        }
        List<ToolCallDTO> client = new ArrayList<>();
        List<ToolCallDTO> server = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> arguments = parseArguments(call);
            if (toolRegistry.isServerTool(call.getName())) {
                server.add(buildToolCallDto(call, ExecTarget.SERVER, arguments));
            } else if (state.clientToolNames.contains(call.getName())) {
                client.add(buildToolCallDto(call, ExecTarget.CLIENT, arguments));
            } else {
                client.add(buildToolCallDto(call, ExecTarget.CLIENT, arguments));
            }
        }
        return new ToolCallSplit(client, server);
    }

    private ToolCallDTO buildToolCallDto(ToolCall call, ExecTarget target, Map<String, Object> arguments) {
        ToolCallDTO dto = new ToolCallDTO();
        dto.setId(call.getId());
        dto.setName(call.getName());
        dto.setArguments(arguments == null ? Map.of() : new LinkedHashMap<>(arguments));
        dto.setExecTarget(target);
        return dto;
    }

    private Map<String, Object> parseArguments(ToolCall call) {
        if (call == null || call.getArgumentsJson() == null || call.getArgumentsJson().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(call.getArgumentsJson(), MAP_TYPE);
        } catch (Exception ex) {
            log.warn("Failed to parse tool arguments for {}: {}", call.getName(), ex.getMessage());
            return Map.of();
        }
    }

    private String toNdjson(Object value) {
        try {
            return mapper.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize NDJSON payload", e);
            return "{\"event\":\"error\",\"data\":{\"message\":\"serialization failed\"}}\n";
        }
    }

    private NdjsonEvent progressEvent(String stage) {
        return NdjsonEvent.builder()
                .event("progress")
                .ts(now())
                .data(Map.of("stage", stage))
                .build();
    }

    private NdjsonEvent errorEvent(String message) {
        return NdjsonEvent.builder()
                .event("error")
                .ts(now())
                .data(Map.of("message", message))
                .build();
    }

    private void emitStep(FluxSink<String> sink, StepState state) {
        OrchestrationStep step = OrchestrationStep.builder()
                .stepId(state.stepId)
                .finished(state.finished)
                .remainingLoops(state.remainingLoops)
                .context(buildContextSummary(state.conversation))
                .serverResults(new ArrayList<>(state.serverResults))
                .pendingClientCalls(new ArrayList<>(state.pendingClientCalls))
                .assistant_summary(Optional.ofNullable(state.assistantSummary).orElse(""))
                .finalAnswer(state.finalAnswer)
                .error(state.error)
                .build();
        sink.next(toNdjson(NdjsonEvent.builder()
                .event("step")
                .ts(now())
                .data(convertToMap(step))
                .build()));
    }

    private void handleStepError(FluxSink<String> sink, StepState state, Throwable error) {
        String message = Optional.ofNullable(error.getMessage()).orElse("Internal error");
        log.error("orchestrateStepNdjson failed", error);
        sink.next(toNdjson(errorEvent(message)));
        state.error = message;
        state.finished = true;
        state.remainingLoops = 0;
        if (state.assistantSummary == null || state.assistantSummary.isBlank()) {
            state.assistantSummary = "发生错误，流程结束";
        }
        emitStep(sink, state);
    }

    private Map<String, Object> convertToMap(Object value) {
        try {
            return mapper.convertValue(value, MAP_TYPE);
        } catch (IllegalArgumentException ex) {
            log.warn("convertToMap failed for NDJSON payload", ex);
            return Map.of("error", "serialization");
        }
    }

    private OrchestrationStep.Context buildContextSummary(List<Map<String, Object>> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return OrchestrationStep.Context.builder()
                    .messages(List.of())
                    .build();
        }
        int limit = Math.min(resolveMemoryWindow(12), conversation.size());
        int start = Math.max(0, conversation.size() - limit);
        List<Map<String, Object>> summary = new ArrayList<>();
        for (int i = start; i < conversation.size(); i++) {
            Map<String, Object> msg = conversation.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", Objects.toString(msg.get("role"), "assistant"));
            Object content = msg.get("content");
            entry.put("content", content == null ? "" : content.toString());
            summary.add(entry);
        }
        return OrchestrationStep.Context.builder()
                .messages(summary)
                .build();
    }

    private Map<String, Object> toToolMessage(ToolResultDTO result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        if (result.getTool_call_id() != null) {
            message.put("tool_call_id", result.getTool_call_id());
        }
        if (result.getName() != null) {
            message.put("name", result.getName());
        }
        message.put("content", Optional.ofNullable(result.getContent()).orElse(""));
        return message;
    }

    private Set<String> extractClientToolNames(List<Map<String, Object>> clientTools) {
        if (clientTools == null || clientTools.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (Map<String, Object> tool : clientTools) {
            if (tool == null) {
                continue;
            }
            Object functionObj = tool.get("function");
            if (functionObj instanceof Map<?, ?> functionMap) {
                Object name = ((Map<?, ?>) functionMap).get("name");
                if (name != null) {
                    names.add(name.toString());
                }
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private List<Map<String, Object>> mergeToolSchemas(List<Map<String, Object>> serverTools,
                                                       List<Map<String, Object>> clientTools) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (serverTools != null) {
            for (Map<String, Object> tool : serverTools) {
                String name = extractFunctionName(tool);
                if (name != null) {
                    merged.putIfAbsent(name, tool);
                }
            }
        }
        if (clientTools != null) {
            for (Map<String, Object> tool : clientTools) {
                String name = extractFunctionName(tool);
                if (name != null) {
                    merged.put(name, tool);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String extractFunctionName(Map<String, Object> tool) {
        if (tool == null) {
            return null;
        }
        Object functionObj = tool.get("function");
        if (functionObj instanceof Map<?, ?> functionMap) {
            Object name = ((Map<?, ?>) functionMap).get("name");
            return name != null ? name.toString() : null;
        }
        return null;
    }

    private String now() {
        return OffsetDateTime.now().toString();
    }

    private class StepState {
        private final V2StepNdjsonRequest request;
        private final String stepId = "step-" + UUID.randomUUID();
        private final String userId;
        private final String conversationId;
        private final boolean hasUserPrompt;
        private final boolean hasClientResults;
        private final List<ToolResultDTO> clientResults;
        private final List<Map<String, Object>> clientTools;
        private final Set<String> clientToolNames;
        private final List<Map<String, Object>> serverToolSchemas;
        private final List<Map<String, Object>> mergedTools;
        private final List<Map<String, Object>> conversation = new ArrayList<>();
        private final List<Map<String, Object>> clientResultMessages;
        private final List<ToolResultDTO> serverResults = new ArrayList<>();
        private final List<ToolCallDTO> pendingClientCalls = new ArrayList<>();
        private boolean clientResultsAppended;
        private Disposable heartbeat;
        private boolean finished;
        private Integer remainingLoops;
        private String assistantSummary;
        private String finalAnswer;
        private String error;

        StepState(V2StepNdjsonRequest request) {
            this.request = request;
            this.userId = request.getUserId();
            this.conversationId = request.getConversationId();
            this.hasUserPrompt = request.getQ() != null && !request.getQ().isBlank();
            this.clientResults = request.getClientResults() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(request.getClientResults());
            this.hasClientResults = !this.clientResults.isEmpty();
            this.clientTools = request.getClientTools() == null
                    ? List.of()
                    : new ArrayList<>(request.getClientTools());
            this.clientToolNames = extractClientToolNames(this.clientTools);
            this.serverToolSchemas = toolRegistry.openAiServerToolsSchema();
            this.mergedTools = mergeToolSchemas(this.serverToolSchemas, this.clientTools);
            this.clientResultMessages = this.clientResults.stream()
                    .map(AiServiceImpl.this::toToolMessage)
                    .collect(Collectors.toCollection(ArrayList::new));
            buildInitialConversation();
        }

        private void buildInitialConversation() {
            conversation.add(createMessage("system", SYSTEM_PROMPT_CORE));
            if (userId != null && conversationId != null) {
                List<Map<String, Object>> history = limitWindow(
                        memoryService.getHistory(userId, conversationId),
                        resolveMemoryWindow(MAX_TOOL_LIMIT)
                );
                conversation.addAll(history);
            }
            if (hasUserPrompt) {
                conversation.add(createMessage("user", request.getQ()));
            }
        }

        Map<String, Object> startedData() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (userId != null) {
                data.put("userId", userId);
            }
            if (conversationId != null) {
                data.put("conversationId", conversationId);
            }
            data.put("hasQ", hasUserPrompt);
            data.put("hasClientResults", hasClientResults);
            if (request.getToolChoice() != null) {
                data.put("toolChoice", request.getToolChoice());
            }
            return data;
        }

        boolean hasIdentifiers() {
            return userId != null && conversationId != null;
        }

        void appendClientResults() {
            if (!clientResultsAppended && !clientResultMessages.isEmpty()) {
                conversation.addAll(clientResultMessages);
                clientResultsAppended = true;
            }
        }
    }

    private record DecisionPayload(ModelDecision decision, String rawResponse, String assistantSummary) {}

    private record ServerExecutionResult(List<Map<String, Object>> messages, List<ToolResultDTO> results) {}

    private record ToolCallSplit(List<ToolCallDTO> client, List<ToolCallDTO> server) {}

    // 小工具：读取 boolean 开关
    private static boolean getBool(@Nullable Map<String, Object> map, String key, boolean defVal) {
        if (map == null || !map.containsKey(key)) return defVal;
        Object v = map.get(key);
        return (v instanceof Boolean) ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v));
    }


}
