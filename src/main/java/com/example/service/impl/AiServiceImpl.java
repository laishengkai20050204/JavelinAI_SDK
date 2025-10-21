package com.example.service.impl;

import com.example.service.AiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiServiceImpl implements AiService {

    public enum Compatibility { OLLAMA, OPENAI }

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final Compatibility compatibility;
    private final String path;
    private final String model;

    public AiServiceImpl(
            WebClient aiWebClient,
            ObjectMapper mapper,
            @Value("${ai.compatibility}") String compatibility,
            @Value("${ai.path}") String path,
            @Value("${ai.model}") String model
    ) {
        this.webClient = aiWebClient;
        this.mapper = mapper;
        this.compatibility = Compatibility.valueOf(compatibility.toUpperCase());
        this.path = path;
        this.model = model;
    }

    // ========= 同步 =========
    @Override
    public String chatOnce(String userMessage) {
        return chatOnceAsync(userMessage).block();
    }

    // ========= 异步（非流式）=========
    @Override
    public Mono<String> chatOnceAsync(String userMessage) {
        return chatOnceCore(userMessage)
                .map(this::extractContentSafely);
    }

    private Mono<String> chatOnceCore(String userMessage) {
        Map<String, Object> body = switch (compatibility) {
            case OLLAMA, OPENAI -> Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userMessage)),
                    "stream", false
            );
        };

        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        resp -> resp.createException()
                                .map(e -> new RuntimeException("4xx client error: " + e.getMessage(), e)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        resp -> resp.createException()
                                .map(e -> new RuntimeException("5xx server error: " + e.getMessage(), e)))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(t -> !(t instanceof IllegalArgumentException)));
    }

    // ========= 流式（SSE/分块）=========
    @Override
    public Flux<String> chatStream(String userMessage) {
        Map<String, Object> body = switch (compatibility) {
            case OLLAMA, OPENAI -> Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userMessage)),
                    "stream", true
            );
        };

        ParameterizedTypeReference<ServerSentEvent<String>> sseType =
                new ParameterizedTypeReference<ServerSentEvent<String>>() {};

        return webClient.post()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.createException()
                                .map(e -> new RuntimeException("HTTP error: " + e.getMessage(), e)))
                .bodyToFlux(sseType)
                .timeout(Duration.ofSeconds(120))
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .takeWhile(d -> !"[DONE]".equalsIgnoreCase(d.trim()))
                .map(this::extractDeltaSafely)
                .filter(Objects::nonNull);
    }

    // ========= JSON 解析 =========
    private String extractContentSafely(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return switch (compatibility) {
                case OLLAMA -> root.path("message").path("content").asText(json);
                case OPENAI -> root.path("choices").path(0).path("message").path("content").asText(json);
            };
        } catch (Exception e) {
            return json; // 解析失败直接回原文，便于排查
        }
    }

    private String extractDeltaSafely(String data) {
        try {
            JsonNode root = mapper.readTree(data);
            if (compatibility == Compatibility.OPENAI) {
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull()) return delta.asText();
                if (root.has("content")) return root.path("content").asText();
                return null;
            } else {
                JsonNode msg = root.path("message").path("content");
                if (!msg.isMissingNode() && !msg.isNull()) return msg.asText();
                if (root.has("content")) return root.path("content").asText();
                return null;
            }
        } catch (Exception e) {
            return data; // 有些实现直接推纯文本
        }
    }
}
