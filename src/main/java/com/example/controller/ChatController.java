package com.example.controller;

import com.example.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Tag(name = "AI Chat")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {
    private final AiService aiService;

    @Operation(summary = "Chat once (non streaming)")
    @GetMapping("/chat")
    public Mono<String> chat(@RequestParam("q") String q) {
        return aiService.chatOnceAsync(q);
    }

    @Operation(summary = "Chat with memory", description = "Keeps conversation history per user and conversation.")
    @GetMapping("/chat/memory")
    public Mono<String> chatWithMemory(@RequestParam("userId") String userId,
                                       @RequestParam("conversationId") String conversationId,
                                       @RequestParam("q") String q) {
        return aiService.chatWithMemoryAsync(userId, conversationId, q);
    }

    @Operation(summary = "Query user memory", description = "Find stored messages related to the query for a user & conversation.")
    @GetMapping("/memory")
    public Mono<List<Map<String, Object>>> memory(@RequestParam("userId") String userId,
                                                  @RequestParam("conversationId") String conversationId,
                                                  @RequestParam(value = "q", required = false) String query,
                                                  @RequestParam(value = "limit", defaultValue = "12") int limit) {
        return aiService.findRelevantMemoryAsync(userId, conversationId, query, limit);
    }

    @Operation(
            summary = "Streaming chat (SSE)",
            description = "Uses text/event-stream to push incremental responses.",
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = String.class))
            )
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("q") String q) {
        return aiService.chatStream(q);
    }

    @PostMapping("/decide")
    @Operation(summary = "Tool call decision", description = "Ask the model which tools should run.")
    public Mono<String> decide(@RequestBody Map<String, Object> payload) {
        return aiService.decideToolsAsync(payload);
    }

    @PostMapping("/continue")
    @Operation(summary = "Continue after tools", description = "Send tool results back to the model for a final answer.")
    public Mono<String> continueAfterTools(@RequestBody Map<String, Object> payload) {
        return aiService.continueAfterToolsAsync(payload);
    }
}
