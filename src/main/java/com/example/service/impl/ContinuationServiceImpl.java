package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ToolResult;
import com.example.service.ConversationMemoryService;
import com.example.service.ContinuationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContinuationServiceImpl implements ContinuationService {

    private final ConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;

    @Override
    public Mono<Void> appendToolResultsToMemory(String stepId, List<ToolResult> results) {
        StepContextStore.Key key = stepStore.get(stepId);
        if (key == null || results == null || results.isEmpty()) {
            return Mono.empty();
        }
        String userId = key.userId();
        String conversationId = key.conversationId();

        try {
            int seq = safeNextSeq(userId, conversationId, stepId);
            for (ToolResult r : results) {
                String content = extractReadableText(r.data());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", r.name());
                payload.put("tool_call_id", r.callId());
                payload.put("reused", r.reused());
                payload.put("status", r.status());
                payload.put("data", r.data());

                String payloadJson = toJson(payload);
                memoryService.upsertMessage(
                        userId, conversationId,
                        "tool",
                        content,
                        payloadJson,
                        stepId, seq++, "DRAFT" // 先落草稿
                );
            }

            // ✅ 新增：把本轮工具结果放进 StepContextStore，供下一次请求拼回 messages
            stepStore.saveToolResults(stepId, results);
            log.debug("[STEP] toolResults saved: stepId={} total={}", stepId, results.size());

            // 如需“工具成功后立即转正”，可在此处解开下一行
             memoryService.promoteDraftsToFinal(userId, conversationId, stepId);

        } catch (Exception ignore) {}
        return Mono.empty();
    }

    @Override
    public Mono<String> generateAssistant(AssembledContext ctx) {
        // 简单占位：返回上下文哈希
        return Mono.just("【占位】本轮上下文哈希: " + (ctx == null ? "NA" : ctx.hash()));
    }

    @Override
    public Mono<Void> appendAssistantToMemory(String stepId, String text) {
        StepContextStore.Key key = stepStore.get(stepId);
        if (key == null) {
            return Mono.empty();
        }
        String userId = key.userId();
        String conversationId = key.conversationId();

        int seq = safeNextSeq(userId, conversationId, stepId);
        memoryService.upsertMessage(userId, conversationId,
                "assistant", text == null ? "" : text, null,
                stepId, seq, "DRAFT"); // 先落草稿
        return Mono.empty();
    }

    // ---- helpers ----

    private int safeNextSeq(String userId, String conversationId, String stepId) {
        Integer max = memoryService.findMaxSeq(userId, conversationId, stepId);
        return (max == null ? 0 : max) + 1;
    }

    private String toJson(@Nullable Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private String extractReadableText(@Nullable Object data) {
        if (data == null) return "";
        if (data instanceof String s) return s;
        if (data instanceof Map<?, ?> m) {
            for (String k : List.of("value","text","content","message","delta")) {
                Object v = m.get(k);
                if (v instanceof String sv && !sv.isBlank()) return sv;
            }
            Object inner = m.get("payload");
            if (inner instanceof Map<?, ?> im) {
                for (String k : List.of("value","text","content","message","delta")) {
                    Object v = im.get(k);
                    if (v instanceof String sv && !sv.isBlank()) return sv;
                }
            }
            return toJson(m);
        }
        if (data instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            for (Object x : it) {
                String part = extractReadableText(x);
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        return String.valueOf(data);
    }
}
