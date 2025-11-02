package com.example.audit;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditVerifyService verifyService;
    private final AuditExportService exportService;

    @Operation(summary = "审计校验，返回 JSON 报告")
    @GetMapping(value = "/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AuditVerifyService.Report> verify(@RequestParam String userId,
                                                  @RequestParam String conversationId) {
        return Mono.fromCallable(() -> verifyService.verify(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "导出 CSV（流式）")
    @GetMapping(value = "/export/csv", params = "format=csv", produces = "text/csv")
    public Mono<Void> exportCsv(@RequestParam String userId,
                                @RequestParam String conversationId,
                                ServerHttpResponse resp) {
        return exportService.streamCsv(userId, conversationId, resp);
    }

    // ✅ 合并两个路径到同一个处理方法；删除 exportJson_compat
    @Operation(summary = "导出 JSON（流式响应体）")
    @GetMapping(
            value = {"/export", "/export/json"},
            params = "format=json",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Flux<DataBuffer>>> exportJson(
            @RequestParam String userId,
            @RequestParam String conversationId) {
        return exportService.streamJson(userId, conversationId);
    }

    @Operation(summary = "导出 NDJSON（流式，逐行）")
    @GetMapping(value = "/export/ndjson", params = "format=ndjson", produces = "application/x-ndjson")
    public Mono<Void> exportNdjson(@RequestParam String userId,
                                   @RequestParam String conversationId,
                                   ServerHttpResponse resp) {
        return exportService.streamNdjson(userId, conversationId, resp);
    }
}
