package com.example.controller;

import com.example.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Tag(name = "AI Chat")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {
    private final AiService aiService;

    @Operation(summary = "对话接口（一次性，非流式）")
    @GetMapping("/chat")
    public Mono<String> chat(@RequestParam("q") String q) {
        return aiService.chatOnceAsync(q);
    }

    @Operation(
            summary = "流式对话（SSE）",
            description = "以 text/event-stream 持续推送片段（前端自行拼接）",
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = String.class))
            )
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("q") String q) {
        return aiService.chatStream(q)
                .onErrorResume(e -> Flux.just("ERR: " + e.getMessage()));
    }

    // ChatController.java
    @PostMapping("/decide")
    @Operation(summary = "函数调用-让模型决定要不要调用哪些函数（不执行）")
    public Mono<String> decide(@RequestBody Map<String, Object> payload) {
        return aiService.decideToolsAsync(payload);
    }

    @PostMapping("/continue")
    @Operation(summary = "函数调用-前端执行完工具后继续，让模型给出最终回复")
    public Mono<String> continueAfterTools(@RequestBody Map<String, Object> payload) {
        return aiService.continueAfterToolsAsync(payload);
    }


}
