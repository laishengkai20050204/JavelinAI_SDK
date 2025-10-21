package com.example.service.Impl;


import com.example.service.AiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;


import java.util.List;
import java.util.Map;


@Service
public class AiServiceImpl implements AiService {


    public enum Compatibility { OLLAMA, OPENAI }


    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Compatibility compatibility;
    private final String path;
    private final String model;


    public AiServiceImpl(WebClient aiWebClient,
                     @Value("${ai.compatibility}") String compatibility,
                     @Value("${ai.path}") String path,
                     @Value("${ai.model}") String model) {
        this.webClient = aiWebClient;
        this.compatibility = Compatibility.valueOf(compatibility.toUpperCase());
        this.path = path;
        this.model = model;
    }


    public String chatOnce(String userMessage) {
        String json = switch (compatibility) {
            case OLLAMA -> callOllama(userMessage);
            case OPENAI -> callOpenAI(userMessage);
        };


// 优先尝试抽出文本内容，失败就原样返回 JSON，便于排错
        try {
            JsonNode root = mapper.readTree(json);
            if (compatibility == Compatibility.OLLAMA) {
                return root.path("message").path("content").asText(json);
            } else {
                return root.path("choices").path(0).path("message").path("content").asText(json);
            }
        } catch (Exception e) {
            return json;
        }
    }


    public String callOllama(String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", userMessage)),
                "stream", false
        );
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }


    public String callOpenAI(String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}