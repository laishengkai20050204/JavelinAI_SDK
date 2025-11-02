package com.example.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditExportService {

    private final AuditVerifyService verifyService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    /** CSV 流式导出 */
    public Mono<Void> streamCsv(String userId, String conversationId, ServerHttpResponse resp) {
        resp.getHeaders().setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        resp.getHeaders().setContentDisposition(ContentDisposition.attachment()
                .filename(("audit-" + conversationId + ".csv"), StandardCharsets.UTF_8).build());

        DataBufferFactory buf = resp.bufferFactory();

        Flux<DataBuffer> body = Mono.fromCallable(() -> verifyService.fetchTimeline(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list ->
                        Flux.just("id,type,role,step_id,seq,state,created_at,prev_hash,hash\n")
                                .concatWith(Flux.fromIterable(list).map(this::toCsvLine))
                )
                .map(s -> buf.wrap(s.getBytes(StandardCharsets.UTF_8)));

        return resp.writeWith(body);
    }

    /** JSON 导出（一次性写出；若体量巨大建议改为 NDJSON） */
    public Mono<org.springframework.http.ResponseEntity<Flux<DataBuffer>>> streamJson(String userId, String conversationId) {
        return Mono.fromCallable(() -> verifyService.fetchTimeline(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(list -> {
                    Flux<DataBuffer> body = Flux.defer(() -> {
                        try {
                            byte[] bytes = objectMapper.writeValueAsBytes(list);
                            return Flux.just(new DefaultDataBufferFactory().wrap(bytes));
                        } catch (Exception e) {
                            return Flux.error(e);
                        }
                    });
                    return org.springframework.http.ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDisposition.attachment()
                                            .filename(("audit-" + conversationId + ".json"), StandardCharsets.UTF_8)
                                            .build().toString())
                            .body(body);
                });
    }

    /** NDJSON 流式导出（逐行一条 JSON） */
    public Mono<Void> streamNdjson(String userId, String conversationId, ServerHttpResponse resp) {
        resp.getHeaders().setContentType(MediaType.parseMediaType("application/x-ndjson; charset=UTF-8"));
        resp.getHeaders().setContentDisposition(ContentDisposition.attachment()
                .filename(("audit-" + conversationId + ".ndjson"), StandardCharsets.UTF_8).build());

        DataBufferFactory buf = resp.bufferFactory();

        Flux<DataBuffer> body = Mono.fromCallable(() -> verifyService.fetchTimeline(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .map(row -> {
                    try { return objectMapper.writeValueAsString(row) + "\n"; }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .map(s -> buf.wrap(s.getBytes(StandardCharsets.UTF_8)));

        return resp.writeWith(body);
    }

    private String toCsvLine(Map<String, Object> r) {
        String id = csv(r.get("id"));
        String type = csv(r.get("type"));
        String role = csv(r.get("role"));
        String stepId = csv(r.get("step_id"));
        String seq = csv(r.get("seq"));
        String state = csv(r.get("state"));

        String createdAt = "";
        Object ts = r.get("created_at");
        if (ts instanceof LocalDateTime ldt) {
            createdAt = ISO_MICROS.format(ldt);
        } else if (ts != null) {
            createdAt = csv(ts);
        }
        String prev = csv(r.get("prev_hash"));
        String hash = csv(r.get("hash"));

        return id + "," + type + "," + role + "," + stepId + "," + seq + "," + state + ","
                + createdAt + "," + prev + "," + hash + "\n";
    }

    private String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean q = s.contains(",") || s.contains("\"") || s.contains("\n");
        if (q) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
