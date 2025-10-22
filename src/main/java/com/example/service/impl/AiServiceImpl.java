package com.example.service.impl;

import com.example.service.AiService;
import com.example.service.ConversationMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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

@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final Set<String> SUPPORTED_THINK_LEVELS = Set.of("low", "medium", "high");

    public enum Compatibility { OLLAMA, OPENAI }

    private static final int DEFAULT_MEMORY_WINDOW = 12;

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
        List<Map<String, Object>> relevant = memoryService.findRelevant(
                userId,
                conversationId,
                userMessage,
                DEFAULT_MEMORY_WINDOW
        );
        List<Map<String, Object>> conversation = new ArrayList<>(relevant);

        if (conversation.isEmpty()) {
            conversation.addAll(limitWindow(
                    memoryService.getHistory(userId, conversationId),
                    DEFAULT_MEMORY_WINDOW
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
        int safeLimit = limit > 0 ? limit : DEFAULT_MEMORY_WINDOW;
        return Mono.fromCallable(() -> new ArrayList<>(
                memoryService.findRelevant(userId, conversationId, query, safeLimit)
        ));
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
}
