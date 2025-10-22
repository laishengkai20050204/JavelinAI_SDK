package com.example.service.impl;

import com.example.service.AiService;
import com.example.service.ConversationMemoryService;
import com.example.service.impl.dto.ModelDecision;
import com.example.service.impl.dto.PlanAction;
import com.example.service.impl.dto.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final Set<String> SUPPORTED_THINK_LEVELS = Set.of("low", "medium", "high");

    public enum Compatibility { OLLAMA, OPENAI }

    private static final int MIN_TOOL_LIMIT = 1;
    private static final int MAX_TOOL_LIMIT = 50;
    private static final String SYSTEM_PROMPT_CORE = "You are a helpful assistant who writes concise, accurate answers.";
    private static final String SYSTEM_PROMPT_DECISION = "You decide whether to call the function find_relevant_memory before answering. Only call it when the user references previous context or when recalling history will help.";
    private static final String SYSTEM_PROMPT_OLLAMA_PLAN = "你是对话编排器。当需要查询历史时，只能输出：\n{\"action\":\"find_relevant_memory\",\"args\":{\"query\":\"...\", \"maxMessages\":12}}\n如果不需要工具，输出：{\"action\":\"final\"}";
    private static final String SYSTEM_PROMPT_OLLAMA_FINAL = "以上是检索结果和对话，请基于这些内容生成最终回答（只输出答案文本）。";

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final ConversationMemoryService memoryService;
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

    public AiServiceImpl(
            WebClient aiWebClient,
            ObjectMapper mapper,
            ConversationMemoryService memoryService,
            @Value("${ai.compatibility}") String compatibility,
            @Value("${ai.path}") String path,
            @Value("${ai.model}") String model
    ) {
        this.webClient = aiWebClient;
        this.mapper = mapper;
        this.memoryService = memoryService;
        this.compatibility = Compatibility.valueOf(compatibility.toUpperCase());
        this.path = path;
        this.model = model;
    }

    // ========= synchronous =========
    @Override
    public String chatOnce(String userMessage) {
        return chatOnceAsync(userMessage).block();
    }

    // ========= async (non-streaming) =========
    @Override
    public Mono<String> chatOnceAsync(String userMessage) {
        List<Map<String, Object>> messages = buildSingleUserMessage(userMessage);
        return chatOnceCore(messages).map(this::extractContentSafely);
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
                .timeout(Duration.ofSeconds(60))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(this::isRetryableError));
    }

    // ========= streaming (SSE/chunked) =========
    @Override
    public Flux<String> chatStream(String userMessage) {
        Map<String, Object> body = buildRequestBody(buildSingleUserMessage(userMessage), true);

        return webClient.post()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> mapToResponseStatus(response, "Upstream returned an error during stream"))
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(120))
                .flatMap(chunk -> Flux.fromArray(chunk.split("\\r?\\n")))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line)
                .takeWhile(data -> !"[DONE]".equalsIgnoreCase(data))
                .map(this::extractDeltaSafely)
                .filter(Objects::nonNull);
    }

    @Override
    public Mono<String> chatWithMemoryAsync(String userId, String conversationId, String userMessage) {
        int window = configuredMemoryWindow > 0 ? configuredMemoryWindow : MAX_TOOL_LIMIT;
        List<Map<String, Object>> relevant = memoryService.findRelevant(
                userId,
                conversationId,
                userMessage,
                window
        );
        List<Map<String, Object>> conversation = new ArrayList<>(relevant);

        if (conversation.isEmpty()) {
            conversation.addAll(limitWindow(
                    memoryService.getHistory(userId, conversationId),
                    window
            ));
        }

        Map<String, Object> userEntry = message("user", userMessage);
        conversation.add(userEntry);

        return chatOnceCore(conversation)
                .map(this::extractContentSafely)
                .doOnNext(reply -> memoryService.appendMessages(
                        userId,
                        conversationId,
                        List.of(userEntry, message("assistant", reply))
                ));
    }

    @Override
    public Mono<List<Map<String, Object>>> findRelevantMemoryAsync(String userId, String conversationId, String query, int limit) {
        int safeLimit = limit > 0 ? limit : Math.max(1, configuredMemoryWindow);
        return Mono.fromCallable(() -> new ArrayList<>(
                memoryService.findRelevant(userId, conversationId, query, safeLimit)
        ));
    }

    @Override
    public Mono<String> orchestrateChat(String userId, String conversationId, String prompt, @Nullable String toolChoice) {
        String normalizedChoice = normalizeToolChoice(toolChoice);
        int historyWindow = configuredMemoryWindow > 0 ? configuredMemoryWindow : MAX_TOOL_LIMIT;

        List<Map<String, Object>> history = limitWindow(
                memoryService.getHistory(userId, conversationId),
                historyWindow
        );
        Map<String, Object> userMessage = msg("user", prompt);

        List<Map<String, Object>> conversation = new ArrayList<>();
        conversation.add(msg("system", SYSTEM_PROMPT_CORE));
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

        return orchestration.flatMap(answer -> Mono.fromRunnable(() -> memoryService.appendMessages(
                        userId,
                        conversationId,
                        List.of(msg("user", prompt), msg("assistant", answer))
                ))
                .thenReturn(answer));
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
            Map<String, Object> args = new HashMap<>();
            args.put("userId", userId);
            args.put("conversationId", conversationId);
            args.put("query", prompt);
            args.put("maxMessages", configuredMemoryWindow > 0 ? configuredMemoryWindow : 12);
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
        if (!Set.of("auto", "none", "required").contains(normalized)) {
            log.warn("Unsupported tool_choice '{}' received - defaulting to auto", toolChoice);
            return "auto";
        }
        return normalized;
    }

    private Mono<ModelDecision> decideToolUse(String userId, String conversationId, String prompt) {
        try {
            int historyWindow = configuredMemoryWindow > 0 ? configuredMemoryWindow : MAX_TOOL_LIMIT;
            List<Map<String, Object>> history = limitWindow(
                    memoryService.getHistory(userId, conversationId),
                    historyWindow
            );

            List<Map<String, Object>> messages = new ArrayList<>();
            if (compatibility == Compatibility.OPENAI) {
                messages.add(msg("system", SYSTEM_PROMPT_DECISION));
            } else {
                messages.add(msg("system", SYSTEM_PROMPT_OLLAMA_PLAN));
            }
            messages.addAll(history);
            messages.add(msg("user", prompt));

            if (compatibility == Compatibility.OPENAI) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", model);
                payload.put("messages", messages);
                payload.put("tools", List.of(buildOpenAIToolSchema_FindRelevant()));
                payload.put("tool_choice", "auto");
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

        responses.add(assistantToolCallsMessage(decision.getToolCalls()));

        for (ToolCall call : decision.getToolCalls()) {
            if (!"find_relevant_memory".equalsIgnoreCase(call.getName())) {
                log.warn("Unsupported tool '{}' requested - skipping", call.getName());
                continue;
            }

            String argsJson = call.getArgumentsJson();
            String targetUserId = userId;
            String targetConversationId = conversationId;
            String query = prompt;
            int maxMessages = configuredMemoryWindow > 0 ? configuredMemoryWindow : 12;

            try {
                if (argsJson != null && !argsJson.isBlank()) {
                    JsonNode argsNode = mapper.readTree(argsJson);
                    if (argsNode.hasNonNull("userId")) {
                        targetUserId = argsNode.path("userId").asText(targetUserId);
                    }
                    if (argsNode.hasNonNull("conversationId")) {
                        targetConversationId = argsNode.path("conversationId").asText(targetConversationId);
                    }
                    if (argsNode.has("query") && !argsNode.path("query").isNull()) {
                        query = argsNode.path("query").asText(query);
                    }
                    if (argsNode.has("maxMessages")) {
                        maxMessages = argsNode.path("maxMessages").asInt(maxMessages);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse tool arguments for call {}", call.getId(), e);
            }

            maxMessages = Math.min(Math.max(maxMessages, MIN_TOOL_LIMIT), MAX_TOOL_LIMIT);

            List<Map<String, Object>> relevant = memoryService.findRelevant(
                    targetUserId,
                    targetConversationId,
                    query,
                    maxMessages
            );

            try {
                String serialized = mapper.writeValueAsString(relevant);
                responses.add(toolMessage(call.getId(), serialized));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool response for call {}", call.getId(), e);
                responses.add(toolMessage(call.getId(), "[]"));
            }
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
        enriched.add(msg("system", SYSTEM_PROMPT_OLLAMA_FINAL));

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

    private Map<String, Object> buildOpenAIToolSchema_FindRelevant() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of("type", "string"));
        properties.put("conversationId", Map.of("type", "string"));
        properties.put("query", Map.of("type", "string"));
        properties.put("maxMessages", Map.of(
                "type", "integer",
                "minimum", MIN_TOOL_LIMIT,
                "maximum", MAX_TOOL_LIMIT,
                "default", Math.max(MIN_TOOL_LIMIT, configuredMemoryWindow)
        ));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("userId", "conversationId"));

        Map<String, Object> function = new HashMap<>();
        function.put("name", "find_relevant_memory");
        function.put("description", "按需取回与当前问题相关的历史消息。");
        function.put("parameters", parameters);

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private Map<String, Object> assistantToolCallsMessage(List<ToolCall> calls) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        List<Map<String, Object>> toolCallList = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", call.getName());
            function.put("arguments", call.getArgumentsJson() == null ? "{}" : call.getArgumentsJson());

            Map<String, Object> callMap = new HashMap<>();
            callMap.put("id", call.getId());
            callMap.put("type", "function");
            callMap.put("function", function);
            toolCallList.add(callMap);
        }
        message.put("tool_calls", toolCallList);
        message.put("content", "");
        return message;
    }

    private Map<String, Object> toolMessage(String callId, String contentJson) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", callId);
        message.put("content", contentJson);
        return message;
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
            if ("find_relevant_memory".equalsIgnoreCase(action)) {
                Map<String, Object> args = plan.getArgs() == null ? Map.of() : plan.getArgs();
                String argsJson = mapper.writeValueAsString(args);
                ToolCall call = new ToolCall("plan-" + UUID.randomUUID(), "find_relevant_memory", argsJson);
                return new ModelDecision(List.of(call), false);
            }
            if ("final".equalsIgnoreCase(action)) {
                return ModelDecision.finalOnly();
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
        try {
            JsonNode root = mapper.readTree(data);

            if (compatibility == Compatibility.OPENAI) {
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull()) {
                    return delta.asText();
                }
                if (root.has("content")) {
                    return root.path("content").asText();
                }
                return null;
            } else {
                JsonNode messageNode = root.path("message");
                JsonNode thinking = messageNode.path("thinking");
                if (!thinking.isMissingNode() && !thinking.isNull() && !thinking.asText().isEmpty()) {
                    return "[THINK] " + thinking.asText(); // allow front-end to render reasoning trace
                }
                JsonNode content = messageNode.path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
                if (root.has("content")) {
                    return root.path("content").asText();
                }
                return null;
            }
        } catch (Exception e) {
            return data; // some providers stream plain text
        }
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
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", userMessage));
        return messages;
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
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

    @Override
    public Mono<String> decideToolsAsync(Map<String, Object> payload) {
        return callChatCompletions(payload, false);
    }

    @Override
    public Mono<String> continueAfterToolsAsync(Map<String, Object> payload) {
        return callChatCompletions(payload, false);
    }

    /**
     * Shared helper for tool-enabled chat completions (non-streaming by default).
     */
    private Mono<String> callChatCompletions(Map<String, Object> payload, boolean stream) {
        Object reqModel = payload.getOrDefault("model", model);
        Object messages = payload.get("messages");
        Object tools = payload.get("tools");
        Object toolChoice = payload.getOrDefault("tool_choice", "auto");

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
                .timeout(Duration.ofSeconds(60))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(this::isRetryableError));
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
}
