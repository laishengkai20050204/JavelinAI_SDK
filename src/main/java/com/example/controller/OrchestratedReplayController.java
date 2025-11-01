package com.example.controller;

import com.example.service.ReplayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class OrchestratedReplayController {
    private final ReplayService replayService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "回放接口")
    @GetMapping(value="/replay/ndjson", produces="application/x-ndjson")
    public Flux<String> replayNdjson(
            @RequestParam String userId,
            @RequestParam String conversationId,
            @RequestParam(required=false) String stepId,
            @RequestParam(defaultValue="2000") int limit
    ) {
        return replayService.replay(userId, conversationId, stepId, limit)
                .map(e -> {
                    try { return objectMapper.writeValueAsString(e) + "\n"; }
                    catch (Exception ex) { return "{\"event\":\"error\",\"ts\":\"\",\"data\":{\"message\":\"serialize failed\"}}\n"; }
                });
    }
}
