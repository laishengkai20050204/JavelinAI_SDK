package com.example.controller;

import com.example.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "AI Chat")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Slf4j
public class ChatController {
    private final AiService aiService;

    @Operation(summary = "Chat once (non streaming)")
    @GetMapping("/chat")
    public Mono<String> chat(@RequestParam("q") String q) {
        log.debug("Handling /ai/chat request q='{}'", q);
        return aiService.chatOnceAsync(q)
                .doOnSuccess(response -> log.debug("chatOnceAsync succeeded q='{}'; responseLength={}",
                        q, response != null ? response.length() : 0))
                .doOnError(error -> log.error("chatOnceAsync failed q='{}'", q, error));
    }

    @Operation(summary = "Chat with memory", description = "Keeps conversation history per user and conversation.")
    @GetMapping("/chat/memory")
    public Mono<String> chatWithMemory(@RequestParam("userId") String userId,
                                       @RequestParam("conversationId") String conversationId,
                                       @RequestParam("q") String q) {
        log.debug("Handling /ai/chat/memory request userId={} conversationId={} q='{}'",
                userId, conversationId, q);
        return aiService.chatWithMemoryAsync(userId, conversationId, q)
                .doOnSuccess(response -> log.debug("chatWithMemoryAsync succeeded userId={} conversationId={} responseLength={}",
                        userId, conversationId, response != null ? response.length() : 0))
                .doOnError(error -> log.error("chatWithMemoryAsync failed userId={} conversationId={}",
                        userId, conversationId, error));
    }

    @Operation(summary = "Query user memory", description = "Find stored messages related to the query for a user & conversation.")
    @GetMapping("/memory")
    public Mono<List<Map<String, Object>>> memory(@RequestParam("userId") String userId,
                                                  @RequestParam("conversationId") String conversationId,
                                                  @RequestParam(value = "q", required = false) String query,
                                                  @RequestParam(value = "limit", defaultValue = "12") int limit) {
        log.debug("Handling /ai/memory request userId={} conversationId={} query='{}' limit={}",
                userId, conversationId, query, limit);
        return aiService.findRelevantMemoryAsync(userId, conversationId, query, limit)
                .doOnSuccess(results -> log.debug("findRelevantMemoryAsync returned {} record(s) userId={} conversationId={}",
                        results != null ? results.size() : 0, userId, conversationId))
                .doOnError(error -> log.error("findRelevantMemoryAsync failed userId={} conversationId={}",
                        userId, conversationId, error));
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
        log.debug("Handling /ai/stream request q='{}'", q);
        return aiService.chatStream(q)
                .doOnSubscribe(subscription -> log.debug("Subscribed to chatStream q='{}'", q))
                .doOnNext(chunk -> log.trace("chatStream chunk q='{}' length={}", q, chunk != null ? chunk.length() : 0))
                .doOnComplete(() -> log.debug("chatStream completed q='{}'", q))
                .doOnError(error -> log.error("chatStream failed q='{}'", q, error));
    }

    @PostMapping("/decide")
    @Operation(summary = "Tool call decision", description = "Ask the model which tools should run.")
    public Mono<String> decide(@RequestBody Map<String, Object> payload) {
        log.debug("Handling /ai/decide request payloadKeys={}", payload != null ? payload.keySet() : "null");
        return aiService.decideToolsAsync(payload)
                .doOnSuccess(response -> log.debug("decideToolsAsync succeeded payloadKeys={} responseLength={}",
                        payload != null ? payload.keySet() : "null", response != null ? response.length() : 0))
                .doOnError(error -> log.error("decideToolsAsync failed payloadKeys={}",
                        payload != null ? payload.keySet() : "null", error));
    }

    @PostMapping(value = "/decide/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Tool call decision (streaming)", description = "Stream incremental model decision updates via SSE (JSON payload per event).")
    public Flux<String> decideStream(@RequestBody Map<String, Object> payload) {
        log.debug("Handling /ai/decide/stream request payloadKeys={}", payload != null ? payload.keySet() : "null");
        return aiService.decideToolsStreamAsync(payload)
                .doOnSubscribe(subscription -> log.debug("Subscribed to decideToolsStream payloadKeys={}",
                        payload != null ? payload.keySet() : "null"))
                .doOnNext(chunk -> log.trace("decideToolsStream chunk length={}",
                        chunk != null ? chunk.length() : 0))
                .doOnComplete(() -> log.debug("decideToolsStream completed payloadKeys={}",
                        payload != null ? payload.keySet() : "null"))
                .doOnError(error -> log.error("decideToolsStreamAsync failed payloadKeys={}",
                        payload != null ? payload.keySet() : "null", error));
    }

