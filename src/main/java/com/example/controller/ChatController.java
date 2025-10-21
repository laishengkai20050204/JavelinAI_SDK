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

@Tag(name = "AI Chat")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private final AiService aiService;

    @Operation(summary = "对话接口（一次性，非流式）")
    @GetMapping("/chat")
    public Mono<String> chat(
            @Parameter(description = "用户问题") @RequestParam("q") String q) {
        // 直接走异步服务方法，避免在控制器里阻塞
        return aiService.chatOnceAsync(q);
    }

    @Operation(
            summary = "流式对话（SSE）",
            description = "以 text/event-stream 持续推送片段（前端自行拼接）",
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(mediaType = "text/event-stream",
                            schema = @Schema(implementation = String.class))
            )
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @Parameter(description = "用户问题") @RequestParam("q") String q) {

        // 业务数据流
        Flux<ServerSentEvent<String>> data = aiService.chatStream(q)
                .map(token -> ServerSentEvent.builder(token).build())
                // 出错时发一条 error 事件再完成（也可直接 complete）
                .onErrorResume(e ->
                        Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("ERR: " + e.getMessage())
                                .build()));

        // 心跳：防代理/网关超时（可按需关闭或调频）
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(t -> ServerSentEvent.<String>builder()
                        .event("ping")
                        .data("")
                        .build());

        // merge 心跳与数据；数据完成后整体结束
        return Flux.merge(heartbeat, data).takeUntilOther(data.ignoreElements().then(Mono.empty()));
    }
}
