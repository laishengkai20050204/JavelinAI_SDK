package com.example.controller;

import com.example.api.dto.ChatRequest;
import com.example.api.dto.StepEvent;
import com.example.service.SinglePathChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class OrchestratedChatController {

    private final SinglePathChatService service;
    private final ObjectMapper objectMapper; // 你项目里应该已经有配置；若没有可 @Bean 提供

    @Operation(summary = "ndjson统一接口")
    @PostMapping(
            value = "/v3/chat/step/ndjson",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson"
    )
    public Flux<String> chatNdjson(@RequestBody ChatRequest req) {
        return service.run(req).map(this::toNdjsonLine);
    }

    private String toNdjsonLine(StepEvent e) {
        try {
            return objectMapper.writeValueAsString(e) + "\n";
        } catch (Exception ex) {
            // 严格来说这里应该记录日志；此处保持最简
            return "{\"event\":\"error\",\"ts\":\"\",\"data\":{\"message\":\"serialize failed\"}}\n";
        }
    }
}