    @PostMapping("/continue")
    @Operation(summary = "Continue after tools", description = "Send tool results back to the model for a final answer.")
    public Mono<String> continueAfterTools(@RequestBody Map<String, Object> payload) {
        log.debug("Handling /ai/continue request payloadKeys={}", payload != null ? payload.keySet() : "null");
        return aiService.continueAfterToolsAsync(payload)
                .doOnSuccess(response -> log.debug("continueAfterToolsAsync succeeded payloadKeys={} responseLength={}",
                        payload != null ? payload.keySet() : "null", response != null ? response.length() : 0))
                .doOnError(error -> log.error("continueAfterToolsAsync failed payloadKeys={}",
                        payload != null ? payload.keySet() : "null", error));
    }

    @PostMapping("/v2/chat")
    @Operation(summary = "Chat with memory tools (v2)", description = "Two-phase orchestration with optional tool usage.")
    public Mono<String> orchestratedChat(@RequestBody Map<String, String> payload) {
        String userId = payload.get("userId");
        String conversationId = payload.get("conversationId");
        String question = payload.get("q");
        String toolChoice = payload.get("toolChoice");
        log.debug("Handling /ai/v2/chat request userId={} conversationId={} toolChoice={}", userId, conversationId, toolChoice);
        if (userId == null || conversationId == null || question == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId, conversationId and q are required");
        }
        return aiService.orchestrateChat(userId, conversationId, question, toolChoice)
                .doOnSuccess(response -> log.debug("orchestrateChat succeeded userId={} conversationId={} responseLength={}",
                        userId, conversationId, response != null ? response.length() : 0))
                .doOnError(error -> log.error("orchestrateChat failed userId={} conversationId={} toolChoice={}",
                        userId, conversationId, toolChoice, error));
    }

    @PostMapping(value = "/continue/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Continue after tools (streaming)",
            description = "Stream final answer after providing tool results. Supports raw JSON frames, delta text, or merged JSON at end.")
    public Flux<String> continueAfterToolsStream(@RequestBody Map<String, Object> payload) {
        log.debug("Handling /ai/continue/stream request payloadKeys={}", payload != null ? payload.keySet() : "null");
        return aiService.continueAfterToolsStreamAsync(payload)
                .doOnSubscribe(s -> log.debug("Subscribed to continueAfterToolsStream payloadKeys={}",
                        payload != null ? payload.keySet() : "null"))
                .doOnNext(chunk -> log.trace("continueAfterToolsStream chunk length={}",
                        chunk != null ? chunk.length() : 0))
                .doOnComplete(() -> log.debug("continueAfterToolsStream completed payloadKeys={}",
                        payload != null ? payload.keySet() : "null"))
                .doOnError(err -> log.error("continueAfterToolsStream failed payloadKeys={}",
                        payload != null ? payload.keySet() : "null", err));
    }

    @PostMapping(value = "/v2/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Chat with memory tools (v2, streaming)",
            description = "One-shot orchestration with optional tools; streams final answer. " +
                    "Supports _delta_text (human-readable), _merge_final (final merged JSON), _raw_stream (raw JSON frames).")
    public Flux<String> orchestratedChatStream(@RequestBody Map<String, Object> payload) {
        String userId = (String) payload.get("userId");
        String conversationId = (String) payload.get("conversationId");
        String question = (String) payload.get("q");
        String toolChoice = (String) payload.get("toolChoice");

        if (userId == null || conversationId == null || question == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId, conversationId and q are required");
        }

        // 取可选 flags
        Map<String, Object> options = new HashMap<>();
        if (payload.containsKey("_delta_text"))  options.put("_delta_text",  payload.get("_delta_text"));
        if (payload.containsKey("_merge_final")) options.put("_merge_final", payload.get("_merge_final"));
        if (payload.containsKey("_raw_stream"))  options.put("_raw_stream",  payload.get("_raw_stream"));

        log.debug("Handling /ai/v2/chat/stream userId={} conversationId={} toolChoice={} options={}",
                userId, conversationId, toolChoice, options);

        return aiService.orchestrateChatStream(userId, conversationId, question, toolChoice, options)
                .doOnSubscribe(s -> log.debug("Subscribed to v2 chat stream"))
                .doOnNext(chunk -> log.trace("v2 chat stream chunk length={}", chunk != null ? chunk.length() : 0))
                .doOnComplete(() -> log.debug("v2 chat stream completed"))
                .doOnError(err -> log.error("v2 chat stream failed", err));
    }

}
